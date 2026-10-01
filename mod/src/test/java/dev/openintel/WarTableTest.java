package dev.openintel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openintel.wartable.MapProjection;
import dev.openintel.wartable.Polygons;
import dev.openintel.wartable.Sanctuaries;
import dev.openintel.wartable.Stroke;
import dev.openintel.wartable.WarTable;

import java.util.Map;
import java.util.Objects;

/**
 * Standalone checks for the War Table math and wire codec — no Minecraft
 * runtime needed: projection transforms, stroke JSON round-trips, stroke
 * validation bounds, point decimation, and polygon triangulation.
 */
public final class WarTableTest {

    private static final String META_JSON = """
            {
              "topLeft": {"x": -3403, "z": -3403},
              "bottomRight": {"x": 3402, "z": 3402},
              "imageSize": {"width": 6806, "height": 6806},
              "scale": 1
            }""";

    public static void main(String[] args) throws Exception {
        MapProjection p = MapProjection.fromJson(JsonParser.parseString(META_JSON).getAsJsonObject());
        if (p == null) throw new AssertionError("failed to parse valid metadata");

        // Corner round-trips: world bounds land on the image edges.
        close(0, p.worldToPxX(-3403));
        close(0, p.worldToPxZ(-3403));
        close(6805, p.worldToPxX(3402));
        close(-3403, p.pxToWorldX(0));
        close(3402, p.pxToWorldX(6805));
        // Round-trip an arbitrary point.
        double wx = 123.4, wz = -987.6;
        close(wx, p.pxToWorldX(p.worldToPxX(wx)));
        close(wz, p.pxToWorldZ(p.worldToPxZ(wz)));

        // Bad metadata is rejected, not silently accepted.
        equal(null, MapProjection.fromJson(null));
        equal(null, MapProjection.fromJson(new JsonObject()));
        JsonObject bogus = JsonParser.parseString(META_JSON).getAsJsonObject();
        bogus.getAsJsonObject("imageSize").addProperty("width", 5);
        equal(null, MapProjection.fromJson(bogus));

        // Stroke codec round-trip.
        Stroke s = new Stroke("abc123-x", Stroke.Tool.AREA, 0xFFFF5555, 4f,
                new float[]{0, 0, 100, 0, 100, 100, 0, 100}, "north wall", "ExamplePlayer", 12345);
        Stroke back = Stroke.fromJson(s.toJson());
        if (back == null) throw new AssertionError("valid stroke failed to parse");
        equal(s.id, back.id);
        equal(s.tool, back.tool);
        equal(s.label, back.label);
        equal(s.author, back.author);
        equal(s.points.length, back.points.length);
        close(s.points[3], back.points[3]);

        // Validation rejects malformed input.
        equal(false, Stroke.validate(null));
        equal(false, Stroke.validate(new Stroke("x", Stroke.Tool.PEN, 0, 4f,
                new float[]{0, 0, 1, 1}, null, "a", 0)));                    // id too short
        equal(false, Stroke.validate(new Stroke("valid-id-1", Stroke.Tool.PEN, 0, 4f,
                new float[]{0, 0, Float.NaN, 1}, null, "a", 0)));           // NaN point
        equal(false, Stroke.validate(new Stroke("valid-id-2", Stroke.Tool.LABEL, 0, 4f,
                new float[]{0, 0}, null, "a", 0)));                          // label missing text
        equal(false, Stroke.validate(new Stroke("valid-id-3", Stroke.Tool.LINE, 0, 999f,
                new float[]{0, 0, 1, 1}, null, "a", 0)));                    // width over cap
        equal(true, Stroke.validate(s));

        // Decimation collapses dense sampling but keeps endpoints.
        float[] dense = new float[]{0, 0, 0.1f, 0.1f, 0.2f, 0.2f, 50, 50};
        float[] dec = Stroke.decimate(dense, 2.0);
        if (dec.length != 4) throw new AssertionError("decimate kept " + dec.length);
        close(50, dec[2]);

        // distanceTo: point tools measure to their anchor, lines to segments.
        Stroke marker = new Stroke("m1-id", Stroke.Tool.MARKER, 0, 4f,
                new float[]{10, 10}, null, "a", 0);
        close(5, marker.distanceTo(15, 10));
        Stroke waypoint = new Stroke("wp1-id", Stroke.Tool.MARKER, 0xFFFFAA00, 4f,
                new float[]{10, 10}, "regroup", "ExamplePlayer", 0);
        equal("regroup", Stroke.fromJson(waypoint.toJson()).label);
        equal("TDRC", Sanctuaries.acronym("The Dreccanfell Riding Club"));
        equal("Sanctuary", Sanctuaries.cleanName("§bSanctuary"));
        Stroke line = new Stroke("l1-id", Stroke.Tool.LINE, 0, 4f,
                new float[]{0, 0, 100, 0}, null, "a", 0);
        close(10, line.distanceTo(50, 10));
        close(10, line.distanceTo(50, -10));

        // Triangulation: convex quad → 2 triangles; concave L-shape too.
        int[] quad = Polygons.triangulate(new float[]{0, 0, 10, 0, 10, 10, 0, 10});
        if (quad == null || quad.length != 6) throw new AssertionError("quad triangulation failed");
        int[] concave = Polygons.triangulate(
                new float[]{0, 0, 10, 0, 10, 10, 5, 5, 0, 10});
        if (concave == null || concave.length != 9) throw new AssertionError("concave triangulation failed");
        equal(null, Polygons.triangulate(new float[]{0, 0, 5, 5}));           // <3 points
        // Signed area detects winding.
        if (Polygons.signedArea(new float[]{0, 0, 10, 0, 10, 10, 0, 10}) < 0)
            throw new AssertionError("CCW quad should have positive area");

        verifyLocalLayer();
        verifyDraftRendering(p);
        System.out.println("War table tests passed");
    }

