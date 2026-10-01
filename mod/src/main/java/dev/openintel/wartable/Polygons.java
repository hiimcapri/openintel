package dev.openintel.wartable;

import java.util.ArrayList;
import java.util.List;

/**
 * Ear-clipping triangulation for filled AREA strokes. Input is a flat
 * [x0,y0, x1,y1, ...] polygon (may be concave); output is a triangle index
 * list into that array. Returns null for degenerate input (<3 vertices or
 * zero area) so callers can fall back to a line.
 */
public final class Polygons {
    private Polygons() { }

    public static int[] triangulate(float[] pts) {
        int n = pts.length / 2;
        if (n < 3) return null;
        List<Integer> verts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) verts.add(i);
        // Ensure CCW winding so the ear test uses a consistent sign.
        if (signedArea(pts) < 0) verts = verts.reversed();

        List<Integer> tris = new ArrayList<>(n * 3);
        int guard = n * n * 2;
        while (verts.size() > 3 && guard-- > 0) {
            int m = verts.size();
            boolean clipped = false;
            for (int i = 0; i < m; i++) {
                int a = verts.get((i - 1 + m) % m);
                int b = verts.get(i);
                int c = verts.get((i + 1) % m);
                if (!convex(pts, a, b, c)) continue;
                if (containsAny(pts, verts, a, b, c)) continue;
                tris.add(a); tris.add(b); tris.add(c);
                verts.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) break;   // sliver/degenerate — emit what we have
        }
        if (verts.size() == 3) {
            tris.add(verts.get(0)); tris.add(verts.get(1)); tris.add(verts.get(2));
        }
        return tris.isEmpty() ? null
                : tris.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Signed area ×2 — positive = CCW. */
    public static double signedArea(float[] pts) {
        int n = pts.length / 2;
        double a = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            a += (double) pts[i * 2] * pts[j * 2 + 1]
                    - (double) pts[j * 2] * pts[i * 2 + 1];
        }
        return a / 2.0;
    }

    private static boolean convex(float[] p, int a, int b, int c) {
        return cross(p, a, b, c) > 1e-9;
    }

    private static double cross(float[] p, int a, int b, int c) {
        return (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[b * 2 + 1])
                - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[b * 2]);
    }

    private static boolean containsAny(float[] p, List<Integer> verts, int a, int b, int c) {
        for (int v : verts) {
            if (v == a || v == b || v == c) continue;
            if (pointInTriangle(p, a, b, c, v)) return true;
        }
        return false;
    }

    private static boolean pointInTriangle(float[] p, int a, int b, int c, int v) {
        double d1 = cross(p, v, a, b);
        double d2 = cross(p, v, b, c);
        double d3 = cross(p, v, c, a);
        boolean neg = d1 < -1e-9 || d2 < -1e-9 || d3 < -1e-9;
        boolean pos = d1 > 1e-9 || d2 > 1e-9 || d3 > 1e-9;
        return !(neg && pos);
    }
}
