package dev.openintel.radar;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.mixin.DrawContextAccessor;
import dev.openintel.ping.PingManager;
import dev.openintel.render.ColoredQuadsElement;
import dev.openintel.tracker.Tracker.RemotePlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix3x2fc;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Circular radar dial on the HUD, ported in spirit from CivModern but
 * rewritten for OpenIntel (no owo-lib, no event bus):
 *
 *  - The dial face is real GUI geometry emitted through
 *    ColoredQuadsElement — gradient disc edge, soft range rings, dim
 *    cardinal spokes, bright rim — rebuilt every frame at screen res.
 *  - N/E/S/W letters ride the rim; a chevron at the center marks your view.
 *  - In-render players draw as face icons with a name + distance tag,
 *    colored by allegiance (focus/friend/ally/enemy/neutral).
 *  - Boats, minecarts and (optionally) dropped items draw as item icons.
 *  - Relay-reported players outside render distance pin to the rim as
 *    allegiance-colored dots — the thing a purely local radar can't do.
 *
 * /oi radar opens the options screen; the toggle key flips it on the fly.
 */
public final class RadarHud {
    private RadarHud() { }

    private static final int MAX_ITEM_BLIPS = 1000;
    private static final double TAU = Math.PI * 2;

    public static void render(DrawContext ctx, float tickDelta) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.radarEnabled) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

        int r = cfg.radarSize;
        float yaw = mc.player.getYaw(tickDelta);
        Vec3d self = mc.player.getLerpedPos(tickDelta);
        double scale = r / cfg.radarRange;
        float frameRot = cfg.radarNorthUp ? (float) Math.PI : (float) -Math.toRadians(yaw);

        Matrix3x2fStack pose = ctx.getMatrices();
        pose.pushMatrix();
        pose.translate(cfg.radarX + r, cfg.radarY + r);
        pose.rotate(frameRot);

        drawDial(ctx, cfg, r);
        drawCardinals(ctx, mc, cfg, frameRot, r);
        drawFacingChevron(ctx, cfg, yaw);

        Set<String> onRadar = new HashSet<>();
        renderVehiclesAndItems(ctx, mc, cfg, self, scale, yaw, tickDelta);
        if (cfg.radarShowPlayers) {
            renderPlayers(ctx, mc, cfg, self, scale, yaw, tickDelta, onRadar);
            if (cfg.radarShowRelay && cfg.relayRendering) {
                renderRelayBlips(ctx, mc, cfg, self, scale, yaw, r, onRadar);
            }
        }
        if (cfg.radarShowPings && cfg.relayRendering) {
            renderPings(ctx, mc, cfg, self, scale, yaw);
        }

        pose.popMatrix();
    }

    // ------------------------------------------------------------- blips ----

    private static void renderPlayers(DrawContext ctx, MinecraftClient mc, OIConfig cfg,
                                      Vec3d self, double scale, float yaw, float tickDelta,
                                      Set<String> onRadar) {
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;
            if (!contactAllowed(p.getGameProfile().name(), cfg)) continue;

            Vec3d pos = p.getLerpedPos(tickDelta);
            double dx = self.x - pos.x, dz = self.z - pos.z;
            double dist = Math.hypot(dx, dz);
            if (dist > cfg.radarRange) continue;

            String name = p.getGameProfile().name();
            onRadar.add(name.toLowerCase(Locale.ROOT));
            int color = OpenIntelClient.allegiances().of(name).argb;
            String label = name + " (" + Math.round(dist) + ")";

            blip(ctx, dx, dz, scale, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.getMatrices();
                pose.pushMatrix();
                pose.scale(cfg.radarIconSize, cfg.radarIconSize);

                PlayerListEntry entry = mc.getNetworkHandler() == null
                        ? null : mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
                // Allegiance frame around the face.
                ctx.drawStrokedRectangle(-5, -5, 10, 10, color);
                if (entry != null) {
                    PlayerSkinDrawer.draw(ctx, entry.getSkinTextures(), -4, -4, 8);
                } else {
                    ctx.fill(-3, -3, 3, 3, color);
                }

                pose.translate(0, 4.5f * cfg.radarIconSize);
                pose.scale(0.6f * cfg.radarTextSize, 0.6f * cfg.radarTextSize);
                ctx.drawCenteredTextWithShadow(mc.textRenderer, label, 0, 1, color);
                pose.popMatrix();
            });
        }
    }

    private static void renderVehiclesAndItems(DrawContext ctx, MinecraftClient mc, OIConfig cfg,
                                               Vec3d self, double scale, float yaw, float tickDelta) {
        if (!cfg.radarShowItems && !cfg.radarShowVehicles) return;

        int drawn = 0;
        for (Entity e : mc.world.getEntities()) {
            ItemStack icon = iconFor(e, cfg);
            if (icon == null || icon.isEmpty()) continue;

            Vec3d pos = e.getLerpedPos(tickDelta);
            double dx = self.x - pos.x, dz = self.z - pos.z;
            if (dx * dx + dz * dz > cfg.radarRange * cfg.radarRange) continue;

            blip(ctx, dx, dz, scale, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.getMatrices();
                pose.pushMatrix();
                pose.scale(cfg.radarIconSize * 0.9f, cfg.radarIconSize * 0.9f);
                ctx.drawItemWithoutEntity(icon, -8, -8);
                pose.popMatrix();
            });
            if (++drawn > MAX_ITEM_BLIPS) return;
        }
    }

    private static ItemStack iconFor(Entity e, OIConfig cfg) {
        if (e instanceof ItemEntity item) {
            return cfg.radarShowItems ? item.getStack() : null;
        }
        if (e instanceof AbstractBoatEntity boat) {
            return cfg.radarShowVehicles ? boat.getPickBlockStack() : null;
        }
        if (e instanceof AbstractMinecartEntity cart) {
            // Pick-stack gives the real cart type (TNT, chest, hopper...).
            return cfg.radarShowVehicles ? cart.getPickBlockStack() : null;
        }
        return null;
    }

    /** Ping filter: everyone, strangers only, or enemies only. */
    private static boolean contactAllowed(String name, OIConfig cfg) {
        return switch (cfg.radarPlayerFilter) {
            case EVERYONE -> true;
            case NON_RELAY -> !OpenIntelClient.allegiances().isRelayUser(name);
            case ENEMIES -> OpenIntelClient.allegiances().isEnemy(name);
        };
    }

    /**
     * Relay-tracked players that aren't rendered locally. Inside range they
     * sit at their true position; beyond it they pin to the rim so the dial
     * answers "which way" even at 400m out.
     */
    private static void renderRelayBlips(DrawContext ctx, MinecraftClient mc, OIConfig cfg,
                                         Vec3d self, double scale, float yaw, int r,
                                         Set<String> onRadar) {
        String myDim = mc.world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();

        for (RemotePlayer p : OpenIntelClient.tracker().all()) {
            if (p.dimension == null || !p.dimension.equals(myDim)) continue;
            if (onRadar.contains(p.name.toLowerCase(Locale.ROOT))) continue;
            if (!contactAllowed(p.name, cfg)) continue;

            // Cold intel cools off as it approaches staleAfterMs.
            float fade = cfg.staleDecay
                    ? Math.max(0f, 1f - (now - p.lastSeen) / (float) cfg.staleAfterMs)
                    : 1f;
            if (fade < 0.05f) continue;

            double dx = self.x - p.x, dz = self.z - p.z;
            double dist = Math.hypot(dx, dz);
            if (dist < 1) continue;

            // Beyond the sweep? Slide the dot to the rim, same bearing.
            if (dist > cfg.radarRange) {
                double f = cfg.radarRange * 0.97 / dist;
                dx *= f;
                dz *= f;
            }

            int color = scaleAlpha(
                    p.allegiance != null ? p.allegiance.argb : 0xFFAAAAAA, fade);
            String label = p.name + " (" + Math.round(dist) + ")";

            blip(ctx, dx, dz, scale, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.getMatrices();
                pose.pushMatrix();
                ctx.fill(-2, -2, 2, 2, color);
                pose.translate(0, 3.5f);
                pose.scale(0.5f * cfg.radarTextSize, 0.5f * cfg.radarTextSize);
                ctx.drawCenteredTextWithShadow(mc.textRenderer, label, 0, 1, color);
                pose.popMatrix();
            });
        }
    }

    /**
     * Shared pings from the wheel — diamonds on the dial, pinned to the rim
     * when the ping is beyond the sweep. Fades during its last seconds.
     */
    private static void renderPings(DrawContext ctx, MinecraftClient mc, OIConfig cfg,
                                    Vec3d self, double scale, float yaw) {
        String myDim = mc.world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();

        for (PingManager.Ping ping : PingManager.active()) {
            if (ping.dimension == null || !ping.dimension.equals(myDim)) continue;

            double dx = self.x - ping.x, dz = self.z - ping.z;
            double dist = Math.hypot(dx, dz);
            if (dist < 1) continue;
            if (dist > cfg.radarRange) {
                double f = cfg.radarRange * 0.97 / dist;
                dx *= f;
                dz *= f;
            }

            float life = Math.min(1f, (ping.expiresAt - now) / 4000f);
            int color = scaleAlpha(ping.color, life);
            String label = "⚑ " + ping.label + " (" + Math.round(dist) + ")";

            blip(ctx, dx, dz, scale, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.getMatrices();
                pose.pushMatrix();
                // Diamond, drawn as a small rotated square.
                ctx.fill(-1, -3, 1, 1, color);
                ctx.fill(-3, -1, -1, 1, color);
                ctx.fill(-1, -1, 1, 3, color);
                ctx.fill(1, -1, 3, 1, color);
                pose.translate(0, 4.5f);
                pose.scale(0.5f * cfg.radarTextSize, 0.5f * cfg.radarTextSize);
                ctx.drawCenteredTextWithShadow(mc.textRenderer, label, 0, 1, color);
                pose.popMatrix();
            });
        }
    }

    /**
     * Positions a blip in dial space (log-scaled distance, rotated frame),
     * then counter-rotates so icons and text stay upright.
     */
    private static void blip(DrawContext ctx, double dx, double dz, double scale,
                             OIConfig cfg, float yaw, Runnable painter) {
        double logscale = rescale(dx, dz, cfg.radarRange, cfg.radarCompressDistance);

        Matrix3x2fStack pose = ctx.getMatrices();
        pose.pushMatrix();
        pose.translate((float) (dx * scale * logscale), (float) (dz * scale * logscale));
        pose.rotate(cfg.radarNorthUp ? (float) Math.PI : (float) Math.toRadians(yaw));
        painter.run();
        pose.popMatrix();
    }

    /** Logarithmic distance compression — close contacts stay readable. */
    private static double rescale(double dx, double dz, double range, boolean log) {
        if (!log) return 1;
        double dist = Math.hypot(dx, dz);
        if (dist < 0.1) return 1;
        return Math.log1p(dist) / Math.log1p(range) * range / dist;
    }

    // -------------------------------------------------------------- dial ----

    /**
     * The dial face as actual geometry — one GUI element emitting quads
     * (degenerate quads for triangles) with per-vertex colors, so every edge
     * is a real gradient at screen resolution. No texture, no texels.
     */
    private static void drawDial(DrawContext ctx, OIConfig cfg, int r) {
        Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
        ((DrawContextAccessor) ctx).openintel$state().addSimpleElement(new ColoredQuadsElement(
                pose, vc -> paintDial(vc, pose, r, cfg),
                new ScreenRect(-(r + 3), -(r + 3), 2 * r + 6, 2 * r + 6)
                        .transformEachVertex(pose)));
    }

    private static void paintDial(VertexConsumer vc, Matrix3x2fc pose, int r, OIConfig cfg) {
        int bg = cfg.radarBgColor;
        int fg = cfg.radarFgColor;
        int bgCenter = scaleAlpha(bg, 0.78f);           // radial vignette
        int bgEdge0 = bg & 0x00FFFFFF;                  // same rgb, alpha 0
        int spoke = scaleAlpha(fg, 0.4f);
        int spoke0 = spoke & 0x00FFFFFF;
        int rim = scaleAlpha(fg, 1.8f);
        int rim0 = rim & 0x00FFFFFF;
        int fg0 = fg & 0x00FFFFFF;
        int seg = Math.max(48, r * 2);

        // Soft drop shadow hugging the rim.
        ringBand(vc, pose, r + 0.5, r + 2.6, seg, 0x4A000000, 0x00000000);

        // Disc face + AA fringe.
        fan(vc, pose, 0, r, seg, bgCenter, bg);
        ringBand(vc, pose, r, r + 0.9, seg, bg, bgEdge0);

        // Spokes: 8 soft rays, kept clear of center and rim.
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            ray(vc, pose, a, 7, r - 3, spoke, spoke0);
        }

        // Range rings with AA on both sides.
        for (int i = 1; i <= cfg.radarCircles; i++) {
            double rr = r * i / (double) cfg.radarCircles;
            ringBand(vc, pose, rr - 1.15, rr - 0.5, seg, fg0, fg);
            ringBand(vc, pose, rr - 0.5, rr + 0.5, seg, fg, fg);
            ringBand(vc, pose, rr + 0.5, rr + 1.15, seg, fg, fg0);
        }

        // Rim, brightest element on the dial.
        ringBand(vc, pose, r - 0.9, r - 0.35, seg, rim0, rim);
        ringBand(vc, pose, r - 0.35, r + 0.35, seg, rim, rim);
        ringBand(vc, pose, r + 0.35, r + 0.9, seg, rim, rim0);
    }

    // --------------------------------------------------- quad emitters ----

    /** Filled fan: one center vertex ringed by seg perimeter vertices. */
    private static void fan(VertexConsumer vc, Matrix3x2fc pose, double r0, double r1,
                            int seg, int cIn, int cOut) {
        for (int i = 0; i < seg; i++) {
            double a0 = i * TAU / seg, a1 = (i + 1) * TAU / seg;
            tri(vc, pose,
                    0, 0, cIn,
                    (float) (Math.cos(a1) * r1), (float) (Math.sin(a1) * r1), cOut,
                    (float) (Math.cos(a0) * r1), (float) (Math.sin(a0) * r1), cOut);
        }
    }

    /** Annulus band r0..r1 with independent inner/outer edge colors. */
    private static void ringBand(VertexConsumer vc, Matrix3x2fc pose, double r0, double r1,
                                 int seg, int cIn, int cOut) {
        // Winding must match the GUI pipeline's front face (same as ray()):
        // inner-a0 → inner-a1 → outer-a1 → outer-a0.
        for (int i = 0; i < seg; i++) {
            double a0 = i * TAU / seg, a1 = (i + 1) * TAU / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            quad(vc, pose,
                    c0 * (float) r0, s0 * (float) r0, cIn,
                    c1 * (float) r0, s1 * (float) r0, cIn,
                    c1 * (float) r1, s1 * (float) r1, cOut,
                    c0 * (float) r1, s0 * (float) r1, cOut);
        }
    }

    /** A spoke ray: opaque core with transparent edge fringes. */
    private static void ray(VertexConsumer vc, Matrix3x2fc pose, double angle,
                            double t0, double t1, int c, int c0) {
        float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
        float nx = -dy, ny = dx;                       // perpendicular
        float inner = 0.15f, outer = 0.5f;

        quad(vc, pose,
                dx * t0f(t0) - nx * outer, dy * t0f(t0) - ny * outer, c0,
                dx * t0f(t0) - nx * inner, dy * t0f(t0) - ny * inner, c,
                dx * t0f(t1) - nx * inner, dy * t0f(t1) - ny * inner, c,
                dx * t0f(t1) - nx * outer, dy * t0f(t1) - ny * outer, c0);
        quad(vc, pose,
                dx * t0f(t0) - nx * inner, dy * t0f(t0) - ny * inner, c,
                dx * t0f(t0) + nx * inner, dy * t0f(t0) + ny * inner, c,
                dx * t0f(t1) + nx * inner, dy * t0f(t1) + ny * inner, c,
                dx * t0f(t1) - nx * inner, dy * t0f(t1) - ny * inner, c);
        quad(vc, pose,
                dx * t0f(t0) + nx * inner, dy * t0f(t0) + ny * inner, c,
                dx * t0f(t0) + nx * outer, dy * t0f(t0) + ny * outer, c0,
                dx * t0f(t1) + nx * outer, dy * t0f(t1) + ny * outer, c0,
                dx * t0f(t1) + nx * inner, dy * t0f(t1) + ny * inner, c);
    }

    private static float t0f(double t) { return (float) t; }

    private static void tri(VertexConsumer vc, Matrix3x2fc pose,
                            float x0, float y0, int c0,
                            float x1, float y1, int c1,
                            float x2, float y2, int c2) {
        quad(vc, pose, x0, y0, c0, x1, y1, c1, x2, y2, c2, x2, y2, c2);
    }

    private static void quad(VertexConsumer vc, Matrix3x2fc pose,
                             float x0, float y0, int c0,
                             float x1, float y1, int c1,
                             float x2, float y2, int c2,
                             float x3, float y3, int c3) {
        vc.vertex(pose, x0, y0).color(c0);
        vc.vertex(pose, x1, y1).color(c1);
        vc.vertex(pose, x2, y2).color(c2);
        vc.vertex(pose, x3, y3).color(c3);
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.round(((argb >>> 24) & 0xFF) * f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /** N/E/S/W hugging the rim — small, soft, upright in the rotated frame. */
    private static void drawCardinals(DrawContext ctx, MinecraftClient mc, OIConfig cfg,
                                      float frameRot, int r) {
        float R = r * 0.85f;
        cardinal(ctx, mc, cfg, frameRot, 0f, 1f, R, "N");   // north = -Z → +dz in our convention
        cardinal(ctx, mc, cfg, frameRot, -1f, 0f, R, "E");  // east = +X → -dx
        cardinal(ctx, mc, cfg, frameRot, 0f, -1f, R, "S");
        cardinal(ctx, mc, cfg, frameRot, 1f, 0f, R, "W");
    }

    private static void cardinal(DrawContext ctx, MinecraftClient mc, OIConfig cfg,
                                 float frameRot, float vx, float vz, float R, String letter) {
        Matrix3x2fStack pose = ctx.getMatrices();
        pose.pushMatrix();
        // The pose already carries the frame rotation, so translating by the
        // world-direction vector lands the letter on that bearing — applying
        // frameRot here too would rotate it twice.
        pose.translate(vx * R, vz * R);
        pose.rotate(-frameRot);                          // letters stay upright
        pose.scale(0.5f * cfg.radarTextSize, 0.5f * cfg.radarTextSize);
        ctx.drawCenteredTextWithShadow(mc.textRenderer, letter, 0, -4,
                (cfg.radarFgColor & 0x00FFFFFF) | 0xD9000000);
        pose.popMatrix();
    }

    /** Slim needle marking your view direction on the dial. */
    private static void drawFacingChevron(DrawContext ctx, OIConfig cfg, float yaw) {
        Matrix3x2fStack pose = ctx.getMatrices();
        pose.pushMatrix();
        // The dial frame is world-aligned, so the marker just turns with
        // yaw: rotating mode nets out to screen-up (frame -yaw + yaw),
        // north-up lands it on your heading.
        pose.rotate((float) Math.toRadians(yaw));
        int argb = (cfg.radarFgColor & 0x00FFFFFF) | 0xF2000000;
        for (int y = -4; y <= 3; y++) {
            int w = Math.max(0, (y + 4) / 3);   // needle: 1px tip → 2px base
            ctx.fill(-w, y, w + 1, y + 1, argb);
        }
        pose.popMatrix();
    }

}