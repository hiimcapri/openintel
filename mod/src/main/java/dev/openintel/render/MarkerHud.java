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
    private record Target(String key, String label, int color, int kind,
                          double x, double y, double z) { }
    /** A soft vector shape in screen space. */
    private record Shape(float x, float y, int color, int kind) { }
    /** Edge arrowhead: dir 0=left, 1=right, 2=up, 3=down. */
    private record Arrow(float x, float y, int color, int dir) { }
    /** scale: user marker-scale for over-head labels; edge labels stay 1f. */
    private record Label(float x, float y, String text, int color, float scale, String key) {
        private Label(float x, float y, String text, int color, float scale) {
            this(x, y, text, color, scale, text);
        }
    }
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
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        if (w <= 0 || h <= 0) return;
        if (w != lastViewportWidth || h != lastViewportHeight) {
            renderY.clear();
            lastViewportWidth = w;
            lastViewportHeight = h;
        }
        float hudScale = HudLayouts.scale(cfg, w, h);
        float edgeScale = Math.min(hudScale, Math.min(w / 10f, h / 10f));
        float scale = cfg.markerScale * hudScale;
        int lineH = client.textRenderer.fontHeight + 2;
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
            targets.add(new Target("player|" + myDim + "|" + p.name, p.name, color, allegiance == Allegiance.FOCUS ? 1 : 0,
                    tx, ty, tz));
        }
        for (PingManager.Ping ping : PingManager.active()) {
            if (ping.dimension == null || !ping.dimension.equals(myDim)) continue;
            float life = Math.min(1f, (ping.expiresAt - now) / 4000f);
            targets.add(new Target("ping|" + myDim + "|" + ping.id, "⚑ " + ping.label, scaleAlpha(ping.color, opacity * life),
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
                    "snitch|" + myDim + "|" + hit.x + "|" + hit.y + "|" + hit.z + "|" + hit.player,
                    hit.snitch + " | " + hit.player + " | " + ago(age),
                    scaleAlpha(argb, opacity * fade), 2,
                    hit.x, hit.y + 2.4, hit.z));
        }
        // Relic points: X/Z only, so the diamond rides at eye level —
        // close enough to read, far ones live on the edge as direction.
        for (var r : dev.openintel.relic.RelicMaps.all()) {
            if (!r.dimension().equals(myDim)) continue;
            targets.add(new Target("relic|" + myDim + "|" + r.x() + "|" + r.z(), r.name(),
                    scaleAlpha(0xFFFFAA00, opacity), 1,
                    r.x() + 0.5, client.player.getY() + 1.5, r.z() + 0.5));
        }
        if (targets.isEmpty()) {
            renderY.clear();
            return;
        }

        // ---- project + classify --------------------------------------------
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
                    glyphs.add(new Glyph(sx, sy - 4f * scale, "⚠", t.color));
                    projectedLabels.add(new Label(sx, sy + 5f * scale, text, t.color, scale, t.key));
                } else {
                    shapes.add(new Shape(sx, sy, t.color, t.kind));
                    projectedLabels.add(new Label(sx, sy - (lineH + 9f) * scale, text, t.color, scale, t.key));
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

        labels.addAll(stackProjectedLabels(client, projectedLabels, scale));

        // ---- edge stacks ----------------------------------------------------
        // Anchors are configurable so the lists can be parked clear of other
        // HUD elements (edgeRowX/edgeColumnY in %, insets in px).
        float topAnchorX = w * cfg.edgeTopXPct / 100f;
        float bottomAnchorX = w * cfg.edgeBottomXPct / 100f;
        float leftAnchorY = h * cfg.edgeLeftYPct / 100f;
        float rightAnchorY = h * cfg.edgeRightYPct / 100f;

        float columnInset = Math.clamp(cfg.edgeColumnInset * edgeScale, 5 * edgeScale, w / 2f);
        float rowInset = Math.clamp(cfg.edgeRowInset * edgeScale, 5 * edgeScale, h / 2f);
        queueVerticalEdge(client, left, false, columnInset, leftAnchorY, arrows, labels, edgeScale, w, h);
        queueVerticalEdge(client, right, true, w - columnInset, rightAnchorY, arrows, labels, edgeScale, w, h);
        // Top/bottom: one direction chevron at the screen edge, entries
        // stacked vertically inward — like the left/right columns.
        queueColumnEdge(client, top, 2, topAnchorX, rowInset, true, arrows, labels, edgeScale, w, h);
        queueColumnEdge(client, bottom, 3, bottomAnchorX, h - rowInset, false, arrows, labels, edgeScale, w, h);

        // ---- one geometry pass, then text on top ----------------------------
        if (!shapes.isEmpty() || !arrows.isEmpty()) {
            Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
            ((DrawContextAccessor) ctx).openintel$state().addSimpleElement(new ColoredQuadsElement(
                    pose, vc -> {
                        for (Shape s : shapes) {
                            // Scale around each marker's own anchor — edges
                            // (arrows) stay at the user's HUD size.
                            Matrix3x2f sp = new Matrix3x2f(pose)
                                    .translate(s.x, s.y).scale(scale, scale)
                                    .translate(-s.x, -s.y);
                            emitShape(vc, sp, s);
                        }
                        for (Arrow a : arrows) {
                            Matrix3x2f ap = new Matrix3x2f(pose).translate(a.x, a.y).scale(edgeScale, edgeScale).translate(-a.x, -a.y);
                            emitArrow(vc, ap, a);
                        }
                    },
                    new ScreenRect(0, 0, w, h).transformEachVertex(pose)));
        }

        var tr = client.textRenderer;
        boolean cf = cleanFont();
        for (Glyph g : glyphs) {
            var pose = ctx.getMatrices();
            pose.pushMatrix();
            pose.translate(g.x, g.y);
            pose.scale(1.05f * scale, 1.05f * scale);
            if (cf) CleanFont.drawCentered(ctx, g.text, 0, -tr.fontHeight / 2, g.color);
            else ctx.drawCenteredTextWithShadow(tr, g.text, 0, -tr.fontHeight / 2, g.color);
            pose.popMatrix();
        }
        for (Label l : labels) {
            if (l.text.isEmpty()) continue;
            float tw = cf ? CleanFont.width(l.text) : tr.getWidth(l.text);
            float fitted = Math.min(l.scale, Math.min(w / Math.max(1f, tw), h / (float) tr.fontHeight));
            if (!(fitted > 0)) continue;
            float half = tw * fitted / 2;
            float x = half + Math.clamp(l.x - half, 0, Math.max(0, w - tw * fitted));
            float y = Math.clamp(l.y, 0, Math.max(0, h - tr.fontHeight * fitted));
            var pose = ctx.getMatrices();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(fitted, fitted);
            if (cf) CleanFont.draw(ctx, l.text, -tw / 2f, 0, l.color, true);
            else ctx.drawText(tr, l.text, Math.round(-tw / 2f), 0, l.color, true);
            pose.popMatrix();
        }
    }

    // ------------------------------------------------------ collection ----

    /** Rendered y per label text — glides toward the resolved target. */
    private static final java.util.Map<String, Float> renderY = new java.util.HashMap<>();
    private static int lastViewportWidth;
    private static int lastViewportHeight;

    private static List<Label> stackProjectedLabels(MinecraftClient client,
                                                    List<Label> source, float scale) {
        source.sort(Comparator.comparing((Label l) -> l.key)
                .thenComparingDouble(l -> l.x));
        List<Label> resolved = new ArrayList<>();
        List<Label> placed = new ArrayList<>();
        float lineH = (client.textRenderer.fontHeight + 2) * scale;
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (Label label : source) {
            seen.add(label.key);
            // Continuous upward stacking: sit just above whatever overlaps,
            // never below the anchor. The target tracks the blocker's own
            // position, so it slides smoothly instead of popping between
            // discrete levels.
            float y = label.y;
            for (int iter = 0; iter <= resolved.size(); iter++) {
                Label b = blocker(client, new Label(label.x, y, label.text, label.color, scale, label.key),
                        resolved, lineH, scale);
                if (b == null) break;
                y = b.y - lineH - 0.5f;
            }
            if (y < 2) y = label.y;
            resolved.add(new Label(label.x, y, label.text, label.color, scale, label.key));

            float ry = smoothY(label, y);
            placed.add(new Label(label.x, ry, label.text, label.color, scale, label.key));
        }
        renderY.keySet().retainAll(seen);
        return placed;
    }

    /** Config-aware text renderer check for clean vs vanilla fonts. */
    private static boolean cleanFont() {
        return CleanFont.active();
    }

    private static float smoothY(Label label, float y) {
        Float prev = renderY.get(label.key);
        float ry = prev != null ? prev + (y - prev) * 0.4f : y;
        renderY.put(label.key, ry);
        return ry;
    }

    private static float textW(MinecraftClient client, String s) {
        return cleanFont() ? CleanFont.width(s) : client.textRenderer.getWidth(s);
    }

    /** The highest already-placed label the candidate collides with, or null. */
    private static Label blocker(MinecraftClient client, Label candidate,
                                 List<Label> placed, float lineH, float scale) {
        float half = textW(client, candidate.text) * scale / 2f;
        float left = candidate.x - half - 2;
        float right = candidate.x + half + 2;
        Label top = null;
        for (Label other : placed) {
            float otherHalf = textW(client, other.text) * scale / 2f;
            if (left < other.x + otherHalf + 2 && right > other.x - otherHalf - 2
                    && candidate.y < other.y + lineH && candidate.y + lineH > other.y
                    && (top == null || other.y < top.y)) {
                top = other;
            }
        }
        return top;
    }

    private static float staleFade(OIConfig cfg, long now, long lastSeen) {
        if (!cfg.staleDecay) return 1f;
        float age = (now - lastSeen) / (float) cfg.staleAfterMs;
        return Math.max(0f, 1f - age);
    }

    /** Left/right edge column: entries stacked vertically, nearest first. */
    private static void queueVerticalEdge(MinecraftClient client, List<EdgeEntry> entries,
                                          boolean rightSide, float arrowX, float anchorY,
                                          List<Arrow> arrows, List<Label> labels, float scale, int width, int height) {
        if (entries.isEmpty() || !(scale > 0)) return;
        entries.sort(Comparator.comparingDouble(EdgeEntry::dist));
        float lineH = (client.textRenderer.fontHeight + 2) * scale;
        var visible = edgeRows(entries, Math.max(1, (int) (height / lineH)));
        float y = Math.clamp(anchorY - visible.size() * lineH / 2f, 0, Math.max(0, height - visible.size() * lineH));
        float available = Math.max(0, (rightSide ? arrowX : width - arrowX) - 7 * scale);
        for (EdgeEntry e : visible) {
            float midY = y + lineH / 2f;
            String text = HudLayout.ellipsize(e.label, available / scale, s -> textW(client, s));
            float tw = textW(client, text) * scale;
            float x = rightSide ? arrowX - 7 * scale - tw / 2 : arrowX + 7 * scale + tw / 2;
            labels.add(new Label(x, y, text, e.color, scale));
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
                                        List<Arrow> arrows, List<Label> labels, float scale, int width, int height) {
        if (entries.isEmpty() || !(scale > 0)) return;
        entries.sort(Comparator.comparingDouble(EdgeEntry::dist));
        float lineH = (client.textRenderer.fontHeight + 2) * scale;
        float available = Math.max(0, (inward ? height - arrowY : arrowY) - 6 * scale);
        var visible = edgeRows(entries, Math.max(1, (int) (available / lineH)));
        cx = Math.clamp(cx, 5 * scale, width - 5 * scale);
        arrows.add(new Arrow(cx, arrowY, entries.get(0).color, dir));
        float y = inward ? arrowY + 6 * scale : arrowY - 6 * scale - visible.size() * lineH;
        y = Math.clamp(y, 0, Math.max(0, height - visible.size() * lineH));
        for (EdgeEntry e : visible) {
            String text = HudLayout.ellipsize(e.label, Math.max(0, width / scale - 2), s -> textW(client, s));
            labels.add(new Label(cx, y, text, e.color, scale));
            y += lineH;
        }
    }

    private static List<EdgeEntry> edgeRows(List<EdgeEntry> entries, int capacity) {
        capacity = Math.clamp(capacity, 1, 256);
        if (entries.size() <= capacity) return entries;
        var visible = new ArrayList<>(entries.subList(0, capacity - 1));
        visible.add(new EdgeEntry("+" + (entries.size() - visible.size()) + " more",
                entries.get(0).color, entries.get(0).dist));
        return visible;
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
