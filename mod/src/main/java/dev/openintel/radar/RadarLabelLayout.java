package dev.openintel.radar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RadarLabelLayout {
    record Label(String key, float x, float y, float width, float height, boolean fixed) { }
    record Placed(Label label, float x, float y) {
        float right() { return x + label.width; }
        float bottom() { return y + label.height; }
    }
    record Result(List<Placed> placed, int hidden) { }
    private record Offset(int row) { }
    private static final int LIMIT = 64;
    private static final float GAP = 2;
    private static final int[] ROWS = {0, -1, 1};

    private RadarLabelLayout() { }

    static Result place(List<Label> labels, int width, int height) {
        return new Session().place(labels, width, height);
    }

    static final class Session {
        private Map<String, Offset> previous = Map.of();
        private int viewportWidth, viewportHeight;

        Result place(List<Label> labels, int width, int height) {
            if (width != viewportWidth || height != viewportHeight) previous = Map.of();
            viewportWidth = width;
            viewportHeight = height;
            var ordered = new ArrayList<>(labels);
            ordered.sort(Comparator.comparing((Label label) -> !label.fixed).thenComparing(Label::key));
            float rowPitch = 0;
            for (Label label : ordered) {
                if (!label.fixed && label.width <= width - GAP * 2 && label.height <= height - GAP * 2)
                    rowPitch = Math.max(rowPitch, label.height);
            }
            rowPitch += GAP + 4;
            var placed = new ArrayList<Placed>();
            var next = new HashMap<String, Offset>();
            int hidden = 0;
            for (Label label : ordered) {
                float maxX = width - GAP - label.width, maxY = height - GAP - label.height;
                if (maxX < GAP || maxY < GAP || placed.size() >= LIMIT) {
                    if (!label.fixed) hidden++;
                    continue;
                }
                float x = Math.clamp(label.x, GAP, maxX);
                float preferredY = Math.clamp(label.y, GAP, maxY);
                Offset old = label.fixed ? null : previous.get(label.key);
                float bestY = Float.NaN;
                int bestRow = 0;
                if (free(label, x, preferredY, placed, old == null ? GAP : GAP + 4)) {
                    bestY = preferredY;
                } else if (!label.fixed) {
                    int bestScore = Integer.MAX_VALUE;
                    for (int row : ROWS) {
                        float y = preferredY + row * rowPitch;
                        if (y < GAP || y > maxY) continue;
                        int movement = old == null ? 0 : row - old.row;
                        int score = row * row + 2 * movement * movement;
                        if (score >= bestScore || !free(label, x, y, placed, GAP)) continue;
                        bestY = y;
                        bestRow = row;
                        bestScore = score;
                    }
                }
                if (Float.isNaN(bestY)) {
                    if (!label.fixed) hidden++;
                } else {
                    placed.add(new Placed(label, x, bestY));
                    if (!label.fixed) next.put(label.key, new Offset(bestRow));
                }
            }
            previous = next;
            return new Result(List.copyOf(placed), hidden);
        }
    }

    private static boolean free(Label label, float x, float y, List<Placed> placed, float gap) {
        for (Placed p : placed) {
            if (x < p.right() + gap && x + label.width + gap > p.x
                    && y < p.bottom() + gap && y + label.height + gap > p.y) return false;
        }
        return true;
    }
}
