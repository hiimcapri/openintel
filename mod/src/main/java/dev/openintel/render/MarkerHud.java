package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.ping.PingManager.Ping;
import dev.openintel.tracker.Tracker.RemotePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One renderer for every shared marker, drawn on the HUD layer:
 *
 *  - Target on screen  → name + chevron at a fixed 1x scale, centered on the
 *    target's projected position (anchored above the head). No distance
 *    scaling, no sliding around the view edge.
 *  - Target off screen → name stacked on the left or right screen edge,
 *    picked by which side of the view it falls on (camera-local X sign, so
 *    targets behind you sort correctly too). Nearest first.
 *
 * Focused players (set by Captains via /oi focus) render in bright purple
 * with a ◆ prefix and take color precedence over everything else.
 *
 * The camera pos + rotation are read from the game renderer at draw time
 * (same frame and thread as the HUD pass), so projection matches the view.
 */
public final class MarkerHud {
    private MarkerHud() { }

    private static final int PING_COLOR = 0xFFFFD83D; // gold, distinct from allegiances

    private record EdgeEntry(String label, int color, double dist) { }

    public static void render(GuiGraphicsExtractor ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.options.hideGui) return;

        var cfg = OpenIntelClient.config();
        String myDim = client.level.dimension().identifier().toString();

        int w = ctx.guiWidth();
        int h = ctx.guiHeight();
        float cx = w / 2f, cy = h / 2f;

        // Vertical focal length from the FOV option. (Dynamic FOV effects like
        // sprinting shift this slightly; close enough for marker placement.)
        float fovDeg = client.options.fov().get();
        float focal = (h / 2f) / (float) Math.tan(Math.toRadians(fovDeg) / 2.0);

        var camera = client.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        Quaternionf worldToCam = new Quaternionf(camera.rotation()).conjugate();

        List<EdgeEntry> left = new ArrayList<>();
        List<EdgeEntry> right = new ArrayList<>();

        for (RemotePlayer p : OpenIntelClient.tracker().all()) {
            if (p.dimension == null || !p.dimension.equals(myDim)) continue;

            double dx = p.x - camPos.x;
            double dy = (p.y + 2.4) - camPos.y; // anchor above the head
            double dz = p.z - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 2 || dist > cfg.maxMarkerDistance) continue;

            if (!cfg.markVisiblePlayers && client.level.players().stream()
                    .anyMatch(e -> e.getGameProfile().name().equals(p.name))) {
                continue;
            }

            boolean focused = p.allegiance == Allegiance.FOCUS;
            int color = p.allegiance.argb;
            String label = (focused ? "◆ " : "") + p.name + " " + (int) dist + "m";

            // World → camera-local space. Camera.setRotation builds the quaternion
            // as rotationYXZ(PI - yaw, -pitch, 0), i.e. the OpenGL view frame:
            // -Z forward, +X = view-right, +Y up. (Verified on 26.1.2 bytecode.)
            Vector3f rel = new Vector3f((float) dx, (float) dy, (float) dz);
            worldToCam.transform(rel);
            float fwd = -rel.z;

            boolean drawnOnScreen = false;
            if (fwd > 0.05f) {
                float sx = cx + (rel.x / fwd) * focal;
                float sy = cy - (rel.y / fwd) * focal;
                if (sx >= 0 && sx <= w && sy >= 0 && sy <= h) {
                    drawProjected(ctx, client, sx, sy, label, color, focused ? "◆" : "▼");
                    drawnOnScreen = true;
                }
            }

            if (!drawnOnScreen && cfg.edgeChevrons) {
                (rel.x >= 0 ? right : left).add(new EdgeEntry(label, color, dist));
            }
        }

        // Location pings: same projection, gold, ✖ glyph instead of a chevron.
        for (Ping ping : OpenIntelClient.pings().all()) {
            if (!ping.dimension.equals(myDim)) continue;

            double dx = ping.x - camPos.x;
            double dy = ping.y - camPos.y;
            double dz = ping.z - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            String label = ping.by + " " + (int) dist + "m";

            Vector3f rel = new Vector3f((float) dx, (float) dy, (float) dz);
            worldToCam.transform(rel);
            float fwd = -rel.z;

            boolean drawnOnScreen = false;
            if (fwd > 0.05f) {
                float sx = cx + (rel.x / fwd) * focal;
                float sy = cy - (rel.y / fwd) * focal;
                if (sx >= 0 && sx <= w && sy >= 0 && sy <= h) {
                    drawProjected(ctx, client, sx, sy, label, PING_COLOR, "✖");
                    drawnOnScreen = true;
                }
            }

            if (!drawnOnScreen && cfg.edgeChevrons) {
                (rel.x >= 0 ? right : left).add(new EdgeEntry("✖ " + label, PING_COLOR, dist));
            }
        }

        drawEdgeStack(ctx, client, left, false, w, cy);
        drawEdgeStack(ctx, client, right, true, w, cy);
    }

    /** Name over a glyph at the target's projected screen position, fixed 1x scale. */
    private static void drawProjected(GuiGraphicsExtractor ctx, Minecraft client,
                                      float sx, float sy, String label, int color,
                                      String glyph) {
        var font = client.font;
        int x = Math.round(sx), y = Math.round(sy);
        ctx.text(font, label, x - font.width(label) / 2, y - 19, color, true);
        ctx.text(font, glyph, x - font.width(glyph) / 2, y - 8, color, true);
    }

    /** Off-screen names stacked on one screen edge, vertically centered, nearest first. */
    private static void drawEdgeStack(GuiGraphicsExtractor ctx, Minecraft client,
                                      List<EdgeEntry> entries, boolean rightSide,
                                      int w, float cy) {
        if (entries.isEmpty()) return;
        entries.sort(Comparator.comparingDouble(EdgeEntry::dist));

        var font = client.font;
        int lineH = font.lineHeight + 2;
        int y = Math.round(cy) - (entries.size() * lineH) / 2;

        for (EdgeEntry e : entries) {
            String text = rightSide ? e.label() + " ▶" : "◀ " + e.label();
            int x = rightSide ? w - 4 - font.width(text) : 4;
            ctx.text(font, text, x, y, e.color(), true);
            y += lineH;
        }
    }
}
