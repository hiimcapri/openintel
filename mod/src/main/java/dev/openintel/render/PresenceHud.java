package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.api.hud.HudSize;
import dev.openintel.config.OIConfig;
import dev.openintel.tracker.Tracker.RemotePlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Side-panel roster of everyone the relay is tracking: name in allegiance
 * color, dimension tag, distance, and a freshness dot that walks
 * green → yellow → red as the intel ages. Same-dimension players sort
 * first by distance; other dimensions sink to the bottom.
 *
 * Toggle + layout live in /oi settings. Respects the master relay-rendering
 * switch — it's a relay-derived view.
 */
public final class PresenceHud {
    private PresenceHud() { }

    private static final int BG = 0x73090D14;
    private static final int RIM = 0x80FFFFFF;

    private record Row(String name, int color, String dim, double dist,
                       float age, float alpha, boolean sameDim) { }

    public static HudSize size(MinecraftClient mc, OIConfig cfg) {
        return measure(mc, rows(mc, cfg));
    }

    public static void render(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.presenceEnabled || !cfg.relayRendering) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        List<Row> rows = rows(mc, cfg);
        if (rows.isEmpty()) return;
        HudSize size = measure(mc, rows);
        var frame = HudLayouts.place(HudLayouts.Element.RELAY, cfg, size,
                ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        if (frame.scale() <= 0) return;
        try (var ignored = HudLayouts.apply(ctx, frame)) {
            drawPanel(ctx, mc, cfg, rows, size);
        }
    }

    private static List<Row> rows(MinecraftClient mc, OIConfig cfg) {
        if (mc.player == null || mc.world == null || OpenIntelClient.tracker() == null) return List.of();
        String myDim = mc.world.getRegistryKey().getValue().toString();
        Vec3d self = mc.player.getEntityPos();
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        for (RemotePlayer p : OpenIntelClient.tracker().all()) {
            if (p.dimension == null) continue;
            boolean same = p.dimension.equals(myDim);
            if (!same && !cfg.presenceShowAllDims) continue;
            double dist = same ? Math.hypot(self.x - p.x, self.z - p.z) : Double.MAX_VALUE;
            int color = p.allegiance != null ? p.allegiance.argb : 0xFFAAAAAA;
            // Fade with intel age, but never below ~25% — expired entries
            // are removed by Tracker anyway.
            float age = Math.min(1f, (now - p.lastSeen) / (float) Math.max(1, cfg.staleAfterMs));
            float alpha = cfg.staleDecay ? 1f - 0.75f * age : 1f;
            rows.add(new Row(p.name, color, dimShort(p.dimension), dist, age, alpha, same));
        }
        rows.sort(Comparator.comparingInt((Row r) -> r.sameDim ? 0 : 1)
                .thenComparingDouble(r -> r.dist)
                .thenComparing(r -> r.name.toLowerCase(Locale.ROOT)));
        int limit = Math.clamp(cfg.presenceMaxRows, 1, 256);
        if (rows.size() > limit) rows.subList(limit, rows.size()).clear();
        return rows;
    }

    private static HudSize measure(MinecraftClient mc, List<Row> rows) {
        if (rows.isEmpty()) return new HudSize(132, 58);
        var tr = mc.textRenderer;
        boolean cf = CleanFont.active();
        int panelW = 96;
        for (Row r : rows) {
            float w = 4 + tw(tr, cf, r.name) + 8 + tw(tr, cf, r.dim)
                    + 6 + tw(tr, cf, distText(r)) + 4 + 4 + 4;
            panelW = Math.max(panelW, (int) Math.ceil(w));
        }
        return new HudSize(Math.min(32768, panelW), rows.size() * (tr.fontHeight + 2) + 8);
    }

    private static void drawPanel(DrawContext ctx, MinecraftClient mc, OIConfig cfg, List<Row> rows, HudSize size) {
        var tr = mc.textRenderer;
        int lineH = tr.fontHeight + 2;
        int padX = 4, padY = 4, dot = 4;
        boolean cf = CleanFont.active();
        int panelW = size.width(), panelH = size.height();
        float opacity = cfg.relayOpacity / 255f;
        ctx.fill(0, 0, panelW, panelH, scaleAlpha(BG, opacity));
        ctx.fill(0, 0, panelW, 1, scaleAlpha(RIM, opacity));
        int y = padY;
        for (Row r : rows) {
            float a = r.alpha * opacity;
            int dotX = panelW - padX - dot;
            int distX = (int) (dotX - 4 - tw(tr, cf, distText(r)));
            draw(ctx, tr, cf, r.name, padX, y, scaleAlpha(r.color, a));
            draw(ctx, tr, cf, r.dim, padX + tw(tr, cf, r.name) + 8, y, scaleAlpha(RIM, a));
            draw(ctx, tr, cf, distText(r), distX, y, scaleAlpha(RIM, a));
            int dotY = y + (lineH - dot) / 2;
            ctx.fill(dotX, dotY, dotX + dot, dotY + dot, dotColor(r.age, a));
            y += lineH;
        }
    }

    private static float tw(net.minecraft.client.font.TextRenderer tr,
                            boolean cf, String s) {
        return cf ? CleanFont.width(s) : tr.getWidth(s);
    }

    private static void draw(DrawContext ctx, net.minecraft.client.font.TextRenderer tr,
                             boolean cf, String s, float x, int y, int color) {
        if (cf) CleanFont.draw(ctx, s, x, y, color, true);
        else ctx.drawText(tr, s, (int) x, y, color, true);
    }

    private static String distText(Row r) {
        return r.sameDim ? Math.round(r.dist) + "m" : "—";
    }

    private static String dimShort(String dim) {
        String id = dim.toLowerCase(Locale.ROOT);
        if (id.contains("nether")) return "N";
        if (id.contains("the_end") || id.endsWith(":end")) return "E";
        if (id.contains("overworld")) return "O";
        return "?";
    }

    private static int dotColor(float age, float alpha) {
        int c = age < 0.33f ? 0xFF55FF55 : age < 0.66f ? 0xFFFFFF55 : 0xFFFF5555;
        return scaleAlpha(c, alpha);
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.max(0, Math.round(((argb >>> 24) & 0xFF) * f)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
