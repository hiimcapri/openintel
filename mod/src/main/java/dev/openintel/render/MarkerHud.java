package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.config.OIConfig;
import dev.openintel.mixin.DrawContextAccessor;
import dev.openintel.ping.PingManager;
import dev.openintel.tracker.Tracker.RemotePlayer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One renderer for every shared marker, drawn on the HUD layer:
 *
 *  - Target on screen   → name + a soft-edged vector chevron (diamond for
 *    focus targets / pings) at the projected position above the head.
 *  - Target off screen  → name on the nearest screen edge with a vector
 *    arrow pointing at it — all four edges: left/right for horizontal,
 *    top/bottom for vertical, bottom for behind-the-camera.
 *
 * All geometry is emitted as a single ColoredQuadsElement — triangle fans
 * with transparent corner vertices, so every shape has a real gradient
 * edge instead of a bitmap glyph. Text labels draw after the element so
 * they sit on top.
 *
 * Two multipliers ride on every alpha: `relayOpacity` (user slider) and a
 * staleness fade toward `staleAfterMs`, so aging intel visibly cools before
 * Tracker drops it.
 */
public final class MarkerHud {
    private MarkerHud() { }

    /** A shared thing to mark: relay player, ping, or snitch hit.
     *  kind: 0 = down-chevron (player), 1 = diamond (focus/ping), 2 = up-triangle (snitch). */
    private record Target(String label, int color, int kind,
                          double x, double y, double z) { }
    /** A soft vector shape in screen space. */
    private record Shape(float x, float y, int color, int kind) { }
    /** Edge arrowhead: dir 0=left, 1=right, 2=up, 3=down. */
    private record Arrow(float x, float y, int color, int dir) { }
    private record Label(float x, float y, String text, int color) { }
    private record Glyph(float x, float y, String text, int color) { }
    private record EdgeEntry(String label, int color, double dist) { }

    public static void render(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        if (!cfg.relayRendering) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.options.hudHidden) return;

        String myDim = client.world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();
        float opacity = cfg.relayOpacity / 255f;
        float tickDelta = client.getRenderTickCounter().getTickProgress(false);

        // ---- gather every target -------------------------------------------
        // Live handoff: when the subject is actually loaded, the marker anchors
        // to the entity's lerped position (frame-rate tracking) and the local
        // nameplate does the talking — relay position is only a fallback.
        var localPlayers = new java.util.HashMap<String, net.minecraft.client.network.AbstractClientPlayerEntity>();
        for (var e : client.world.getPlayers()) localPlayers.put(e.getGameProfile().name(), e);

        List<Target> targets = new ArrayList<>();
        for (RemotePlayer p : OpenIntelClient.tracker().all()) {
            if (p.dimension == null || !p.dimension.equals(myDim)) continue;
            var local = localPlayers.get(p.name);
            if (local != null && !cfg.markVisiblePlayers) continue;

            double tx, ty, tz;
            float alpha;
            if (local != null) {
                Vec3d lp = local.getLerpedPos(tickDelta);
                tx = lp.x; ty = lp.y + 2.4; tz = lp.z;
                alpha = opacity;                         // live: never stale
            } else {
                tx = p.x; ty = p.y + 2.4; tz = p.z;
                alpha = opacity * staleFade(cfg, now, p.lastSeen);
            }
            if (alpha < 0.03f) continue;
            Allegiance allegiance = p.allegiance != null ? p.allegiance : Allegiance.NEUTRAL;
            int color = scaleAlpha(allegiance.argb, alpha);
            targets.add(new Target(p.name, color, allegiance == Allegiance.FOCUS ? 1 : 0,
                    tx, ty, tz));
        }
        for (PingManager.Ping ping : PingManager.active()) {
            if (ping.dimension == null || !ping.dimension.equals(myDim)) continue;
            float life = Math.min(1f, (ping.expiresAt - now) / 4000f);
            targets.add(new Target("⚑ " + ping.label, scaleAlpha(ping.color, opacity * life),
                    1, ping.x, ping.y, ping.z));
        }
        // Snitch hits: "snitch | tripper | 42s", fading to nothing over
        // snitchMarkerSeconds. The live counter is free — we render per frame.
        long snitchLife = cfg.snitchMarkerSeconds * 1000L;
        for (var hit : OpenIntelClient.tracker().snitchHits()) {
            if (hit.dimension == null || !hit.dimension.equals(myDim)) continue;
            long age = now - hit.t;
            if (age < 0) age = 0;
            float fade = 1f - age / (float) snitchLife;
            if (fade <= 0.03f) continue;
            Allegiance a = OpenIntelClient.allegiances().of(hit.player);
            int argb = (cfg.snitchMarkerColorAuto || cfg.snitchMarkerColor == -1)
                    ? a.argb : cfg.snitchMarkerColor;
            targets.add(new Target(
                    hit.snitch + " | " + hit.player + " | " + ago(age),
                    scaleAlpha(argb, opacity * fade), 2,
                    hit.x, hit.y + 2.4, hit.z));
        }
        if (targets.isEmpty()) return;

