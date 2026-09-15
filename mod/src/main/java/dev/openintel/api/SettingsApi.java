package dev.openintel.api;

import java.util.Optional;

public interface SettingsApi {
    Optional<View> snapshot();

    record View(Radar radar, Markers markers, Snitches snitches, Overlays overlays,
                boolean pingWheelEnabled, int pingLifetimeSeconds, int reportIntervalMs) { }
    record Radar(boolean enabled, int x, int y, int radius, double range, int rings,
                 boolean northUp, boolean logarithmic, boolean showItems, boolean showVehicles,
                 boolean showRelay, float iconScale, float textScale, int backgroundColor, int foregroundColor) { }
    record Markers(boolean enabled, boolean visiblePlayers, boolean edgeChevrons, int opacity,
                   boolean staleDecay, int staleAfterMs, double maxDistance, int topXPct,
                   int bottomXPct, int leftYPct, int rightYPct, int rowInset, int columnInset) { }
    record Snitches(boolean forwardingEnabled, int lifetimeSeconds, int maxDistance,
                    int color, boolean allegianceColor) { }
    record Overlay(boolean enabled, int x, int y) { }
    record Overlays(Overlay presence, int presenceRows, boolean presenceAllDimensions,
                    Overlay feed, int feedLifetimeSeconds, Overlay armor, Overlay potions) { }
}
