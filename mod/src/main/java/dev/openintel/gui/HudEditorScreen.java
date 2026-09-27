package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.api.hud.HudApi;
import dev.openintel.api.hud.HudElementDescriptor;
import dev.openintel.api.hud.HudPosition;
import dev.openintel.config.OIConfig;
import dev.openintel.render.HudLayout;
import dev.openintel.render.HudLayouts;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public class HudEditorScreen extends Screen {
    private static final int SNAP = 10;
    private final Screen parent;
    private Element dragging;
    private Identifier selectedExternalId;
    private Identifier draggingExternalId;
    private double grabX;
    private double grabY;

    private enum Element {
        RELAY, RADAR, EVENTS, ARMOR, POTIONS, TOP, BOTTOM, LEFT, RIGHT
    }

    private record Box(Element element, String label, int x, int y, int w, int h, int color) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record ExternalBox(HudElementDescriptor descriptor, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public HudEditorScreen(Screen parent) {
        super(Text.literal("OpenIntel HUD Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        finishDrag();
        if (width <= 0 || height <= 0) return;
        HudLayouts.scale(OpenIntelClient.config(), width, height);
        int buttonWidth = Math.min(100, Math.max(1, (width - 12) / 2));
        int buttonHeight = Math.min(20, Math.max(1, height - 8));
        int y = Math.max(0, height - buttonHeight - 4);
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset layout"), b -> reset())
                .dimensions(Math.max(0, width / 2 - buttonWidth - 4), y, buttonWidth, buttonHeight).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(Math.min(width - buttonWidth, width / 2 + 4), y, buttonWidth, buttonHeight).build());
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0 && click.button() != 1) return false;
        finishDrag();
        List<ExternalBox> external = externalBoxes();
        for (int i = external.size() - 1; i >= 0; i--) {
            ExternalBox box = external.get(i);
            if (!box.contains(click.x(), click.y())) continue;
            selectedExternalId = box.descriptor.id();
            if (click.button() == 1) {
                try {
                    HudApi.getInstance().setEnabled(selectedExternalId, !box.descriptor.enabled());
                } catch (IllegalArgumentException ignored) {
                    selectedExternalId = null;
                }
            } else {
                draggingExternalId = selectedExternalId;
                grabX = click.x() - box.x;
                grabY = click.y() - box.y;
            }
            return true;
        }
        selectedExternalId = null;
        if (click.button() != 0) return false;
        List<Box> boxes = boxes();
        for (int i = boxes.size() - 1; i >= 0; i--) {
            Box box = boxes.get(i);
            if (!box.contains(click.x(), click.y())) continue;
            dragging = box.element;
            grabX = click.x() - box.x;
            grabY = click.y() - box.y;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (click.button() != 0) return super.mouseDragged(click, offsetX, offsetY);
        if (draggingExternalId != null) {
            HudElementDescriptor descriptor = HudApi.getInstance().element(draggingExternalId).orElse(null);
            if (descriptor == null) {
                finishDrag();
                selectedExternalId = null;
                return true;
            }
            var frame = HudLayout.pixels(descriptor.position().x(), descriptor.position().y(),
                    descriptor.size().width(), descriptor.size().height(), width, height);
            HudPosition position = new HudPosition(
                    snappedExternal(click.x() - grabX, frame.width(), width),
                    snappedExternal(click.y() - grabY, frame.height(), height));
            try {
                HudApi.getInstance().setPosition(draggingExternalId, position, false);
            } catch (IllegalArgumentException ignored) {
                finishDrag();
                selectedExternalId = null;
            }
            return true;
        }
        if (dragging == null) return super.mouseDragged(click, offsetX, offsetY);
        move(dragging, click.x() - grabX, click.y() - grabY);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0 && (dragging != null || draggingExternalId != null)) {
            finishDrag();
            return true;
        }
        return super.mouseReleased(click);
    }

    private void finishDrag() {
        if (dragging == null && draggingExternalId == null) return;
        dragging = null;
        draggingExternalId = null;
        HudApi.getInstance().saveLayout();
    }

    @Override
    public void close() {
        finishDrag();
        HudApi.getInstance().saveLayout();
        client.setScreen(parent);
    }

    @Override
    public void removed() {
        finishDrag();
        super.removed();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderInGameBackground(ctx);
        if (height >= textRenderer.fontHeight + 8) {
            ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(title.getString(), Math.max(0, width - 8)),
                    width / 2, 4, 0xFFFFFFFF);
        }
        if (height >= textRenderer.fontHeight * 2 + 12) {
            String help = "Drag to move; right-click external HUDs to toggle. Markers stay on their edge.";
            ctx.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(help, Math.max(0, width - 8)),
                    width / 2, textRenderer.fontHeight + 7, 0xFFAAAAAA);
        }

        ctx.fill(width / 2, 0, width / 2 + 1, height, 0x35FFFFFF);
        ctx.fill(0, height / 2, width, height / 2 + 1, 0x35FFFFFF);

        for (Box box : boxes()) drawBox(ctx, box, mouseX, mouseY);
        for (ExternalBox box : externalBoxes()) drawExternalBox(ctx, box, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBox(DrawContext ctx, Box box, int mouseX, int mouseY) {
        boolean active = box.element == dragging || box.contains(mouseX, mouseY);
        int bg = active ? 0xD02B3545 : 0xB018202C;
        ctx.fill(box.x, box.y, box.x + box.w, box.y + box.h, bg);
        ctx.drawStrokedRectangle(box.x, box.y, box.w, box.h, active ? 0xFFFFFFFF : box.color);
        ctx.enableScissor(box.x, box.y, box.x + box.w, box.y + box.h);
        try {
            drawBoxLabel(ctx, box);
        } finally {
            ctx.disableScissor();
        }
    }

    /** Single line when it fits; one word per line inside narrow boxes. */
    private void drawBoxLabel(DrawContext ctx, Box box) {
        String[] words = textRenderer.getWidth(box.label) <= box.w - 4 || !box.label.contains(" ")
                ? new String[]{box.label} : box.label.split(" ");
        int blockW = 1;
        for (String word : words) blockW = Math.max(blockW, textRenderer.getWidth(word));
        int blockH = words.length * (textRenderer.fontHeight + 2) - 2;
        float scale = Math.min(1f, Math.min(Math.max(1, box.w - 4) / (float) blockW,
                Math.max(1, box.h - 4) / (float) blockH));
        var pose = ctx.getMatrices();
        pose.pushMatrix();
        pose.translate(box.x + box.w / 2f, box.y + (box.h - blockH * scale) / 2f);
        pose.scale(scale, scale);
        int ty = 0;
        for (String word : words) {
            ctx.drawCenteredTextWithShadow(textRenderer, word, 0, ty, box.color);
            ty += textRenderer.fontHeight + 2;
        }
        pose.popMatrix();
    }

    private List<ExternalBox> externalBoxes() {
        List<ExternalBox> boxes = new ArrayList<>();
        if (width <= 0 || height <= 0) return boxes;
        for (HudElementDescriptor descriptor : HudApi.getInstance().elements()) {
            var frame = HudLayout.pixels(descriptor.position().x(), descriptor.position().y(),
                    descriptor.size().width(), descriptor.size().height(), width, height);
            boxes.add(new ExternalBox(descriptor, frame.x(), frame.y(), frame.width(), frame.height()));
        }
        return boxes;
    }

    private void drawExternalBox(DrawContext ctx, ExternalBox box, int mouseX, int mouseY, float delta) {
        HudElementDescriptor descriptor = box.descriptor;
        boolean active = descriptor.id().equals(selectedExternalId) || box.contains(mouseX, mouseY);
        int color = descriptor.runtimeFailed() ? 0xFFFF5555 : descriptor.enabled() ? 0xFF55FFFF : 0xFF888888;
        ctx.fill(box.x, box.y, box.x + box.w, box.y + box.h, active ? 0xD02B3545 : 0xB018202C);
        HudApi.getInstance().renderPreview(descriptor.id(), ctx, width, height, delta);
        ctx.drawStrokedRectangle(box.x, box.y, box.w, box.h, active ? 0xFFFFFFFF : color);
        String label = descriptor.name() + (descriptor.runtimeFailed() ? " [error: reset]" : descriptor.enabled() ? "" : " [off]");
        ctx.enableScissor(box.x, box.y, box.x + box.w, box.y + box.h);
        try {
            ctx.drawTextWithShadow(textRenderer, label, box.x + 3, box.y + 3, color);
        } finally {
            ctx.disableScissor();
        }
        if (box.contains(mouseX, mouseY)) {
            ctx.drawTooltip(textRenderer, List.of(Text.literal(label), Text.literal(descriptor.id().toString()),
                    Text.literal("Left-drag to move; right-click to toggle")), mouseX, mouseY);
        }
    }

    private static int snappedExternal(double raw, int size, int viewport) {
        return HudLayout.snap(raw, size, viewport, SNAP);
    }

    private List<Box> boxes() {
        OIConfig c = OpenIntelClient.config();
        List<Box> boxes = new ArrayList<>();
        if (width <= 0 || height <= 0) return boxes;
        addBuiltin(boxes, Element.RADAR, "Radar", 0xFF55FFFF);
        addBuiltin(boxes, Element.RELAY, "Relay roster", 0xFF55FF55);
        addBuiltin(boxes, Element.EVENTS, "Event feed", 0xFFFFAA00);
        addBuiltin(boxes, Element.ARMOR, "Armor HUD", 0xFF55AAFF);
        addBuiltin(boxes, Element.POTIONS, "Potion effects", 0xFFAA55FF);
        float scale = HudLayouts.scale(c, width, height);
        addEdge(boxes, Element.TOP, "Top markers", 74, c.edgeTopXPct, c.edgeRowInset, scale);
        addEdge(boxes, Element.BOTTOM, "Bottom markers", 88, c.edgeBottomXPct, c.edgeRowInset, scale);
        addEdge(boxes, Element.LEFT, "Left", 48, c.edgeLeftYPct, c.edgeColumnInset, scale);
        addEdge(boxes, Element.RIGHT, "Right", 52, c.edgeRightYPct, c.edgeColumnInset, scale);
        return boxes;
    }

    private void addBuiltin(List<Box> boxes, Element element, String label, int color) {
        var frame = HudLayouts.bounds(HudLayouts.Element.valueOf(element.name()), client,
                OpenIntelClient.config(), width, height);
        boxes.add(new Box(element, label, frame.x(), frame.y(), frame.width(), frame.height(), color));
    }

    private void addEdge(List<Box> boxes, Element element, String label, int nominalWidth, int pct, int inset, float scale) {
        int w = Math.min(width, Math.max(1, (int) Math.ceil(nominalWidth * scale)));
        int h = Math.min(height, Math.max(1, (int) Math.ceil(18 * scale)));
        boolean horizontal = element == Element.TOP || element == Element.BOTTOM;
        int x = horizontal ? Math.round(width * pct / 100f - w / 2f)
                : element == Element.LEFT ? Math.round((inset - 5) * scale) : Math.round(width - (inset - 5) * scale - w);
        int y = !horizontal ? Math.round(height * pct / 100f - h / 2f)
                : element == Element.TOP ? Math.round((inset - 5) * scale) : Math.round(height - (inset - 5) * scale - h);
        boxes.add(new Box(element, label, Math.clamp(x, 0, width - w), Math.clamp(y, 0, height - h), w, h, 0xFFFF5555));
    }

    private void move(Element element, double rawX, double rawY) {
        OIConfig c = OpenIntelClient.config();
        if (element.ordinal() <= Element.POTIONS.ordinal()) {
            HudLayouts.move(HudLayouts.Element.valueOf(element.name()), client, c, rawX, rawY, width, height);
            return;
        }
        Box box = boxes().stream().filter(b -> b.element == element).findFirst().orElseThrow();
        int x = HudLayout.snap(rawX, box.w, width, SNAP);
        int y = HudLayout.snap(rawY, box.h, height, SNAP);
        switch (element) {
            case TOP -> c.edgeTopXPct = percent(x + box.w / 2.0, width);
            case BOTTOM -> c.edgeBottomXPct = percent(x + box.w / 2.0, width);
            case LEFT -> c.edgeLeftYPct = percent(y + box.h / 2.0, height);
            case RIGHT -> c.edgeRightYPct = percent(y + box.h / 2.0, height);
            default -> { }
        }
    }

    private static int percent(double value, int total) {
        return Math.clamp((int) Math.round(value * 100 / Math.max(1, total)), 0, 100);
    }

    private void reset() {
        dragging = null;
        draggingExternalId = null;
        selectedExternalId = null;
        OIConfig c = OpenIntelClient.config();
        c.radarX = 8;
        c.radarY = 8;
        c.presenceX = -1;
        c.presenceY = 120;
        c.eventFeedX = -1;
        c.eventFeedY = 4;
        c.armorHudX = 8;
        c.armorHudY = 116;
        c.potionHudX = -1;
        c.potionHudY = 40;
        c.edgeTopXPct = 50;
        c.edgeBottomXPct = 50;
        c.edgeLeftYPct = 50;
        c.edgeRightYPct = 50;
        c.edgeRowInset = 5;
        c.edgeColumnInset = 5;
        HudLayouts.reset(c, width, height);
        HudApi.getInstance().resetAll();
    }
}
