package dev.openintel.api.hud;

public record HudSize(int width, int height) {
    public HudSize {
        if (width <= 0 || height <= 0 || width > 32768 || height > 32768) {
            throw new IllegalArgumentException("HUD dimensions must be between 1 and 32768 GUI-scaled pixels");
        }
    }
}
