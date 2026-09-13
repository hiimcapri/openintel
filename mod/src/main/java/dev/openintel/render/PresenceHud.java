package dev.openintel.render;

import dev.openintel.OpenIntelClient;
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

    public static void render(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.presenceEnabled || !cfg.relayRendering) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

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
            float age = Math.min(1f, (now - p.lastSeen) / (float) cfg.staleAfterMs);
            float alpha = cfg.staleDecay ? 1f - 0.75f * age : 1f;

            rows.add(new Row(p.name, color, dimShort(p.dimension), dist, age, alpha, same));
        }
        if (rows.isEmpty()) return;

        rows.sort(Comparator.comparingInt((Row r) -> r.sameDim ? 0 : 1)
                .thenComparingDouble(r -> r.dist)
                .thenComparing(r -> r.name.toLowerCase(Locale.ROOT)));
        if (rows.size() > cfg.presenceMaxRows) {
            rows = new ArrayList<>(rows.subList(0, cfg.presenceMaxRows));
        }

        var tr = mc.textRenderer;
        int lineH = tr.fontHeight + 2;
        int padX = 4, padY = 4, dot = 4;

        int panelW = 0;
        for (Row r : rows) {
            int w = padX + tr.getWidth(r.name) + 8 + tr.getWidth(r.dim)
                    + 6 + tr.getWidth(distText(r)) + 4 + dot + padX;
            panelW = Math.max(panelW, w);
        }
        panelW = Math.max(panelW, 96);
        int panelH = rows.size() * lineH + 2 * padY;

        int left = cfg.presenceX >= 0
                ? cfg.presenceX
                : ctx.getScaledWindowWidth() + cfg.presenceX - panelW;
        int top = cfg.presenceY;

        float opacity = cfg.relayOpacity / 255f;
        ctx.fill(left, top, left + panelW, top + panelH, scaleAlpha(BG, opacity));
        ctx.fill(left, top, left + panelW, top + 1, scaleAlpha(RIM, opacity));

        int y = top + padY;
        for (Row r : rows) {
            float a = r.alpha * opacity;
            int dotX = left + panelW - padX - dot;
            int distX = dotX - 4 - tr.getWidth(distText(r));

            ctx.drawText(tr, r.name, left + padX, y, scaleAlpha(r.color, a), true);
            ctx.drawText(tr, r.dim, left + padX + tr.getWidth(r.name) + 8, y,
                    scaleAlpha(RIM, a), true);
            ctx.drawText(tr, distText(r), distX, y, scaleAlpha(RIM, a), true);

            int dotY = y + (lineH - dot) / 2;
            ctx.fill(dotX, dotY, dotX + dot, dotY + dot, dotColor(r.age, a));
            y += lineH;
        }
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
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