        // ---- project + classify --------------------------------------------
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        float cx = w / 2f, cy = h / 2f;

        var camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getCameraPos();
        Quaternionf worldToCam = new Quaternionf(camera.getRotation()).conjugate();

        List<Shape> shapes = new ArrayList<>();
        List<Arrow> arrows = new ArrayList<>();
        List<Glyph> glyphs = new ArrayList<>();
        List<Label> projectedLabels = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        List<EdgeEntry> left = new ArrayList<>(), right = new ArrayList<>();
        List<EdgeEntry> top = new ArrayList<>(), bottom = new ArrayList<>();

        for (Target t : targets) {
            double dx = t.x - camPos.x, dy = t.y - camPos.y, dz = t.z - camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Range caps: <= 0 = unlimited — shared intel at any range still
            // lands on the edge as a direction marker.
            double cap = t.kind == 2 ? cfg.snitchMarkerRange : cfg.maxMarkerDistance;
            if (dist < 2 || (cap > 0 && dist > cap)) continue;

            // Camera-local frame (-Z forward) — only needed for front/behind.
            Vector3f rel = new Vector3f((float) dx, (float) dy, (float) dz);
            worldToCam.transform(rel);
            float fwd = -rel.z;

            // Full vanilla projection: real aspect + dynamic FOV (sprint,
            // speed effects, use-item zoom) → NDC in [-1,1].
            Vec3d ndc = client.gameRenderer.project(new Vec3d(t.x, t.y, t.z));
            float nx = (float) ndc.x, ny = (float) ndc.y;
            // Behind the camera the perspective divide mirrors NDC — un-mirror
            // so the edge still reads as "the direction you'd turn".
            if (fwd <= 0.02f) { nx = -nx; ny = -ny; }

            // Snitch labels already carry "snitch | player | age" — pipe the
            // distance in too so it doesn't blend into the timestamp.
            String text = t.label + (t.kind == 2 ? " | " : " ") + (int) dist + "m";

            if (fwd > 0.02f && Math.abs(nx) <= 1f && Math.abs(ny) <= 1f) {
                float sx = (nx * 0.5f + 0.5f) * w;
                float sy = (0.5f - ny * 0.5f) * h;
                if (t.kind == 2) {
                    // Snitch: ⚠ glyph floats above the name line.
                    glyphs.add(new Glyph(sx, sy - 4f, "⚠", t.color));
                    projectedLabels.add(new Label(sx, sy + 5f, text, t.color));
                } else {
                    shapes.add(new Shape(sx, sy, t.color, t.kind));
                    projectedLabels.add(new Label(sx, sy - 20, text, t.color));
                }
                continue;
            }

            // Off-screen: the border the center→target ray hits first, i.e.
            // whichever NDC axis overflows more. Directly-behind targets have
            // ~zero NDC magnitude, so fall back to camera-space dominance.
            if (cfg.edgeChevrons) {
                EdgeEntry e = new EdgeEntry(text, t.color, dist);
                if (Math.abs(nx) > 0.01f || Math.abs(ny) > 0.01f) {
                    if (Math.abs(nx) > Math.abs(ny)) {
                        (nx < 0 ? left : right).add(e);
                    } else {
                        (ny > 0 ? top : bottom).add(e);
                    }
                } else {
                    if (Math.abs(rel.x) > Math.abs(rel.y)) {
                        (rel.x < 0 ? left : right).add(e);
                    } else {
                        (rel.y > 0 ? top : bottom).add(e);
                    }
                }
            }
        }

