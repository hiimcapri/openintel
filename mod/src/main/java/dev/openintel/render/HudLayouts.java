package dev.openintel.render;

import dev.openintel.api.hud.HudSize;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.LinkedHashMap;

public final class HudLayouts {
    public enum Element { RADAR, RELAY, EVENTS, ARMOR, POTIONS }

    private static OIConfig dirty;

    private HudLayouts() { }

    public static HudSize size(Element element, MinecraftClient client, OIConfig config) {
        return switch (element) {
            case RADAR -> new HudSize(radius(config) * 2 + 6, radius(config) * 2 + 6);
            case RELAY -> PresenceHud.size(client, config);
            case EVENTS -> EventFeed.size(client);
            case ARMOR -> ArmorHud.size(client, config);
            case POTIONS -> PotionHud.size(client);
        };
    }

    public static int radius(OIConfig config) {
        return Math.clamp(config.radarSize, 1, 4096);
    }

    public static float scale(OIConfig config, int width, int height) {
        initialize(config, width, height);
        return HudLayout.scale(config.hudReferenceWidth, config.hudReferenceHeight, width, height);
    }

    private static void initialize(OIConfig config, int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (config.hudReferenceWidth <= 0 || config.hudReferenceHeight <= 0) {
            config.hudReferenceWidth = width;
            config.hudReferenceHeight = height;
            dirty = config;
        }
        if (config.hudAnchors == null) config.hudAnchors = new LinkedHashMap<>();
    }

    public static HudLayout.Frame bounds(Element element, MinecraftClient client, OIConfig config, int width, int height) {
        return place(element, config, size(element, client, config), width, height);
    }

    public static HudLayout.Frame place(Element element, OIConfig config, HudSize size, int width, int height) {
        if (width <= 0 || height <= 0) return new HudLayout.Frame(0, 0, 0, 0, 0);
        initialize(config, width, height);
        int x = x(element, config), y = y(element, config);
        var saved = config.hudAnchors.get(element.name());
        if (saved == null || saved.anchor() == null || !saved.anchor().valid()
                || saved.sourceX() != x || saved.sourceY() != y) {
            int captureW = saved == null ? config.hudReferenceWidth : width;
            int captureH = saved == null ? config.hudReferenceHeight : height;
            var frame = HudLayout.place(null, size.width(), size.height(), config.hudReferenceWidth,
                    config.hudReferenceHeight, captureW, captureH);
            saved = new OIConfig.HudAnchorState(x, y,
                    HudLayout.capture(x, y, frame.width(), frame.height(), captureW, captureH));
            config.hudAnchors.put(element.name(), saved);
            dirty = config;
        }
        return HudLayout.place(saved.anchor(), size.width(), size.height(), config.hudReferenceWidth,
                config.hudReferenceHeight, width, height);
    }

    public static void move(Element element, MinecraftClient client, OIConfig config,
                            double rawX, double rawY, int width, int height) {
        var frame = bounds(element, client, config, width, height);
        int x = HudLayout.snap(rawX, frame.width(), width, 10);
        int y = HudLayout.snap(rawY, frame.height(), height, 10);
        switch (element) {
            case RADAR -> { config.radarX = x; config.radarY = y; }
            case RELAY -> { config.presenceX = x; config.presenceY = y; }
            case EVENTS -> { config.eventFeedX = x; config.eventFeedY = y; }
            case ARMOR -> { config.armorHudX = x; config.armorHudY = y; }
            case POTIONS -> { config.potionHudX = x; config.potionHudY = y; }
        }
        config.hudAnchors.put(element.name(), new OIConfig.HudAnchorState(x, y,
                HudLayout.capture(x, y, frame.width(), frame.height(), width, height)));
        dirty = config;
    }

    private static int x(Element element, OIConfig c) {
        return switch (element) {
            case RADAR -> c.radarX;
            case RELAY -> c.presenceX;
            case EVENTS -> c.eventFeedX;
            case ARMOR -> c.armorHudX;
            case POTIONS -> c.potionHudX;
        };
    }

    private static int y(Element element, OIConfig c) {
        return switch (element) {
            case RADAR -> c.radarY;
            case RELAY -> c.presenceY;
            case EVENTS -> c.eventFeedY;
            case ARMOR -> c.armorHudY;
            case POTIONS -> c.potionHudY;
        };
    }

    public static void reset(OIConfig config, int width, int height) {
        if (config.hudAnchors == null) config.hudAnchors = new LinkedHashMap<>();
        else config.hudAnchors.clear();
        config.hudReferenceWidth = Math.max(1, width);
        config.hudReferenceHeight = Math.max(1, height);
        dirty = config;
    }

    public static void flush() {
        if (dirty == null) return;
        OIConfig config = dirty;
        dirty = null;
        config.save();
    }

    public static FrameScope apply(DrawContext context, HudLayout.Frame frame) {
        return new FrameScope(context, frame);
    }

    public static final class FrameScope implements AutoCloseable {
        private final DrawContext context;

        private FrameScope(DrawContext context, HudLayout.Frame frame) {
            this.context = context;
            context.getMatrices().pushMatrix();
            context.enableScissor(frame.x(), frame.y(), frame.right(), frame.bottom());
            context.getMatrices().translate((float) frame.x(), (float) frame.y());
            context.getMatrices().scale(frame.scale(), frame.scale());
        }

        @Override
        public void close() {
            try {
                context.disableScissor();
            } finally {
                context.getMatrices().popMatrix();
            }
        }
    }
}
