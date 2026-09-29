package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.api.hud.HudSize;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.config.OIConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Deque;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Compact intel feed in the top-right corner: deaths, disconnects,
 * enemies walking into render, relay joins/leaves, pings, snitch hits.
 *
 * Entries hold for `eventFeedSeconds`, then fade over 1.5s. A hard cap
 * keeps a busy fight from flooding the screen. The static hooks below are
 * wired from OpenIntelClient; renderers never call them directly.
 */
public final class EventFeed {
    private EventFeed() { }

    private static final int MAX_ENTRIES = 8;
    private static final long FADE_MS = 1500;

    private record Entry(String text, int color, long createdAt, boolean relay) { }

    private static final Deque<Entry> entries = new ConcurrentLinkedDeque<>();

    /** Players currently in render distance, so ENTITY_LOAD doesn't re-fire on chunk refresh. */
    private static final Set<String> inRender = new HashSet<>();
    /** Friendly corpses we've already announced — cleared when they respawn. */
    private static final Set<String> deadNotified = new HashSet<>();

    public static void add(String text, int argb) {
        add(text, argb, false);
    }

    public static void addRelay(String text, int argb) {
        add(text, argb, true);
    }

    private static void add(String text, int argb, boolean relay) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            var world = client.level;
            client.execute(() -> { if (client.level == world) add(text, argb, relay); });
            return;
        }
        OIConfig cfg = OpenIntelClient.config();
        if (cfg != null && cfg.eventFeedEnabled) {
            while (entries.size() >= MAX_ENTRIES) entries.pollFirst();
            entries.addLast(new Entry(text, argb, System.currentTimeMillis(), relay));
        }
        ApiBridge.notification(text, argb, "feed");
    }

    public static void clear() {
        clear(true);
    }

    public static void clearRelay() {
        clear(false);
    }

    private static void clear(boolean includeLocal) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> clear(includeLocal));
            return;
        }
        clearData(includeLocal);
        if (entries.isEmpty()) ApiBridge.notificationsCleared();
    }

    private static void clearData(boolean includeLocal) {
        if (includeLocal) {
            entries.clear();
            inRender.clear();
            deadNotified.clear();
        } else {
            entries.removeIf(Entry::relay);
        }
    }

    // ------------------------------------------------------------ hooks ----

    /** Fabric ENTITY_LOAD — announces enemy/focus players entering render. */
    public static void onEntityLoad(Entity entity, net.minecraft.client.multiplayer.ClientLevel world) {
        if (!(entity instanceof AbstractClientPlayer p)) return;
        String name = p.getGameProfile().name();
        if (!inRender.add(name)) return;

        Allegiance a = OpenIntelClient.allegiances().of(name);
        if (a == Allegiance.ENEMY || a == Allegiance.FOCUS) {
            add("⚠ " + name + " entered render distance", a.argb);
        }
    }

    public static void onEntityUnload(Entity entity, net.minecraft.client.multiplayer.ClientLevel world) {
        if (entity instanceof AbstractClientPlayer p) {
            inRender.remove(p.getGameProfile().name());
        }
    }

    /** Client tick — watches rendered teammates for deaths. */
    public static void tick(Minecraft client) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.eventFeedEnabled || client.level == null) return;

        for (AbstractClientPlayer p : client.level.players()) {
            if (p == client.player) continue;
            String name = p.getGameProfile().name();
            Allegiance a = OpenIntelClient.allegiances().of(name);
            boolean friendly = a == Allegiance.FRIEND || a == Allegiance.ALLY
                    || a == Allegiance.FOCUS;
            if (!friendly) {
                deadNotified.remove(name);
                continue;
            }
            if (!p.isAlive()) {
                if (deadNotified.add(name)) {
                    add("✝ " + name + " died", 0xFFFF5555);
                }
            } else {
                deadNotified.remove(name);
            }
        }
    }

    // ------------------------------------------------------------ render ---

    public static void render(GuiGraphicsExtractor ctx) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.eventFeedEnabled || entries.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gui.hud.isHidden()) return;

        long now = System.currentTimeMillis();
        long holdMs = cfg.eventFeedSeconds * 1000L;
        entries.removeIf(e -> now - e.createdAt > holdMs + FADE_MS);
        if (entries.isEmpty()) return;

        List<Entry> visible = List.copyOf(entries);
        HudSize size = measure(client, visible);
        var frame = HudLayouts.place(HudLayouts.Element.EVENTS, cfg, size,
                ctx.guiWidth(), ctx.guiHeight());
        if (frame.scale() <= 0) return;
        try (var ignored = HudLayouts.apply(ctx, frame)) {
            int y = 0;
            boolean cf = CleanFont.active();
            for (Entry e : visible) {
                long age = now - e.createdAt;
                float fade = age <= holdMs ? 1f : 1f - (age - holdMs) / (float) FADE_MS;
                int color = scaleAlpha(e.color, fade);
                float tw = cf ? CleanFont.width(e.text) : client.font.width(e.text);
                int x = cfg.eventFeedX >= 0 ? 0 : Math.max(0, Math.round(size.width() - tw));
                if (cf) CleanFont.draw(ctx, e.text, x, y, color, true);
                else ctx.text(client.font, e.text, x, y, color, true);
                y += client.font.lineHeight + 2;
            }
        }
    }

    public static HudSize size(Minecraft client) {
        return measure(client, List.copyOf(entries));
    }

    private static HudSize measure(Minecraft client, List<Entry> visible) {
        if (visible.isEmpty()) return new HudSize(180, 58);
        boolean cf = CleanFont.active();
        int width = 1;
        for (Entry entry : visible) {
            float w = cf ? CleanFont.width(entry.text) : client.font.width(entry.text);
            width = Math.max(width, (int) Math.ceil(w));
        }
        return new HudSize(Math.min(32768, width), Math.max(1, visible.size() * (client.font.lineHeight + 2)));
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.max(0, Math.round(((argb >>> 24) & 0xFF) * f)));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