        labels.addAll(stackProjectedLabels(client, projectedLabels, h));

        // ---- edge stacks ----------------------------------------------------
        // Anchors are configurable so the lists can be parked clear of other
        // HUD elements (edgeRowX/edgeColumnY in %, insets in px).
        float topAnchorX = w * cfg.edgeTopXPct / 100f;
        float bottomAnchorX = w * cfg.edgeBottomXPct / 100f;
        float leftAnchorY = h * cfg.edgeLeftYPct / 100f;
        float rightAnchorY = h * cfg.edgeRightYPct / 100f;

        queueVerticalEdge(client, left, false, cfg.edgeColumnInset, leftAnchorY, arrows, labels);
        queueVerticalEdge(client, right, true, w - cfg.edgeColumnInset, rightAnchorY, arrows, labels);
        // Top/bottom: one direction chevron at the screen edge, entries
        // stacked vertically inward — like the left/right columns.
        queueColumnEdge(client, top, 2, topAnchorX, cfg.edgeRowInset, true, arrows, labels);
        queueColumnEdge(client, bottom, 3, bottomAnchorX, h - cfg.edgeRowInset, false, arrows, labels);

        // ---- one geometry pass, then text on top ----------------------------
        if (!shapes.isEmpty() || !arrows.isEmpty()) {
            Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
            ((DrawContextAccessor) ctx).openintel$state().addSimpleElement(new ColoredQuadsElement(
                    pose, vc -> {
                        for (Shape s : shapes) emitShape(vc, pose, s);
                        for (Arrow a : arrows) emitArrow(vc, pose, a);
                    },
                    new ScreenRect(0, 0, w, h).transformEachVertex(pose)));
        }

