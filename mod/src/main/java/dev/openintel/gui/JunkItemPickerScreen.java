package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Item-icon picker for the junk-drop list — click a tile to toggle it. */
public class JunkItemPickerScreen extends Screen {

    private static final int CELL = 20;

    private final Screen parent;
    private final Set<String> junk;                 // namespaced ids
    private EditBox search;
    private List<Item> view = new ArrayList<>();
    private int gridX, gridY, gridW, gridH;
    private int scroll;                             // px offset into the grid

    public JunkItemPickerScreen(Screen parent) {
        super(Component.literal("Junk items"));
        this.parent = parent;
        junk = new LinkedHashSet<>();
        for (String s : OpenIntelClient.config().junkItems) {
            String id = s.trim();
            if (!id.isEmpty()) junk.add(id.contains(":") ? id : "minecraft:" + id);
        }
    }

    private static String idOf(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    @Override
    protected void init() {
        search = new EditBox(font, (width - 180) / 2, 22, 180, 16,
                Component.literal("search"));
        search.setHint(Component.literal("search items…"));
        search.setResponder(q -> refilter());
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
        gridW = Math.max(CELL, Math.min(CELL * 14, width - 48) / CELL * CELL);
        gridX = (width - gridW) / 2;
        gridY = 48;
        gridH = height - gridY - 36;
        refilter();
    }

    private void refilter() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        view = new ArrayList<>();
        for (Item it : BuiltInRegistries.ITEM) {
            String id = idOf(it);
            if (!q.isEmpty() && !id.contains(q)
                    && !new ItemStack(it).getHoverName().getString()
                            .toLowerCase(Locale.ROOT).contains(q))
                continue;
            view.add(it);
        }
        // selected items float to the top so current junk is always visible
        view.sort(Comparator.comparing((Item it) -> !junk.contains(idOf(it)))
                .thenComparing(JunkItemPickerScreen::idOf));
        scroll = Math.min(scroll, maxScroll());
    }

    private int cols() { return gridW / CELL; }

    private int maxScroll() {
        int rows = (view.size() + cols() - 1) / cols();
        return Math.max(0, rows * CELL - gridH);
    }

    private void toggle(Item it) {
        String id = idOf(it);
        if (!junk.remove(id)) junk.add(id);
        OpenIntelClient.config().junkItems = new ArrayList<>(junk);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0101015);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font, "Junk items — click to toggle",
                width / 2, 8, 0xFFFFFFFF);

        int cols = cols();
        ctx.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);
        ItemStack hovered = null;
        for (int i = 0; i < view.size(); i++) {
            int x = gridX + (i % cols) * CELL;
            int y = gridY + (i / cols) * CELL - scroll;
            if (y + CELL < gridY || y > gridY + gridH) continue;
            boolean sel = junk.contains(idOf(view.get(i)));
            boolean hov = mouseX >= x && mouseX < x + CELL
                    && mouseY >= y && mouseY < y + CELL
                    && mouseY >= gridY && mouseY < gridY + gridH;
            ctx.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1,
                    sel ? 0x8834A800 : (hov ? 0x33FFFFFF : 0x22000000));
            if (sel)
                ctx.outline(x + 1, y + 1, CELL - 2, CELL - 2, 0xFF64FF64);
            ItemStack st = new ItemStack(view.get(i));
            ctx.item(st, x + 2, y + 2);
            if (hov) hovered = st;
        }
        ctx.disableScissor();

        int max = maxScroll();
        if (max > 0) {
            int track = gridX + gridW + 3;
            ctx.fill(track, gridY, track + 3, gridY + gridH, 0x33000000);
            int h = Math.max(10, gridH * gridH / (gridH + max));
            int y = gridY + (gridH - h) * scroll / max;
            ctx.fill(track, y, track + 3, y + h, 0x88AAAAAA);
        }
        ctx.centeredText(font, junk.size() + " selected",
                width / 2, height - 46, 0xFFAAAAAA);
        if (hovered != null)
            ctx.setTooltipForNextFrame(font, hovered, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            if (mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH) {
                int c = (int) (mx - gridX) / CELL;
                int r = (int) (my - gridY + scroll) / CELL;
                int idx = r * cols() + c;
                if (c < cols() && idx >= 0 && idx < view.size()) {
                    toggle(view.get(idx));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        if (mouseX >= gridX && mouseX < gridX + gridW
                && mouseY >= gridY && mouseY < gridY + gridH) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) vertical * CELL));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void onClose() {
        OpenIntelClient.config().save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
