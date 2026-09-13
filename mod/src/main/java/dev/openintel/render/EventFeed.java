package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

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

    private record Entry(String text, int color, long createdAt) { }

    private static final Deque<Entry> entries = new ArrayDeque<>();

    /** Players currently in render distance, so ENTITY_LOAD doesn't re-fire on chunk refresh. */
    private static final Set<String> inRender = new HashSet<>();
    /** Friendly corpses we've already announced — cleared when they respawn. */
    private static final Set<String> deadNotified = new HashSet<>();

    public static void add(String text, int argb) {
        OIConfig cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.eventFeedEnabled) return;
        while (entries.size() >= MAX_ENTRIES) entries.pollFirst();
        entries.addLast(new Entry(text, argb, System.currentTimeMillis()));
    }

    public static void clear() {
        entries.clear();
        inRender.clear();
        deadNotified.clear();
    }

    // ------------------------------------------------------------ hooks ----

    /** Fabric ENTITY_LOAD — announces enemy/focus players entering render. */
    public static void onEntityLoad(Entity entity, net.minecraft.client.world.ClientWorld world) {
        if (!(entity instanceof AbstractClientPlayerEntity p)) return;
        String name = p.getGameProfile().name();
        if (!inRender.add(name)) return;

        Allegiance a = OpenIntelClient.allegiances().of(name);
        if (a == Allegiance.ENEMY || a == Allegiance.FOCUS) {
            add("⚠ " + name + " entered render distance", a.argb);
        }
    }

    public static void onEntityUnload(Entity entity, net.minecraft.client.world.ClientWorld world) {
        if (entity instanceof AbstractClientPlayerEntity p) {
            inRender.remove(p.getGameProfile().name());
        }
    }

    /** Client tick — watches rendered teammates for deaths. */
    public static void tick(MinecraftClient client) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.eventFeedEnabled || client.world == null) return;

        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
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

    public static void render(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.eventFeedEnabled || entries.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;

        long now = System.currentTimeMillis();
        long holdMs = cfg.eventFeedSeconds * 1000L;
        entries.removeIf(e -> now - e.createdAt > holdMs + FADE_MS);
        if (entries.isEmpty()) return;

        int w = ctx.getScaledWindowWidth();
        int y = 4;
        for (Entry e : entries) {
            long age = now - e.createdAt;
            float fade = age <= holdMs ? 1f : 1f - (age - holdMs) / (float) FADE_MS;
            int color = scaleAlpha(e.color, fade);

            int x = w - 4 - client.textRenderer.getWidth(e.text);
            ctx.drawText(client.textRenderer, e.text, x, y, color, true);
            y += client.textRenderer.fontHeight + 2;
        }
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.max(0, Math.round(((argb >>> 24) & 0xFF) * f)));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