        var tr = client.textRenderer;
        for (Glyph g : glyphs) {
            var pose = ctx.getMatrices();
            pose.pushMatrix();
            pose.translate(g.x, g.y);
            pose.scale(1.05f, 1.05f);
            ctx.drawCenteredTextWithShadow(tr, g.text, 0, -tr.fontHeight / 2, g.color);
            pose.popMatrix();
        }
        for (Label l : labels) {
            int tw = tr.getWidth(l.text);
            ctx.drawText(tr, l.text, Math.round(l.x - tw / 2f), Math.round(l.y), l.color, true);
        }
    }

    // ------------------------------------------------------ collection ----

    private static List<Label> stackProjectedLabels(MinecraftClient client,
                                                    List<Label> source, int screenHeight) {
        source.sort(Comparator.comparing((Label l) -> l.text)
                .thenComparingDouble(l -> l.x));
        List<Label> placed = new ArrayList<>();
        int lineH = client.textRenderer.fontHeight + 2;
        float maxY = screenHeight - client.textRenderer.fontHeight - 2;

        for (Label label : source) {
            Label chosen = null;
            for (int step = 0; step <= source.size() * 2; step++) {
                int level = step == 0 ? 0 : (step + 1) / 2;
                if (step > 0 && step % 2 == 0) level = -level;
                float y = label.y + level * lineH;
                if (y < 2 || y > maxY) continue;
                Label candidate = new Label(label.x, y, label.text, label.color);
                if (!overlaps(client, candidate, placed, lineH)) {
                    chosen = candidate;
                    break;
                }
            }
            placed.add(chosen != null ? chosen : label);
        }
        return placed;
    }

    private static boolean overlaps(MinecraftClient client, Label candidate,
                                    List<Label> placed, int lineH) {
        float half = client.textRenderer.getWidth(candidate.text) / 2f;
        float left = candidate.x - half - 2;
        float right = candidate.x + half + 2;
        for (Label other : placed) {
            float otherHalf = client.textRenderer.getWidth(other.text) / 2f;
            if (left < other.x + otherHalf + 2 && right > other.x - otherHalf - 2
                    && candidate.y < other.y + lineH && candidate.y + lineH > other.y) {
                return true;
            }
        }
        return false;
    }

    private static float staleFade(OIConfig cfg, long now, long lastSeen) {
        if (!cfg.staleDecay) return 1f;
        float age = (now - lastSeen) / (float) cfg.staleAfterMs;
        return Math.max(0f, 1f - age);
    }

    /** Left/right edge column: entries stacked vertically, nearest first. */
    private static void queueVerticalEdge(MinecraftClient client, List<EdgeEntry> entries,
                                          boolean rightSide, float arrowX, float anchorY,
                                          List<Arrow> arrows, List<Label> labels) {
        if (entries.isEmpty()) return;
        entries.sort(Comparator.comparingDouble(EdgeEntry::dist));

        var tr = client.textRenderer;
        int lineH = tr.fontHeight + 2;
        float y = anchorY - (entries.size() * lineH) / 2f;

        for (EdgeEntry e : entries) {
            float midY = y + lineH / 2f;
            int tw = tr.getWidth(e.label);
            if (rightSide) {
                labels.add(new Label(arrowX - 7f - tw / 2f, y, e.label, e.color));
            } else {
                labels.add(new Label(arrowX + 7f + tw / 2f, y, e.label, e.color));
            }
            arrows.add(new Arrow(arrowX, midY, e.color, rightSide ? 1 : 0));
            y += lineH;
        }
    }

    /**
     * Top/bottom edge column: one direction chevron at the screen edge
     * (nearest target's color), entries stacked vertically inward,
     * nearest first.
     */
    private static void queueColumnEdge(MinecraftClient client, List<EdgeEntry> entries,
                                        int dir, float cx, float arrowY, boolean inward,
                                        List<Arrow> arrows, List<Label> labels) {
        if (entries.isEmpty()) return;
        entries.sort(Comparator.comparingDouble(EdgeEntry::dist));

        var tr = client.textRenderer;
        int lineH = tr.fontHeight + 2;
        arrows.add(new Arrow(cx, arrowY, entries.get(0).color, dir));

        float y = inward ? arrowY + 6f : arrowY - 6f - entries.size() * lineH;
        for (EdgeEntry e : entries) {
            labels.add(new Label(cx, y, e.label, e.color));
            y += lineH;
        }
    }

    // -------------------------------------------------------- geometry ----

    /**
     * Soft shape = fan of triangles from a solid center to transparent
     * corners — every edge is a gradient. kind 0 = down-chevron (players),
     * kind 1 = diamond (focus/pings), kind 2 = up-triangle (snitch hits).
     */
    private static void emitShape(VertexConsumer vc, Matrix3x2fc pose, Shape s) {
        int c = s.color;
        int c0 = c & 0x00FFFFFF;
        float x = s.x, y = s.y;

        if (s.kind == 1) {
            float[][] corners = {{0, -5.5f}, {-3.4f, -1.6f}, {0, 2.2f}, {3.4f, -1.6f}};
            for (int i = 0; i < 4; i++) {
                float[] a = corners[i], b = corners[(i + 1) & 3];
                tri(vc, pose, x, y - 1.6f, c,
                        x + a[0], y + a[1], c0, x + b[0], y + b[1], c0);
            }
            tri(vc, pose, x, y - 3.6f, c, x - 1.9f, y - 1.6f, c, x, y + 0.4f, c);
            tri(vc, pose, x, y - 3.6f, c, x, y + 0.4f, c, x + 1.9f, y - 1.6f, c);
        } else {
            float cxp = x, cyp = y - 2.6f;
            tri(vc, pose, cxp, cyp, c, x, y + 1.6f, c0, x + 4.2f, y - 4.6f, c0);
            tri(vc, pose, cxp, cyp, c, x + 4.2f, y - 4.6f, c0, x - 4.2f, y - 4.6f, c0);
            tri(vc, pose, cxp, cyp, c, x - 4.2f, y - 4.6f, c0, x, y + 1.6f, c0);
            tri(vc, pose, x, y + 0.4f, c, x + 2.4f, y - 3.8f, c, x - 2.4f, y - 3.8f, c);
        }
    }

    /** Arrowhead on a screen edge, pointing outward. dir: 0=L 1=R 2=U 3=D. */
    private static void emitArrow(VertexConsumer vc, Matrix3x2fc pose, Arrow a) {
        int c = a.color;
        int c0 = c & 0x00FFFFFF;
        float x = a.x, y = a.y;

        float tipX, tipY, bX0, bY0, bX1, bY1;
        switch (a.dir) {
            case 0 -> { tipX = x - 4.5f; tipY = y; bX0 = x + 1.5f; bY0 = y - 3.6f; bX1 = x + 1.5f; bY1 = y + 3.6f; }
            case 2 -> { tipX = x; tipY = y - 4.5f; bX0 = x - 3.6f; bY0 = y + 1.5f; bX1 = x + 3.6f; bY1 = y + 1.5f; }
            case 3 -> { tipX = x; tipY = y + 4.5f; bX0 = x + 3.6f; bY0 = y - 1.5f; bX1 = x - 3.6f; bY1 = y - 1.5f; }
            default -> { tipX = x + 4.5f; tipY = y; bX0 = x - 1.5f; bY0 = y - 3.6f; bX1 = x - 1.5f; bY1 = y + 3.6f; }
        }

        // Fan: solid center → transparent corners (tip, baseA, baseB).
        tri(vc, pose, x, y, c, tipX, tipY, c0, bX0, bY0, c0);
        tri(vc, pose, x, y, c, bX0, bY0, c0, bX1, bY1, c0);
        tri(vc, pose, x, y, c, bX1, bY1, c0, tipX, tipY, c0);
        // Crisp core.
        tri(vc, pose, x + (tipX - x) * 0.55f, y + (tipY - y) * 0.55f, c,
                x + (bX0 - x) * 0.6f, y + (bY0 - y) * 0.6f, c,
                x + (bX1 - x) * 0.6f, y + (bY1 - y) * 0.6f, c);
    }

    /**
     * Degenerate quad triangle with auto-corrected winding — the GUI
     * pipeline culls backfaces, so we flip the last two verts when the
     * signed area says we're wound backwards.
     */
    private static void tri(VertexConsumer vc, Matrix3x2fc pose,
                            float x0, float y0, int c0,
                            float x1, float y1, int c1,
                            float x2, float y2, int c2) {
        float z = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        if (z >= 0) {
            float tx = x1; x1 = x2; x2 = tx;
            float ty = y1; y1 = y2; y2 = ty;
            int tc = c1; c1 = c2; c2 = tc;
        }
        vc.vertex(pose, x0, y0).color(c0);
        vc.vertex(pose, x1, y1).color(c1);
        vc.vertex(pose, x2, y2).color(c2);
        vc.vertex(pose, x2, y2).color(c2);
    }

    /** Live "time since" for snitch labels: 8s, 47s, 1m 05s, 2m+ gone by fade. */
    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 10) return "now";
        if (s < 60) return s + "s";
        return (s / 60) + "m " + String.format("%02d", s % 60) + "s";
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.max(0, Math.round(((argb >>> 24) & 0xFF) * f)));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
