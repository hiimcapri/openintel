package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.tracker.Tracker.RemotePlayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
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
 * The camera rotation + FOV are captured once per frame from the world render
 * pass so HUD projection matches the actual view.
 */
public final class MarkerHud {
    private MarkerHud() { }

    // Captured each frame from the world render pass.
    private static volatile Vec3d camPos = Vec3d.ZERO;
    private static final Quaternionf camRot = new Quaternionf();
    private static volatile boolean captured = false;

    private record EdgeEntry(String label, int color, double dist) { }

    public static void captureCamera(WorldRenderContext ctx) {
        camPos = ctx.camera().getPos();
        synchronized (camRot) {
            camRot.set(ctx.camera().getRotation());
        }
        captured = true;
    }

    public static void render(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!captured || client.player == null || client.world == null || client.options.hudHidden) return;

        var cfg = OpenIntelClient.config();
        String myDim = client.world.getRegistryKey().getValue().toString();

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        float cx = w / 2f, cy = h / 2f;

        // Vertical focal length from the FOV option. (Dynamic FOV effects like
        // sprinting shift this slightly; close enough for marker placement.)
        float fovDeg = client.options.getFov().getValue();
        float focal = (h / 2f) / (float) Math.tan(Math.toRadians(fovDeg) / 2.0);

        Quaternionf worldToCam;
        synchronized (camRot) {
            worldToCam = new Quaternionf(camRot).conjugate();
        }

        List<EdgeEntry> left = new ArrayList<>();
        List<EdgeEntry> right = new ArrayList<>();

        for (RemotePlayer p : OpenIntelClient.tracker().all()) {
            if (p.dimension == null || !p.dimension.equals(myDim)) continue;

            double dx = p.x - camPos.x;
            double dy = (p.y + 2.4) - camPos.y; // anchor above the head
            double dz = p.z - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 2 || dist > cfg.maxMarkerDistance) continue;

            if (!cfg.markVisiblePlayers && client.world.getPlayers().stream()
                    .anyMatch(e -> e.getGameProfile().getName().equals(p.name))) {
                continue;
            }

            boolean focused = p.allegiance == Allegiance.FOCUS;
            int color = p.allegiance.argb;
            String label = (focused ? "◆ " : "") + p.name + " " + (int) dist + "m";

            // World → camera-local space. Camera.setRotation builds the quaternion
            // as rotationYXZ(PI - yaw, -pitch, 0), i.e. the OpenGL view frame:
            // -Z forward, +X = view-right, +Y up.
            Vector3f rel = new Vector3f((float) dx, (float) dy, (float) dz);
            worldToCam.transform(rel);
            float fwd = -rel.z;

            boolean drawnOnScreen = false;
            if (fwd > 0.05f) {
                float sx = cx + (rel.x / fwd) * focal;
                float sy = cy - (rel.y / fwd) * focal;
                if (sx >= 0 && sx <= w && sy >= 0 && sy <= h) {
                    drawProjected(ctx, client, sx, sy, label, color, focused);
                    drawnOnScreen = true;
                }
            }

            if (!drawnOnScreen && cfg.edgeChevrons) {
                (rel.x >= 0 ? right : left).add(new EdgeEntry(label, color, dist));
            }
        }

        drawEdgeStack(ctx, client, left, false, w, cy);
        drawEdgeStack(ctx, client, right, true, w, cy);
    }

    /** Name over chevron at the target's projected screen position, fixed 1x scale. */
    private static void drawProjected(DrawContext ctx, MinecraftClient client,
                                      float sx, float sy, String label, int color,
                                      boolean focused) {
        var tr = client.textRenderer;
        int x = Math.round(sx), y = Math.round(sy);
        ctx.drawText(tr, label, x - tr.getWidth(label) / 2, y - 19, color, true);
        String chev = focused ? "◆" : "▼";
        ctx.drawText(tr, chev, x - tr.getWidth(chev) / 2, y - 8, color, true);
    }

    /** Off-screen names stacked on one screen edge, vertically centered, nearest first. */
    private static void drawEdgeStack(DrawContext ctx, MinecraftClient client,
                                      List<EdgeEntry> entries, boolean rightSide,
                                      int w, float cy) {
        if (entries.isEmpty()) return;
        entries.sort(Comparator.comparingDouble(EdgeEntry::dist));

        var tr = client.textRenderer;
        int lineH = tr.fontHeight + 2;
        int y = Math.round(cy) - (entries.size() * lineH) / 2;

        for (EdgeEntry e : entries) {
            String text = rightSide ? e.label() + " ▶" : "◀ " + e.label();
            int x = rightSide ? w - 4 - tr.getWidth(text) : 4;
            ctx.drawText(tr, text, x, y, e.color(), true);
            y += lineH;
        }
    }
}
