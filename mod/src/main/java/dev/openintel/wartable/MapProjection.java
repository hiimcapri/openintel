package dev.openintel.wartable;

import com.google.gson.JsonObject;

/**
 * World ↔ map-pixel transform for a rendered live-map image.
 *
 * The live-map API serves one PNG per renderer plus a JSON metadata file
 * describing the world rectangle it covers:
 *
 *   { "topLeft": {"x":-3403,"z":-3403}, "bottomRight": {"x":3402,"z":3402},
 *     "imageSize": {"width":6806,"height":6806}, "scale": 1 }
 *
 * `scale` is world blocks per image pixel (1 = one pixel per block).
 * Strokes are stored in world coordinates so they survive re-renders of
 * the underlying image.
 */
public record MapProjection(double minX, double minZ, double scale, int width, int height) {

    /** world block x → image pixel x */
    public double worldToPxX(double x) { return (x - minX) / scale; }

    /** world block z → image pixel y */
    public double worldToPxZ(double z) { return (z - minZ) / scale; }

    /** image pixel x → world block x */
    public double pxToWorldX(double px) { return minX + px * scale; }

    /** image pixel y → world block z */
    public double pxToWorldZ(double py) { return minZ + py * scale; }

    /** Parse the metadata JSON served next to the map PNG. */
    public static MapProjection fromJson(JsonObject meta) {
        if (meta == null) return null;
        try {
            JsonObject tl = meta.getAsJsonObject("topLeft");
            JsonObject br = meta.getAsJsonObject("bottomRight");
            JsonObject size = meta.getAsJsonObject("imageSize");
            if (tl == null || br == null || size == null) return null;
            double minX = tl.get("x").getAsDouble();
            double minZ = tl.get("z").getAsDouble();
            double maxX = br.get("x").getAsDouble();
            double maxZ = br.get("z").getAsDouble();
            int width = size.get("width").getAsInt();
            int height = size.get("height").getAsInt();
            double scale = meta.has("scale") ? meta.get("scale").getAsDouble() : 1.0;
            if (width <= 0 || height <= 0 || scale <= 0) return null;
            // Sanity: declared bounds should roughly match imageSize * scale.
            double expectedW = (maxX - minX) / scale + 1;
            if (Math.abs(expectedW - width) > 4) return null;
            return new MapProjection(minX, minZ, scale, width, height);
        } catch (Exception e) {
            return null;
        }
    }
}
