package dev.openintel.wartable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One annotation on the War Table. Points are world X/Z pairs stored flat
 * ([x0,z0, x1,z1, ...]) so the shape is independent of the map image —
 * re-rendered tiles just re-project it.
 *
 * Tools:
 *   PEN    freehand polyline (open)
 *   LINE   straight segment; optional arrowhead at the far end
 *   AREA   closed filled polygon
 *   LABEL  text at a point
 *   MARKER small pin at a point (circle + optional label)
 *
 * Wire format (inside a {"type":"wartable"} envelope):
 *   { id, tool, color, width, points:[x,z,...], label?, from, t }
 */
public final class Stroke {

    public enum Tool {
        PEN, LINE, ARROW, AREA, LABEL, MARKER;

        public static Tool byName(String name) {
            if (name == null) return null;
            try { return valueOf(name.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    public static final int MAX_POINTS = 512;          // x/z pairs
    public static final int MAX_LABEL = 96;
    public static final double MAX_COORD = 1_000_000;  // far lands are a no
    public static final float MIN_WIDTH = 1f, MAX_WIDTH = 64f;

    public final String id;
    public final Tool tool;
    public final int color;            // ARGB
    public final float width;          // stroke thickness in world blocks
    public final float[] points;       // world x,z pairs
    public final String label;         // may be null
    public final String author;        // relay-stamped sender name
    public final long t;

    public Stroke(String id, Tool tool, int color, float width, float[] points,
                  String label, String author, long t) {
        this.id = id;
        this.tool = tool;
        this.color = color;
        this.width = width;
        this.points = points;
        this.label = label;
        this.author = author;
        this.t = t;
    }

    // ------------------------------------------------------------ codec ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("tool", tool.name().toLowerCase(Locale.ROOT));
        o.addProperty("color", color);
        o.addProperty("width", width);
        JsonArray pts = new JsonArray();
        for (float v : points) pts.add(v);
        o.add("points", pts);
        if (label != null) o.addProperty("label", label);
        if (author != null) o.addProperty("from", author);
        if (t > 0) o.addProperty("t", t);
        return o;
    }

    /** Parse + validate an inbound stroke; null if malformed. */
    public static Stroke fromJson(JsonObject o) {
        Stroke s = tryFromJson(o);
        return s != null && validate(s) ? s : null;
    }

    private static Stroke tryFromJson(JsonObject o) {
        try {
            if (o == null || !o.has("id") || !o.has("tool") || !o.has("points")) return null;
            String id = o.get("id").getAsString();
            Tool tool = Tool.byName(o.get("tool").getAsString());
            if (tool == null) return null;
            int color = o.has("color") ? o.get("color").getAsInt() : 0xFFFF5555;
            float width = o.has("width") ? o.get("width").getAsFloat() : 4f;
            JsonArray pts = o.getAsJsonArray("points");
            if (pts == null || pts.size() % 2 != 0) return null;
            float[] points = new float[pts.size()];
            for (int i = 0; i < pts.size(); i++) points[i] = pts.get(i).getAsFloat();
            String label = o.has("label") && !o.get("label").isJsonNull()
                    ? o.get("label").getAsString() : null;
            String author = o.has("from") && !o.get("from").isJsonNull()
                    ? o.get("from").getAsString()
                    : (o.has("author") ? o.get("author").getAsString() : null);
            long t = o.has("t") ? o.get("t").getAsLong() : 0;
            return new Stroke(id, tool, color, width, points, label, author, t);
        } catch (Exception e) {
            return null;
        }
    }

    /** Bounds/content check shared by client and relay semantics. */
    public static boolean validate(Stroke s) {
        if (s == null || s.id == null || s.tool == null || s.points == null) return false;
        if (!s.id.matches("[A-Za-z0-9_\\-]{4,64}")) return false;
        int pairs = s.points.length / 2;
        int minPairs = switch (s.tool) {
            case PEN, AREA -> 2;
            case LINE, ARROW -> 2;
            case LABEL, MARKER -> 1;
        };
        if (pairs < minPairs || pairs > MAX_POINTS) return false;
        for (float v : s.points) {
            if (!Float.isFinite(v) || Math.abs(v) > MAX_COORD) return false;
        }
        if (s.width < MIN_WIDTH || s.width > MAX_WIDTH || !Float.isFinite(s.width)) return false;
        if (s.label != null && (s.label.isEmpty() || s.label.length() > MAX_LABEL)) return false;
        if ((s.tool == Tool.LABEL) && s.label == null) return false;
        return true;
    }

    /** All (x,z) points as a list — handy for rendering/hit-testing. */
    public List<double[]> pointList() {
        List<double[]> out = new ArrayList<>(points.length / 2);
        for (int i = 0; i + 1 < points.length; i += 2) out.add(new double[]{points[i], points[i + 1]});
        return out;
    }

    /**
     * Drop vertices closer than `minDist` world blocks to their predecessor.
     * Keeps pen input from producing thousands of near-identical points
     * while held still.
     */
    public static float[] decimate(float[] pts, double minDist) {
        if (pts.length < 6) return pts;
        double minSq = minDist * minDist;
        List<Float> kept = new ArrayList<>(pts.length);
        kept.add(pts[0]); kept.add(pts[1]);
        for (int i = 2; i + 1 < pts.length; i += 2) {
            double dx = pts[i] - kept.get(kept.size() - 2);
            double dz = pts[i + 1] - kept.get(kept.size() - 1);
            if (dx * dx + dz * dz >= minSq) { kept.add(pts[i]); kept.add(pts[i + 1]); }
        }
        // Always keep the release point so the stroke ends where the mouse did.
        int n = pts.length;
        int lastKept = kept.size() - 2;
        if (kept.get(lastKept) != pts[n - 2] || kept.get(lastKept + 1) != pts[n - 1]) {
            kept.add(pts[n - 2]); kept.add(pts[n - 1]);
        }
        float[] out = new float[kept.size()];
        for (int i = 0; i < out.length; i++) out[i] = kept.get(i);
        return out;
    }

    /**
     * Distance from point to the stroke's nearest segment (or vertex for
     * point-ish tools). Used by the eraser. Returns +∞ when empty.
     */
    public double distanceTo(double wx, double wz) {
        if (points.length < 2) return Double.POSITIVE_INFINITY;
        if (points.length == 2 || tool == Tool.LABEL || tool == Tool.MARKER) {
            return Math.hypot(points[0] - wx, points[1] - wz);
        }
        int pairs = points.length / 2;
        boolean closed = tool == Tool.AREA;
        double best = Double.POSITIVE_INFINITY;
        int segments = closed ? pairs : pairs - 1;
        for (int i = 0; i < segments; i++) {
            int a = i * 2, b = ((i + 1) % pairs) * 2;
            best = Math.min(best, segmentDist(wx, wz,
                    points[a], points[a + 1], points[b], points[b + 1]));
        }
        return best;
    }

    private static double segmentDist(double px, double py,
                                      double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        double t = lenSq == 0 ? 0 : Math.max(0, Math.min(1,
                ((px - ax) * dx + (py - ay) * dy) / lenSq));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
