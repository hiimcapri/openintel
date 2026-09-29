package dev.openintel.render;

public final class HudLayout {
    public record Anchor(double x, double y, double alignX, double alignY, double insetX, double insetY) {
        public boolean valid() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(alignX) && Double.isFinite(alignY)
                    && Double.isFinite(insetX) && Double.isFinite(insetY)
                    && x >= 0 && x <= 1 && y >= 0 && y <= 1
                    && alignX >= 0 && alignX <= 1 && alignY >= 0 && alignY <= 1;
        }
    }

    public record Frame(int x, int y, int width, int height, float scale) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
    }

    private HudLayout() { }

    public static float scale(int referenceWidth, int referenceHeight, int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        return Math.min(width / (float) Math.max(1, referenceWidth), height / (float) Math.max(1, referenceHeight));
    }

    public static Anchor capture(int x, int y, int elementWidth, int elementHeight, int width, int height) {
        double[] horizontal = axis(x, elementWidth, width);
        double[] vertical = axis(y, elementHeight, height);
        return new Anchor(horizontal[0], vertical[0], horizontal[1], vertical[1], horizontal[2], vertical[2]);
    }

    private static double[] axis(int value, int size, int viewport) {
        if (value < 0) return new double[]{1, 1, value};
        int edge = Math.max(0, viewport - size);
        int clamped = Math.clamp(value, 0, edge);
        if (clamped == 0) return new double[]{0, 0, 0};
        if (clamped == edge) return new double[]{1, 1, 0};
        if (Math.abs(clamped - edge / 2.0) <= 0.5) return new double[]{0.5, 0.5, 0};
        return new double[]{clamped / (double) Math.max(1, viewport), 0, 0};
    }

    public static Frame place(Anchor anchor, int elementWidth, int elementHeight,
                              int referenceWidth, int referenceHeight, int width, int height) {
        if (width <= 0 || height <= 0) return new Frame(0, 0, 0, 0, 0);
        float scale = Math.min(scale(referenceWidth, referenceHeight, width, height),
                Math.min(width / (float) Math.max(1, elementWidth), height / (float) Math.max(1, elementHeight)));
        int w = Math.min(width, Math.max(1, (int) Math.ceil(elementWidth * scale)));
        int h = Math.min(height, Math.max(1, (int) Math.ceil(elementHeight * scale)));
        if (anchor == null || !anchor.valid()) anchor = new Anchor(0, 0, 0, 0, 0, 0);
        int x = (int) Math.round(Math.clamp(anchor.x * width - anchor.alignX * w + anchor.insetX * scale, 0, width - w));
        int y = (int) Math.round(Math.clamp(anchor.y * height - anchor.alignY * h + anchor.insetY * scale, 0, height - h));
        return new Frame(x, y, w, h, scale);
    }

    public static Frame pixels(int x, int y, int elementWidth, int elementHeight, int width, int height) {
        Frame fitted = place(null, elementWidth, elementHeight, Math.max(1, width), Math.max(1, height), width, height);
        return new Frame(Math.clamp(x, 0, Math.max(0, width - fitted.width)),
                Math.clamp(y, 0, Math.max(0, height - fitted.height)), fitted.width, fitted.height, fitted.scale);
    }

    public static String ellipsize(String text, double maximum, java.util.function.ToDoubleFunction<String> measure) {
        if (measure.applyAsDouble(text) <= maximum) return text;
        String suffix = "\u2026";
        if (measure.applyAsDouble(suffix) > maximum) return "";
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = text.substring(0, text.offsetByCodePoints(0, mid)) + suffix;
            if (measure.applyAsDouble(candidate) <= maximum) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)) + suffix;
    }

    public static int snap(double raw, int size, int viewport, int distance) {
        int edge = Math.max(0, viewport - size);
        int value = (int) Math.round(Math.clamp(Double.isNaN(raw) ? 0 : raw, 0, edge));
        int target = value <= edge - value ? 0 : edge;
        int center = (int) Math.round(edge / 2.0);
        if (Math.abs(value - center) < Math.abs(value - target)) target = center;
        return Math.abs(value - target) <= distance ? target : value;
    }
}
