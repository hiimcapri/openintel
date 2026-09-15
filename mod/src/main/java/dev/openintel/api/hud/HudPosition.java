package dev.openintel.api.hud;

import java.util.Objects;

public record HudPosition(int x, int y) {
    public HudPosition {
        if (x < 0 || y < 0) throw new IllegalArgumentException("HUD position must be nonnegative GUI-scaled pixels");
    }

    public HudPosition clamp(HudSize size, int viewportWidth, int viewportHeight) {
        Objects.requireNonNull(size, "size");
        if (viewportWidth < 0 || viewportHeight < 0) {
            throw new IllegalArgumentException("Viewport dimensions must be nonnegative");
        }
        return new HudPosition(Math.min(x, Math.max(0, viewportWidth - size.width())),
                Math.min(y, Math.max(0, viewportHeight - size.height())));
    }
}
