package dev.openintel.xaero;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.ping.PingManager;
import io.github.billstark001.xaerobridge.api.MapOverlayContext;
import io.github.billstark001.xaerobridge.api.OverlayCanvas;
import io.github.billstark001.xaerobridge.api.XaeroWorldMapBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.lang.reflect.Field;

/**
 * Mirrors OpenIntel's snitch hits and shared pings onto Xaero's World Map
 * through the xaero-world-map-bridge overlay API.
 *
 * One permanent map overlay reads live state every frame — expiry,
 * disconnect clears and allegiance changes take care of themselves, and
 * context.dimension() follows whichever dimension the map is viewing, so
 * the built-in dimension switcher just works.
 *
 * This class is only loaded when the bridge mod is present (gated by
 * isModLoaded in OpenIntelClient), and registration itself is wrapped —
 * any bridge/Xaero version hiccup disables the overlay instead of
 * hurting the client. Same soft-dep contract as the JourneyMap bridge.
 */
public final class XaeroBridge {

    private static final int BACKING = 0xA0101014;

    /** Set once the lambda→GuiGraphicsExtractor unwrap fails — skip labels, keep fills. */
    private static boolean textBroken;

    private XaeroBridge() { }

    public static void register() {
        XaeroWorldMapBridge.registerMapOverlay("openintel:markers", 100, XaeroBridge::render);
    }

    private static void render(MapOverlayContext ctx) {
        var cfg = OpenIntelClient.config();
        var tracker = OpenIntelClient.tracker();
        if (cfg == null || tracker == null || !cfg.xaeroMarkers) return;

        String dim = ctx.dimension();
        if (dim == null) {
            var world = Minecraft.getInstance().level;
            if (world == null) return;
            dim = world.dimension().identifier().toString();
        }

        long now = System.currentTimeMillis();
        long snitchLife = cfg.snitchMarkerSeconds * 1000L;
        OverlayCanvas canvas = ctx.canvas();
        var mc = Minecraft.getInstance();
        String myName = mc.getUser().getName();
        // Players in render distance are already on the map natively —
        // stacking a relay dot + label on them is just noise.
        java.util.Set<String> local = new java.util.HashSet<>();
        if (mc.level != null) {
            for (var e : mc.level.players()) local.add(e.getGameProfile().name().toLowerCase());
        }

        // Relay positions: small allegiance dot, name underneath — focus
        // targets keep the full diamond so they read like pings/snitches.
        for (var p : tracker.all()) {
            if (!dim.equals(p.dimension) || p.name.equalsIgnoreCase(myName)
                    || local.contains(p.name.toLowerCase())) continue;
            float alpha = cfg.staleDecay
                    ? Math.max(0f, 1f - (now - p.lastSeen) / (float) cfg.staleAfterMs)
                    : 1f;
            if (alpha < 0.03f) continue;
            var a = p.allegiance != null ? p.allegiance : Allegiance.NEUTRAL;
            int argb = scaleAlpha(a.argb, alpha);
            int x = ctx.worldToScreenX(p.x);
            int y = ctx.worldToScreenY(p.z);
            if (a == Allegiance.FOCUS) {
                diamond(canvas, x, y, argb);
            } else {
                dot(canvas, x, y, argb);
            }
            label(ctx, x, y + 6, p.name, argb);
        }

        for (var h : tracker.snitchHits()) {
            if (!dim.equals(h.dimension)) continue;
            long age = Math.max(0, now - h.t);
            float fade = 1f - age / (float) snitchLife;
            if (fade <= 0.03f) continue;
            int rgb = (cfg.snitchMarkerColorAuto || cfg.snitchMarkerColor == -1)
                    ? OpenIntelClient.allegiances().of(h.player).argb
                    : cfg.snitchMarkerColor;
            int argb = scaleAlpha(rgb, fade);
            int x = ctx.worldToScreenX(h.x);
            int y = ctx.worldToScreenY(h.z);
            diamond(canvas, x, y, argb);
            label(ctx, x, y + 6, h.snitch + " | " + h.player + " | " + ago(age), argb);
        }

        for (PingManager.Ping p : PingManager.active()) {
            if (!dim.equals(p.dimension)) continue;
            float life = Math.min(1f, (p.expiresAt - now) / 4000f);
            if (life <= 0.03f) continue;
            int argb = scaleAlpha(p.color, life);
            int x = ctx.worldToScreenX(p.x);
            int y = ctx.worldToScreenY(p.z);
            diamond(canvas, x, y, argb);
            label(ctx, x, y + 6, "⚑ " + p.label + " (" + p.sender + ")", argb);
        }
    }

    // ------------------------------------------------------------ drawing ----

    /** Relay player marker: small filled disc, allegiance-tinted. */
    private static void dot(OverlayCanvas c, int x, int y, int argb) {
        int[] rows = {1, 2, 2, 2, 1};
        for (int dy = -2; dy <= 2; dy++) {
            int hw = rows[dy + 2];
            c.fill(x - hw, y + dy, x + hw + 1, y + dy + 1, argb);
        }
    }

    /** Allegiance-tinted diamond: dark silhouette one px larger, colored core. */
    private static void diamond(OverlayCanvas c, int x, int y, int argb) {
        for (int dy = -4; dy <= 4; dy++) {
            int hw = 4 - Math.abs(dy);
            c.fill(x - hw, y + dy, x + hw + 1, y + dy + 1, BACKING);
        }
        for (int dy = -3; dy <= 3; dy++) {
            int hw = 3 - Math.abs(dy);
            c.fill(x - hw, y + dy, x + hw + 1, y + dy + 1, argb);
        }
    }

    /**
     * Marker label below the icon, tinted to match, on a dark backing so it
     * stays readable over bright map tiles. The bridge canvas only exposes
     * fill(), so text goes through the GuiGraphicsExtractor the canvas wraps — when
     * that unwrap fails the markers still draw, just unlabeled.
     */
    private static void label(MapOverlayContext ctx, int cx, int y, String text, int argb) {
        GuiGraphicsExtractor dc = drawContext(ctx.canvas());
        if (dc == null) return;
        var tr = Minecraft.getInstance().font;
        int tw = tr.width(text);
        ctx.canvas().fill(cx - tw / 2 - 2, y - 1, cx + (tw + 1) / 2 + 2,
                y + tr.lineHeight + 1, BACKING);
        dc.text(tr, text, cx - tw / 2, y, argb | 0xFF000000, true);
    }

    /**
     * The bridge builds its canvas as GuiGraphicsExtractor::fill — a lambda holding
     * the real context in a captured field. Fish it out for text rendering;
     * harmless when the implementation changes (labels just drop out).
     */
    private static GuiGraphicsExtractor drawContext(OverlayCanvas canvas) {
        if (textBroken) return null;
        try {
            for (Field f : canvas.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.get(canvas) instanceof GuiGraphicsExtractor dc) return dc;
            }
        } catch (Throwable ignored) { }
        textBroken = true;
        return null;
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 10) return "now";
        if (s < 60) return s + "s";
        return (s / 60) + "m " + String.format("%02d", s % 60) + "s";
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.max(0, Math.round(((argb >>> 24) & 0xFF) * f)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
