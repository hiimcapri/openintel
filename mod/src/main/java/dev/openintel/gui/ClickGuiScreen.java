package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.radar.RadarHud;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sodium-style settings screen: a translucent category sidebar on the
 * left, a vertically scrolling options pane on the right, and a search
 * box that flattens matching options from every category into one list.
 *
 * The option model comes from {@link ClickGui#build} — Groups render as
 * section headers, Options as label + right-aligned widget rows. Every
 * Option widget (and its aux) is a real drawable child, so vanilla input
 * handles slider drags, button clicks and text-field focus for free.
 * Rendering is manual: each frame the screen computes every row's Y from
 * the scroll offset, repositions its widgets and draws them inside the
 * pane's scissor rect. {@code widget.visible} doubles as the input gate —
 * rows outside the clip can't be clicked.
 *
 * The screen also implements {@link ClickGui.RebindHandler}: clicking a
 * keybind row's button starts listening, the next key press or mouse
 * click becomes the binding, and ESC cancels.
 */
public class ClickGuiScreen extends Screen implements ClickGui.RebindHandler {

    // ----------------------------------------------------- layout (px) ----
    private static final int MARGIN = 16;
    private static final int SIDEBAR_W = 150;
    private static final int PANE_X = 178;
    private static final int PANE_BOTTOM_PAD = 40;  // pane bottom = height - 40
    private static final int INSET_X = 8;           // content inset inside pane
    private static final int INSET_TOP = 8;
    private static final int INSET_BOTTOM = 8;
    private static final int CAT_ROW_H = 20;
    private static final int OPTION_H = 22;
    private static final int GROUP_H = 22;
    private static final int WIDGET_W = 150;
    private static final int WIDGET_H = 20;
    private static final int WIDGET_COL = 280;  // preferred widget column, px from pane content left
    private static final int AUX_W = 60;
    private static final int AUX_GAP = 6;
    private static final double SCROLL_STEP = 22;

    // --------------------------------------------------------- colors -----
    private static final int ACCENT       = 0xFF7C8CF8;  // indigo
    private static final int SIDEBAR_BG   = 0xC00E0F14;
    private static final int PANE_BG      = 0xB8121319;
    private static final int PANEL_BORDER = 0x22FFFFFF;
    private static final int CAT_SELECTED = 0x2E7C8CF8;
    private static final int CAT_HOVER    = 0x1EFFFFFF;
    private static final int ROW_HOVER    = 0x0FFFFFFF;
    private static final int DIVIDER      = 0x33FFFFFF;
    private static final int TEXT_MAIN    = 0xFFE8EAF0;
    private static final int TEXT_BRIGHT  = 0xFFFFFFFF;
    private static final int TEXT_DIM     = 0xFF8E93A6;

    private final Screen parent;
    private final List<ClickGui.Category> categories;
    private final String version;
    private final EditBox searchField;
    private Button doneButton;

    private int selected;
    private List<ClickGui.Item> rows = List.of();
    private boolean viewDirty = true;
    private double scrollOffset;

    /** Non-null while a keybind row is capturing the next input. */
    private KeyMapping listeningFor;

    public ClickGuiScreen(Screen parent) {
        this(parent, null);
    }

    public ClickGuiScreen(Screen parent, String categoryId) {
        super(Component.literal("OpenIntel"));
        this.parent = parent;
        this.categories = ClickGui.build(this, this);

        int sel = 0;
        if (categoryId != null) {
            for (int i = 0; i < categories.size(); i++) {
                if (categories.get(i).id().equals(categoryId)) {
                    sel = i;
                    break;
                }
            }
        }
        this.selected = sel;

        this.version = FabricLoader.getInstance().getModContainer("openintel")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");

        searchField = new EditBox(
                net.minecraft.client.Minecraft.getInstance().font,
                0, 0, 134, 16, Component.literal("Search"));
        searchField.setHint(Component.literal("Search…"));
        searchField.setResponder(q -> {
            viewDirty = true;
            scrollOffset = 0;
            blurOptionFocus();
        });
    }

    // --------------------------------------------------------- layout -----

    @Override
    protected void init() {
        searchField.setX(MARGIN + 8);
        searchField.setY(MARGIN + 40);
        searchField.setWidth(SIDEBAR_W - 16);
        searchField.setHeight(16);
        addRenderableWidget(searchField);

        doneButton = addRenderableWidget(Button.builder(Component.literal("Done"),
                        b -> onClose())
                .bounds(width - 116, height - 30, 100, 20).build());

        // Every option widget is a real child — vanilla input gives us
        // drags, clicks and text focus. They start hidden; render() turns
        // visibility back on for the rows that intersect the pane clip.
        hideOptionWidgets();
        for (ClickGui.Category cat : categories) {
            for (ClickGui.Item item : cat.items()) {
                if (item instanceof ClickGui.Option opt) {
                    addRenderableWidget(opt.widget());
                    if (opt.aux() != null) addRenderableWidget(opt.aux());
                }
            }
        }
        viewDirty = true;
    }

    private int paneLeft()      { return PANE_X; }
    private int paneTop()       { return MARGIN; }
    private int paneRight()     { return width - MARGIN; }
    private int paneBottom()    { return height - PANE_BOTTOM_PAD; }
    private int contentTop()    { return paneTop() + INSET_TOP; }
    private int contentBottom() { return paneBottom() - INSET_BOTTOM; }
    private int viewportHeight() {
        return Math.max(0, contentBottom() - contentTop());
    }

    private void hideOptionWidgets() {
        for (ClickGui.Category cat : categories) {
            for (ClickGui.Item item : cat.items()) {
                if (item instanceof ClickGui.Option opt) {
                    opt.widget().visible = false;
                    if (opt.aux() != null) opt.aux().visible = false;
                }
            }
        }
    }

    // ----------------------------------------------------------- view -----

    private boolean searching() {
        return !searchField.getValue().isBlank();
    }

    /** Rebuilds the row list for the selected category — or, while the
     *  search box is non-blank, a flat cross-category result list headed
     *  by each matching option's category title. */
    private void refreshView() {
        viewDirty = false;
        List<ClickGui.Item> out = new ArrayList<>();
        if (searching()) {
            String q = searchField.getValue().trim().toLowerCase(Locale.ROOT);
            for (ClickGui.Category cat : categories) {
                boolean header = false;
                for (ClickGui.Item item : cat.items()) {
                    if (item instanceof ClickGui.Option opt && matches(opt, q)) {
                        if (!header) {
                            out.add(new ClickGui.Group(cat.title()));
                            header = true;
                        }
                        out.add(opt);
                    }
                }
            }
        } else {
            out.addAll(categories.get(selected).items());
        }
        rows = out;
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));
    }

    private static boolean matches(ClickGui.Option opt, String q) {
        return opt.label().toLowerCase(Locale.ROOT).contains(q)
                || (opt.tooltip() != null
                    && opt.tooltip().toLowerCase(Locale.ROOT).contains(q));
    }

    private int contentHeight() {
        int h = 0;
        for (ClickGui.Item item : rows) {
            h += item instanceof ClickGui.Group ? GROUP_H : OPTION_H;
        }
        return h;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - viewportHeight());
    }

    private void selectCategory(int idx) {
        if (idx == selected && !searching()) return;
        selected = idx;
        // A pick while searching means "jump to that category".
        if (searching()) searchField.setValue("");
        scrollOffset = 0;
        viewDirty = true;
        blurOptionFocus();
    }

    /** Keeps a hidden option widget (e.g. a scrolled-out text field) from
     *  silently holding keyboard focus. The search field keeps its own. */
    private void blurOptionFocus() {
        if (getFocused() != null && getFocused() != searchField) {
            setFocused(null);
        }
    }

    // --------------------------------------------------------- render -----

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // In-world screens must not call renderBackground — its blur pass can
        // only run once per frame. renderInGameBackground darkens without it.
        extractTransparentBackground(ctx);

        // Live dial preview behind the translucent UI.
        if ("radar".equals(categories.get(selected).id())
                && minecraft.level != null && minecraft.player != null) {
            RadarHud.render(ctx, delta);
        }

        if (viewDirty) refreshView();
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));

        renderSidebar(ctx, mouseX, mouseY, delta);
        ClickGui.Option hovered = renderPane(ctx, mouseX, mouseY, delta);

        if (doneButton != null) doneButton.extractRenderState(ctx, mouseX, mouseY, delta);

        // Tooltips ride above everything else.
        if (hovered != null && hovered.tooltip() != null) {
            ctx.setComponentTooltipForNextFrame(font,
                    List.of(Component.literal(hovered.tooltip())), mouseX, mouseY);
        }
    }

    private void renderSidebar(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                               float delta) {
        int sbL = MARGIN, sbT = MARGIN;
        int sbR = sbL + SIDEBAR_W, sbB = height - MARGIN;

        ctx.fill(sbL, sbT, sbR, sbB, SIDEBAR_BG);
        ctx.outline(sbL, sbT, SIDEBAR_W, sbB - sbT, PANEL_BORDER);

        // Mod title — scaled up for weight — over a dim version line.
        var pose = ctx.pose();
        pose.pushMatrix();
        pose.translate(sbL + 10, sbT + 8);
        pose.scale(1.4f, 1.4f);
        ctx.text(font, "OpenIntel", 0, 0, ACCENT, true);
        pose.popMatrix();
        ctx.text(font, "v" + version,
                sbL + 10, sbT + 26, TEXT_DIM, true);

        searchField.extractRenderState(ctx, mouseX, mouseY, delta);

        int listTop = sbT + 64;
        for (int i = 0; i < categories.size(); i++) {
            int rowTop = listTop + i * CAT_ROW_H;
            if (rowTop + CAT_ROW_H > sbB - 4) break;
            boolean sel = i == selected;
            boolean hov = mouseX >= sbL + 4 && mouseX < sbR - 4
                    && mouseY >= rowTop && mouseY < rowTop + CAT_ROW_H;
            if (sel) {
                ctx.fill(sbL + 4, rowTop, sbR - 4, rowTop + CAT_ROW_H,
                        CAT_SELECTED);
                ctx.fill(sbL + 4, rowTop, sbL + 6, rowTop + CAT_ROW_H, ACCENT);
            } else if (hov) {
                ctx.fill(sbL + 4, rowTop, sbR - 4, rowTop + CAT_ROW_H,
                        CAT_HOVER);
            }
            ctx.text(font, categories.get(i).title(),
                    sbL + 12, rowTop + (CAT_ROW_H - font.lineHeight) / 2,
                    sel ? TEXT_BRIGHT : TEXT_MAIN);
        }
    }

    /** Draws the options pane and returns the Option row under the mouse
     *  (for tooltip duty), or null. */
    private ClickGui.Option renderPane(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                                       float delta) {
        int pL = paneLeft(), pT = paneTop();
        int pR = paneRight(), pB = paneBottom();
        int cL = pL + INSET_X, cR = pR - INSET_X;

        ctx.fill(pL, pT, pR, pB, PANE_BG);

        // Visible rows re-show their widgets below; everything else stays
        // dark — visible=false is also the input gate.
        hideOptionWidgets();

        ClickGui.Option hovered = null;
        int y = contentTop() - (int) Math.round(scrollOffset);

        ctx.enableScissor(pL + 1, pT + 1, pR - 1, pB - 1);
        for (ClickGui.Item item : rows) {
            int rowH = item instanceof ClickGui.Group ? GROUP_H : OPTION_H;
            int rowTop = y;
            boolean intersects = rowTop + rowH > pT && rowTop < pB;

            if (item instanceof ClickGui.Group group) {
                if (intersects) {
                    ctx.text(font, group.title(),
                            cL + 4, rowTop + 6, ACCENT, true);
                    ctx.fill(cL + 4, rowTop + GROUP_H - 4, cR - 4,
                            rowTop + GROUP_H - 3, DIVIDER);
                }
            } else if (item instanceof ClickGui.Option opt) {
                int wy = rowTop + (OPTION_H - WIDGET_H) / 2;
                AbstractWidget widget = opt.widget();
                AbstractWidget aux = opt.aux();

                // Widgets sit in a fixed column near the label rather than
                // hugging the pane's right edge — clamped so narrow windows
                // still fit the whole control.
                int auxW = aux != null ? AUX_W + AUX_GAP : 0;
                int widgetLeft = Math.min(cL + WIDGET_COL, cR - auxW - WIDGET_W);
                widget.setX(widgetLeft);
                widget.setY(wy);
                widget.setWidth(WIDGET_W);
                widget.visible = intersects;
                if (aux != null) {
                    aux.setX(widgetLeft + WIDGET_W + AUX_GAP);
                    aux.setY(wy);
                    aux.setWidth(AUX_W);
                    aux.visible = intersects;
                }

                if (intersects) {
                    if (mouseX >= cL && mouseX < cR
                            && mouseY >= Math.max(rowTop, pT)
                            && mouseY < Math.min(rowTop + rowH, pB)) {
                        ctx.fill(cL, rowTop, cR, rowTop + rowH, ROW_HOVER);
                        hovered = opt;
                    }
                    ctx.text(font, opt.label(), cL + 4,
                            rowTop + (OPTION_H - font.lineHeight) / 2,
                            TEXT_MAIN, true);
                    widget.extractRenderState(ctx, mouseX, mouseY, delta);
                    if (aux != null) aux.extractRenderState(ctx, mouseX, mouseY, delta);
                }
            }
            y += rowH;
        }
        ctx.disableScissor();

        ctx.outline(pL, pT, pR - pL, pB - pT, PANEL_BORDER);

        // Slim accent thumb on the pane's inner right edge — only when the
        // content overflows the viewport.
        int contentH = contentHeight(), viewH = viewportHeight();
        if (contentH > viewH) {
            int thumbH = Math.max(14, viewH * viewH / contentH);
            int travel = viewH - thumbH;
            int thumbY = contentTop() + (maxScroll() <= 0 ? 0
                    : (int) Math.round(travel * scrollOffset / maxScroll()));
            ctx.fill(pR - 3, thumbY, pR - 1, thumbY + thumbH, ACCENT);
        }

        if (rows.isEmpty() && searching()) {
            ctx.centeredText(font, "No matching options",
                    (pL + pR) / 2, pT + 24, TEXT_DIM);
        }
        return hovered;
    }

    // ---------------------------------------------------------- input -----

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (listeningFor != null) {
            listeningFor.setKey(
                    InputConstants.Type.MOUSE.getOrCreate(click.button()));
            finishRebind();
            return true;
        }

        // Sidebar category rows.
        int sbL = MARGIN, sbR = MARGIN + SIDEBAR_W, sbB = height - MARGIN;
        int listTop = MARGIN + 64;
        if (click.x() >= sbL + 4 && click.x() < sbR - 4
                && click.y() >= listTop && click.y() < sbB - 4) {
            int idx = (int) (click.y() - listTop) / CAT_ROW_H;
            if (idx >= 0 && idx < categories.size()
                    && listTop + (idx + 1) * CAT_ROW_H <= sbB - 4) {
                selectCategory(idx);
                return true;
            }
        }

        // A click inside the pane's X span but outside its vertical clip
        // must not reach a widget peeking out from under the scissor.
        // render() re-shows legitimately visible widgets next frame.
        if (click.x() >= paneLeft() && click.x() < paneRight()
                && (click.y() < paneTop() || click.y() >= paneBottom())) {
            hideOptionWidgets();
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        if (mouseX >= paneLeft() && mouseX < paneRight()
                && mouseY >= paneTop() && mouseY < paneBottom()) {
            scrollOffset = Math.max(0, Math.min(maxScroll(),
                    scrollOffset - vertical * SCROLL_STEP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (listeningFor != null) {
            if (input.key() != GLFW.GLFW_KEY_ESCAPE) {
                listeningFor.setKey(InputConstants.getKey(input));
            }
            finishRebind();
            return true;
        }
        return super.keyPressed(input);
    }

    // --------------------------------------------------------- rebind -----

    @Override
    public void begin(KeyMapping kb) {
        listeningFor = kb;
        ClickGui.Option opt = findOption(kb);
        if (opt != null) {
            opt.widget().setMessage(Component.literal("> ")
                    .append(kb.getTranslatedKeyMessage())
                    .append(" <")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    @Override
    public void reset(KeyMapping kb) {
        kb.setKey(kb.getDefaultKey());
        commitRebind(kb);
    }

    private void finishRebind() {
        KeyMapping kb = listeningFor;
        listeningFor = null;
        if (kb != null) commitRebind(kb);
    }

    private void commitRebind(KeyMapping kb) {
        KeyMapping.resetMapping();
        if (minecraft != null) minecraft.options.save();
        ClickGui.Option opt = findOption(kb);
        if (opt != null) {
            opt.widget().setMessage(kb.getTranslatedKeyMessage());
        }
    }

    private ClickGui.Option findOption(KeyMapping kb) {
        for (ClickGui.Category cat : categories) {
            for (ClickGui.Item item : cat.items()) {
                if (item instanceof ClickGui.Option opt && opt.keybind() == kb) {
                    return opt;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------ lifecycle -----

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        super.removed();
        OpenIntelClient.config().save();
    }
}
