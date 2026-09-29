package dev.openintel.radar;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.ping.PingManager;
import dev.openintel.render.CleanFont;
import dev.openintel.render.HudLayout;
import dev.openintel.render.HudLayouts;
import dev.openintel.render.ColoredQuadsElement;
import dev.openintel.tracker.Tracker.RemotePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final float LABEL_PAD = 2f;
    private static final String OVERFLOW_KEY = "~overflow";
    private static final RadarLabelLayout.Session LABEL_LAYOUT = new RadarLabelLayout.Session();
    private record RadarLabel(RadarLabelLayout.Label bounds, String text, int color, float scale) { }

    public static void render(GuiGraphicsExtractor ctx, float tickDelta) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.radarEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) return;

        int r = HudLayouts.radius(cfg);
        var frame = HudLayouts.bounds(HudLayouts.Element.RADAR, mc, cfg,
                ctx.guiWidth(), ctx.guiHeight());
        if (frame.scale() <= 0) return;
        float yaw = mc.player.getYRot(tickDelta);
        Vec3 self = mc.player.getPosition(tickDelta);

        float frameRot = cfg.radarNorthUp ? (float) Math.PI : (float) -Math.toRadians(yaw);

        List<RadarLabel> labels = new ArrayList<>();
        try (var ignored = HudLayouts.apply(ctx, frame)) {
            Matrix3x2fStack pose = ctx.pose();
            pose.translate(r + 3f, r + 3f);
            pose.rotate(frameRot);
            drawDial(ctx, cfg, r);
            drawCardinals(ctx, mc, cfg, frameRot, r);
            drawFacingChevron(ctx, cfg, yaw);
            renderVehiclesAndItems(ctx, mc, cfg, self, yaw, tickDelta);
            if (cfg.radarShowPlayers) {
                renderPlayers(ctx, mc, cfg, self, yaw, tickDelta, labels);
            }
            if (cfg.radarShowPings && cfg.relayRendering) {
                renderPings(ctx, mc, cfg, self, yaw, labels);
            }
        }
        drawLabels(ctx, mc, cfg, labels, frame);
    }

    // ------------------------------------------------------------- blips ----

    private static void renderPlayers(GuiGraphicsExtractor ctx, Minecraft mc, OIConfig cfg,
                                      Vec3 self, float yaw, float tickDelta,
                                      List<RadarLabel> labels) {
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player || !p.isAlive()) continue;
            if (!contactAllowed(p.getGameProfile().name(), cfg)) continue;

            Vec3 pos = p.getPosition(tickDelta);
            double dx = self.x - pos.x, dz = self.z - pos.z;
            double dist = Math.hypot(dx, dz);
            if (dist > cfg.radarRange) continue;

            String name = p.getGameProfile().name();
            int color = OpenIntelClient.allegiances().of(name).argb;
            String label = name + " (" + Math.round(dist) + ")";

            blip(ctx, dx, dz, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.pose();
                pose.pushMatrix();
                pose.scale(cfg.radarIconSize, cfg.radarIconSize);

                PlayerInfo entry = mc.getConnection() == null
                        ? null : mc.getConnection().getPlayerInfo(p.getUUID());
                // Allegiance frame around the face.
                ctx.outline(-5, -5, 10, 10, color);
                if (entry != null) {
                    PlayerFaceExtractor.extractRenderState(ctx, entry.getSkin(), -4, -4, 8);
                } else {
                    ctx.fill(-3, -3, 3, 3, color);
                }

                pose.popMatrix();
                pose.pushMatrix();
                localLabelPose(pose, cfg.radarIconSize, cfg.radarTextSize);
                queueLabel(ctx, mc, labels, "player:" + name, label, 0, 1, color, false);
                pose.popMatrix();
            });
        }
    }

    private static void renderVehiclesAndItems(GuiGraphicsExtractor ctx, Minecraft mc, OIConfig cfg,
                                               Vec3 self, float yaw, float tickDelta) {
        if (!cfg.radarShowItems && !cfg.radarShowVehicles) return;

        int drawn = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            ItemStack icon = iconFor(e, cfg);
            if (icon == null || icon.isEmpty()) continue;

            Vec3 pos = e.getPosition(tickDelta);
            double dx = self.x - pos.x, dz = self.z - pos.z;
            if (dx * dx + dz * dz > cfg.radarRange * cfg.radarRange) continue;

            blip(ctx, dx, dz, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.pose();
                pose.pushMatrix();
                pose.scale(cfg.radarIconSize * 0.9f, cfg.radarIconSize * 0.9f);
                ctx.fakeItem(icon, -8, -8);
                pose.popMatrix();
            });
            if (++drawn > MAX_ITEM_BLIPS) return;
        }
    }

    private static ItemStack iconFor(Entity e, OIConfig cfg) {
        if (e instanceof ItemEntity item) {
            return cfg.radarShowItems ? item.getItem() : null;
        }
        if (e instanceof AbstractBoat boat) {
            return cfg.radarShowVehicles ? boat.getPickResult() : null;
        }
        if (e instanceof AbstractMinecart cart) {
            // Pick-stack gives the real cart type (TNT, chest, hopper...).
            return cfg.radarShowVehicles ? cart.getPickResult() : null;
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
    private static void renderRelayBlips(GuiGraphicsExtractor ctx, Minecraft mc, OIConfig cfg,
                                         Vec3 self, float yaw, int r,
                                         Set<String> onRadar, List<RadarLabel> labels) {
        String myDim = mc.level.dimension().identifier().toString();
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

            int color = scaleAlpha(
                    p.allegiance != null ? p.allegiance.argb : 0xFFAAAAAA, fade);
            String label = p.name + " (" + Math.round(dist) + ")";

            blip(ctx, dx, dz, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.pose();
                pose.pushMatrix();
                ctx.fill(-2, -2, 2, 2, color);
                pose.translate(0, 3.5f);
                scaleText(pose, 0.5f * cfg.radarTextSize);
                queueLabel(ctx, mc, labels, "player:" + p.name, label, 0, 1, color, false);
                pose.popMatrix();
            });
        }
    }

    /**
     * Shared pings from the wheel — diamonds on the dial, pinned to the rim
     * when the ping is beyond the sweep. Fades during its last seconds.
     */
    private static void renderPings(GuiGraphicsExtractor ctx, Minecraft mc, OIConfig cfg,
                                    Vec3 self, float yaw, List<RadarLabel> labels) {
        String myDim = mc.level.dimension().identifier().toString();
        long now = System.currentTimeMillis();

        for (PingManager.Ping ping : PingManager.active()) {
            if (ping.dimension == null || !ping.dimension.equals(myDim)) continue;

            double dx = self.x - ping.x, dz = self.z - ping.z;
            double dist = Math.hypot(dx, dz);
            if (dist < 1) continue;

            float life = Math.min(1f, (ping.expiresAt - now) / 4000f);
            int color = scaleAlpha(ping.color, life);
            String label = "⚑ " + ping.label + " (" + Math.round(dist) + ")";

            blip(ctx, dx, dz, cfg, yaw, () -> {
                Matrix3x2fStack pose = ctx.pose();
                pose.pushMatrix();
                // Diamond, drawn as a small rotated square.
                ctx.fill(-1, -3, 1, 1, color);
                ctx.fill(-3, -1, -1, 1, color);
                ctx.fill(-1, -1, 1, 3, color);
                ctx.fill(1, -1, 3, 1, color);
                pose.translate(0, 4.5f);
                scaleText(pose, 0.5f * cfg.radarTextSize);
                queueLabel(ctx, mc, labels, "ping:" + ping.id, label, 0, 1, color, false);
                pose.popMatrix();
            });
        }
    }

    /**
     * Positions a blip in dial space: distance is mapped through the
     * compression blend, the result is clamped just inside the rim, then
     * counter-rotated so icons and text stay upright.
     */
    private static void blip(GuiGraphicsExtractor ctx, double dx, double dz,
                             OIConfig cfg, float yaw, Runnable painter) {
        double dist = Math.hypot(dx, dz);
        if (dist < 0.1) return;
        double radius = Math.min(mapDistance(dist, cfg.radarRange, cfg.radarCompression), 0.97) * HudLayouts.radius(cfg);
        double unit = radius / dist;

        Matrix3x2fStack pose = ctx.pose();
        pose.pushMatrix();
        pose.translate((float) (dx * unit), (float) (dz * unit));
        pose.rotate(cfg.radarNorthUp ? (float) Math.PI : (float) Math.toRadians(yaw));
        painter.run();
        pose.popMatrix();
    }

    /**
     * Maps world distance to a fraction of the dial radius using a
     * piecewise-linear "knee": the inner zone is a true linear minimap
     * (motion stays proportional, no acceleration feel), and everything
     * beyond it compresses linearly into the remaining ring.
     * compression 0 → single linear map; 100 → inner 25% of range
     * magnified across 55% of the dial.
     */
    private static double mapDistance(double dist, double range, int compression) {
        double c = Math.min(100, Math.max(0, compression)) / 100.0;
        double kneeDist = (1.0 - 0.75 * c) * range;   // 100% → 25% of range
        double kneeRadius = 1.0 - 0.45 * c;           // occupies 100% → 55% of dial
        if (dist <= kneeDist || range <= kneeDist) return dist / kneeDist * kneeRadius;
        return kneeRadius + (dist - kneeDist) / (range - kneeDist) * (1.0 - kneeRadius);
    }

    // -------------------------------------------------------------- dial ----

    /**
     * The dial face as actual geometry — one GUI element emitting quads
     * (degenerate quads for triangles) with per-vertex colors, so every edge
     * is a real gradient at screen resolution. No texture, no texels.
     */
    private static void drawDial(GuiGraphicsExtractor ctx, OIConfig cfg, int r) {
        Matrix3x2f pose = new Matrix3x2f(ctx.pose());
        ctx.guiRenderState.addGuiElement(new ColoredQuadsElement(
                pose, vc -> paintDial(vc, pose, r, cfg),
                new ScreenRectangle(-(r + 3), -(r + 3), 2 * r + 6, 2 * r + 6)
                        .transformMaxBounds(pose)));
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
        vert(vc, pose, x0, y0, c0);
        vert(vc, pose, x1, y1, c1);
        vert(vc, pose, x2, y2, c2);
        vert(vc, pose, x3, y3, c3);
    }

    private static void vert(VertexConsumer vc, Matrix3x2fc pose, float x, float y, int c) {
        Vector2f p = pose.transformPosition(x, y, new Vector2f());
        vc.addVertex(p.x, p.y, 0).setColor(c);
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.round(((argb >>> 24) & 0xFF) * f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /** N/E/S/W hugging the rim — small, soft, upright in the rotated frame. */
    private static void drawCardinals(GuiGraphicsExtractor ctx, Minecraft mc, OIConfig cfg,
                                      float frameRot, int r) {
        float R = r * 0.85f;
        cardinal(ctx, mc, cfg, frameRot, 0f, 1f, R, "N");   // north = -Z → +dz in our convention
        cardinal(ctx, mc, cfg, frameRot, -1f, 0f, R, "E");  // east = +X → -dx
        cardinal(ctx, mc, cfg, frameRot, 0f, -1f, R, "S");
        cardinal(ctx, mc, cfg, frameRot, 1f, 0f, R, "W");
    }

    private static void cardinal(GuiGraphicsExtractor ctx, Minecraft mc, OIConfig cfg,
                                 float frameRot, float vx, float vz, float R, String letter) {
        Matrix3x2fStack pose = ctx.pose();
        pose.pushMatrix();
        // The pose already carries the frame rotation, so translating by the
        // world-direction vector lands the letter on that bearing — applying
        // frameRot here too would rotate it twice.
        pose.translate(vx * R, vz * R);
        pose.rotate(-frameRot);                          // letters stay upright
        scaleText(pose, 0.5f * cfg.radarTextSize);
        lbl(ctx, mc, letter, 0, -4,
                (cfg.radarFgColor & 0x00FFFFFF) | 0xD9000000);
        pose.popMatrix();
    }

    /** Slim needle marking your view direction on the dial. */
    private static void drawFacingChevron(GuiGraphicsExtractor ctx, OIConfig cfg, float yaw) {
        Matrix3x2fStack pose = ctx.pose();
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


    private static void scaleText(Matrix3x2fStack pose, float size) {
        pose.scale(2f * size, 2f * size);
    }

    private static void localLabelPose(Matrix3x2fStack pose, float iconSize, float textSize) {
        pose.translate(0, 5f * iconSize + 1f);
        scaleText(pose, 0.6f * textSize);
    }

    private static float textWidth(Minecraft mc, String text) {
        return CleanFont.active() ? CleanFont.width(text) : mc.font.width(text);
    }

    private static void queueLabel(GuiGraphicsExtractor ctx, Minecraft mc, List<RadarLabel> labels,
                                   String key, String text, float cx, float y, int color, boolean fixed) {
        var pose = ctx.pose();
        float scale = (float) Math.hypot(pose.m00(), pose.m01());
        if (!(scale > 0) || (color >>> 24) == 0) return;
        float maxWidth = (ctx.guiWidth() - 4 - LABEL_PAD * 2) / scale;
        float measured = textWidth(mc, text);
        if (measured > maxWidth) {
            text = HudLayout.ellipsize(text, maxWidth, value -> textWidth(mc, value));
            measured = textWidth(mc, text);
        }
        Vector2f origin = pose.transformPosition(cx, y, new Vector2f());
        float width = measured * scale + LABEL_PAD * 2;
        float height = mc.font.lineHeight * scale + LABEL_PAD * 2;
        labels.add(new RadarLabel(new RadarLabelLayout.Label(key, origin.x - width / 2,
                origin.y - LABEL_PAD, width, height, fixed), text, color, scale));
    }

    private static void drawLabels(GuiGraphicsExtractor ctx, Minecraft mc, OIConfig cfg,
                                   List<RadarLabel> labels, HudLayout.Frame frame) {
        var pose = ctx.pose();
        var byKey = new LinkedHashMap<String, RadarLabel>();
        for (RadarLabel label : labels) byKey.put(label.bounds.key(), label);
        var result = LABEL_LAYOUT.place(byKey.values().stream().map(RadarLabel::bounds).toList(),
                ctx.guiWidth(), ctx.guiHeight());
        if (result.hidden() > 0) {
            int contacts = byKey.size();
            pose.pushMatrix();
            pose.translate(frame.x() + frame.width() / 2f, frame.bottom() + 5f * frame.scale());
            pose.scale(cfg.radarTextSize * frame.scale(), cfg.radarTextSize * frame.scale());
            queueLabel(ctx, mc, labels, OVERFLOW_KEY,
                    "+" + "8".repeat(Integer.toString(contacts).length()) + " more", 0, 0, 0xFFE0E4EA, true);
            pose.popMatrix();
            RadarLabel overflow = labels.get(labels.size() - 1);
            byKey.put(overflow.bounds.key(), overflow);
            result = LABEL_LAYOUT.place(byKey.values().stream().map(RadarLabel::bounds).toList(),
                    ctx.guiWidth(), ctx.guiHeight());
        }
        pose.pushMatrix();
        pose.identity();
        for (var p : result.placed()) {
            RadarLabel label = byKey.get(p.label().key());
            boolean overflow = p.label().key().equals(OVERFLOW_KEY);
            if (overflow && result.hidden() == 0) continue;
            pose.pushMatrix();
            pose.translate(p.x() + p.label().width() / 2, p.y() + LABEL_PAD);
            pose.scale(label.scale, label.scale);
            lbl(ctx, mc, overflow ? "+" + result.hidden() + " more" : label.text, 0, 0, label.color | 0xFF000000);
            pose.popMatrix();
        }
        pose.popMatrix();
    }

    /** Radar text through the clean font when it's enabled. */
    private static void lbl(GuiGraphicsExtractor ctx, Minecraft mc, String s,
                            float cx, float y, int color) {
        if (CleanFont.active()) {
            CleanFont.drawCentered(ctx, s, cx, y, color);
        } else {
            ctx.centeredText(mc.font, s,
                    Math.round(cx), Math.round(y), color);
        }
    }
}