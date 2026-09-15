package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.api.hud.HudApi;
import dev.openintel.api.hud.HudElementDescriptor;
import dev.openintel.api.hud.HudPosition;
import dev.openintel.config.OIConfig;
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
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset layout"), b -> reset())
                .dimensions(width / 2 - 104, height - 26, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 + 4, height - 26, 100, 20).build());
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
            HudPosition position = new HudPosition(
                    snappedExternal(click.x() - grabX, descriptor.size().width(), width),
                    snappedExternal(click.y() - grabY, descriptor.size().height(), height));
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
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Drag to move; right-click external HUDs to toggle. Markers stay on their edge."),
                width / 2, 21, 0xFFAAAAAA);

        ctx.fill(width / 2, 34, width / 2 + 1, height - 34, 0x35FFFFFF);
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
        ctx.drawCenteredTextWithShadow(textRenderer, box.label,
                box.x + box.w / 2, box.y + (box.h - textRenderer.fontHeight) / 2, box.color);
    }

    private List<ExternalBox> externalBoxes() {
        List<ExternalBox> boxes = new ArrayList<>();
        if (width <= 0 || height <= 0) return boxes;
        for (HudElementDescriptor descriptor : HudApi.getInstance().elements()) {
            HudPosition position = descriptor.position().clamp(descriptor.size(), width, height);
            boxes.add(new ExternalBox(descriptor, position.x(), position.y(),
                    Math.min(descriptor.size().width(), width), Math.min(descriptor.size().height(), height)));
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
        int edge = Math.max(0, viewport - size);
        int value = (int) Math.max(0, Math.min(edge, Math.round(raw)));
        if (value <= SNAP) return 0;
        if (edge - value <= SNAP) return edge;
        int center = edge / 2;
        if (Math.abs(value - center) <= SNAP) return center;
        return value;
    }

    private List<Box> boxes() {
        OIConfig c = OpenIntelClient.config();
        List<Box> boxes = new ArrayList<>();
        int radarD = c.radarSize * 2 + 6;
        boxes.add(new Box(Element.RADAR, "Radar", clampX(c.radarX, radarD), clampY(c.radarY, radarD), radarD, radarD, 0xFF55FFFF));
        boxes.add(new Box(Element.RELAY, "Relay roster", resolveX(c.presenceX, 132), clampY(c.presenceY, 58), 132, 58, 0xFF55FF55));
        boxes.add(new Box(Element.EVENTS, "Event feed", resolveX(c.eventFeedX, 180), clampY(c.eventFeedY, 58), 180, 58, 0xFFFFAA00));
        boxes.add(new Box(Element.ARMOR, "Armor HUD", resolveX(c.armorHudX, 80), clampY(c.armorHudY, 24), 80, 24, 0xFF55AAFF));
        boxes.add(new Box(Element.POTIONS, "Potion effects", resolveX(c.potionHudX, 140), clampY(c.potionHudY, 48), 140, 48, 0xFFAA55FF));
        boxes.add(new Box(Element.TOP, "Top markers", pctX(c.edgeTopXPct, 74), c.edgeRowInset, 74, 18, 0xFFFF5555));
        boxes.add(new Box(Element.BOTTOM, "Bottom markers", pctX(c.edgeBottomXPct, 88), height - c.edgeRowInset - 18, 88, 18, 0xFFFF5555));
        boxes.add(new Box(Element.LEFT, "Left", c.edgeColumnInset, pctY(c.edgeLeftYPct, 18), 48, 18, 0xFFFF5555));
        boxes.add(new Box(Element.RIGHT, "Right", width - c.edgeColumnInset - 52, pctY(c.edgeRightYPct, 18), 52, 18, 0xFFFF5555));
        return boxes;
    }

    private void move(Element element, double rawX, double rawY) {
        OIConfig c = OpenIntelClient.config();
        switch (element) {
            case RADAR -> {
                int d = c.radarSize * 2 + 6;
                c.radarX = snappedX(rawX, d);
                c.radarY = snappedY(rawY, d);
            }
            case RELAY -> {
                c.presenceX = snappedX(rawX, 132);
                c.presenceY = snappedY(rawY, 58);
            }
            case EVENTS -> {
                c.eventFeedX = snappedX(rawX, 180);
                c.eventFeedY = snappedY(rawY, 58);
            }
            case ARMOR -> {
                c.armorHudX = snappedX(rawX, 80);
                c.armorHudY = snappedY(rawY, 24);
            }
            case POTIONS -> {
                c.potionHudX = snappedX(rawX, 140);
                c.potionHudY = snappedY(rawY, 48);
            }
            case TOP -> c.edgeTopXPct = percent(rawX + 37, width);
            case BOTTOM -> c.edgeBottomXPct = percent(rawX + 44, width);
            case LEFT -> c.edgeLeftYPct = percent(rawY + 9, height);
            case RIGHT -> c.edgeRightYPct = percent(rawY + 9, height);
        }
    }

    private int snappedX(double x, int w) {
        int right = Math.max(4, width - w - 4);
        int value = Math.max(4, Math.min(right, (int) Math.round(x)));
        if (value <= SNAP) return 4;
        if (right - value <= SNAP) return right;
        if (Math.abs(value + w / 2 - width / 2) <= SNAP) return width / 2 - w / 2;
        return value;
    }

    private int snappedY(double y, int h) {
        int bottom = Math.max(34, height - h - 32);
        int value = Math.max(34, Math.min(bottom, (int) Math.round(y)));
        if (value - 34 <= SNAP) return 34;
        if (bottom - value <= SNAP) return bottom;
        if (Math.abs(value + h / 2 - height / 2) <= SNAP) return height / 2 - h / 2;
        return value;
    }

    private int resolveX(int x, int w) {
        return clampX(x >= 0 ? x : width + x - w, w);
    }

    private int clampX(int x, int w) {
        return Math.max(4, Math.min(Math.max(4, width - w - 4), x));
    }

    private int clampY(int y, int h) {
        return Math.max(34, Math.min(Math.max(34, height - h - 32), y));
    }

    private int pctX(int pct, int w) {
        return clampX(width * pct / 100 - w / 2, w);
    }

    private int pctY(int pct, int h) {
        return clampY(height * pct / 100 - h / 2, h);
    }

    private static int percent(double value, int total) {
        return Math.max(3, Math.min(97, (int) Math.round(value * 100 / total)));
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
        HudApi.getInstance().resetAll();
    }
}