    private static void verifyLocalLayer() throws Exception {
        // The standalone test starts disconnected, which is local-edit mode.
        equal(false, WarTable.live());
        equal(true, WarTable.canDraw());

        Map<String, Stroke> shared = strokeMap("strokes");
        Map<String, Stroke> local = strokeMap("local");
        Map<String, Stroke> pending = strokeMap("pending");
        shared.clear();
        local.clear();
        pending.clear();

        Stroke cached = new Stroke("cached-shared", Stroke.Tool.PEN, 0xFFFF5555, 4f,
                new float[]{0, 0, 10, 0}, null, "relay", 1);
        Stroke localPin = new Stroke("local-pin", Stroke.Tool.MARKER, 0xFFFFAA00, 4f,
                new float[]{20, 20}, "local pin", "local", 2);
        Stroke remote = new Stroke("remote-synced", Stroke.Tool.LINE, 0xFF55FFFF, 4f,
                new float[]{30, 30, 40, 40}, null, "relay", 3);
        shared.put(cached.id, cached);
        local.put(localPin.id, localPin);

        JsonObject sync = new JsonObject();
        sync.add("strokes", new com.google.gson.JsonArray());
        sync.getAsJsonArray("strokes").add(remote.toJson());
        WarTable.applySync(sync);

        if (!hasStroke(WarTable.strokes(), remote.id))
            throw new AssertionError("authoritative sync did not replace shared strokes");
        if (hasStroke(WarTable.strokes(), cached.id))
            throw new AssertionError("cached shared stroke survived sync");
        if (!hasStroke(WarTable.strokes(), localPin.id))
            throw new AssertionError("local stroke was lost during relay sync");
        equal(1, WarTable.waypointStrokes().size());
        equal(localPin.id, WarTable.waypointStrokes().iterator().next().id);

        shared.clear();
        local.clear();
        pending.clear();
    }

    private static boolean hasStroke(java.util.Collection<Stroke> strokes, String id) {
        for (Stroke stroke : strokes) if (stroke.id.equals(id)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Stroke> strokeMap(String name) throws Exception {
        var field = WarTable.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, Stroke>) field.get(null);
    }

    private static void verifyDraftRendering(MapProjection projection) throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var screen = (dev.openintel.wartable.WarTableScreen) unsafeClass
                .getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null),
                        dev.openintel.wartable.WarTableScreen.class);
        setField(screen, "zoom", 1.0);
        screen.width = 256;
        screen.height = 256;
        setField(screen, "centerPx", projection.worldToPxX(0));
        setField(screen, "centerPy", projection.worldToPxZ(0));
        var paint = screen.getClass().getDeclaredMethod("paintStrokeG", java.awt.Graphics2D.class,
                MapProjection.class, Stroke.class, boolean.class);
        paint.setAccessible(true);
        for (Stroke.Tool tool : Stroke.Tool.values()) {
            for (float[] points : new float[][]{ {}, {0, 0}, {0, 0, 0, 0},
                    {-60, 0, 60, 0}, {-60, 0, 60, 0, 60, 0} }) {
                var image = new java.awt.image.BufferedImage(256, 256,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics();
                try {
                    paint.invoke(screen, graphics, projection,
                            new Stroke("draft", tool, 0xFFFF5555, 4f, points, "test", null, 0), true);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw new AssertionError(tool + " preview failed with " + points.length / 2 + " points", e.getCause());
                } finally {
                    graphics.dispose();
                }
                if ((tool == Stroke.Tool.LINE || tool == Stroke.Tool.ARROW) && points.length >= 4
                        && points[0] != points[2] && image.getRGB(128, 128) >>> 24 == 0) {
                    throw new AssertionError(tool + " preview did not paint its shaft");
                }
                if (tool == Stroke.Tool.ARROW && points.length >= 4 && points[0] != points[2]
                        && image.getRGB(178, 132) >>> 24 == 0) {
                    throw new AssertionError("Arrow preview did not paint its head");
                }
            }
        }
        verifyDraftUpdates(screen);
    }

    private static void verifyDraftUpdates(dev.openintel.wartable.WarTableScreen screen) throws Exception {
        var draft = new java.util.ArrayList<Float>();
        setField(screen, "draft", draft);
        var update = screen.getClass().getDeclaredMethod("updateDraftEndpoint", double.class, double.class);
        update.setAccessible(true);
        var changed = screen.getClass().getDeclaredMethod("draftChanged");
        changed.setAccessible(true);
        var toolField = screen.getClass().getDeclaredField("tool");
        for (Object tool : toolField.getType().getEnumConstants()) {
            if (tool.toString().equals("AREA")) throw new AssertionError("Area brush must not be selectable");
            if (!tool.toString().equals("LINE") && !tool.toString().equals("ARROW")
                    && !tool.toString().equals("PEN")) continue;
            setField(screen, "tool", tool);
            draft.clear();
            update.invoke(screen, 20.0, 30.0);
            equal(0, draft.size());
            draft.add(0f);
            draft.add(0f);
            update.invoke(screen, 20.0, 30.0);
            equal(java.util.List.of(0f, 0f, 20f, 30f), draft);
            setField(screen, "lastDraft", java.util.List.copyOf(draft));
            setField(screen, "lastTool", tool);
            setField(screen, "color", 0xFFFF5555);
            setField(screen, "lastColor", 0xFFFF5555);
            setField(screen, "strokeWidth", 4f);
            setField(screen, "lastStrokeWidth", 4f);
            equal(false, changed.invoke(screen));
            setField(screen, "color", 0xFFFFFFFF);
            equal(true, changed.invoke(screen));
            setField(screen, "color", 0xFFFF5555);
            setField(screen, "strokeWidth", 8f);
            equal(true, changed.invoke(screen));
            setField(screen, "strokeWidth", 4f);
            update.invoke(screen, 40.0, 50.0);
            equal(tool.toString().equals("PEN") ? 6 : 4, draft.size());
            close(40, draft.get(draft.size() - 2));
            close(50, draft.get(draft.size() - 1));
            equal(true, changed.invoke(screen));
            draft.clear();
            equal(true, changed.invoke(screen));
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }

    private static void close(double expected, double actual) {
        if (Math.abs(expected - actual) > 0.001)
            throw new AssertionError(expected + " != " + actual);
    }
}
