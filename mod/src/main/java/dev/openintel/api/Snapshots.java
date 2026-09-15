package dev.openintel.api;

import java.util.Map;
import java.util.Optional;

public final class Snapshots {
    private Snapshots() { }

    public enum Allegiance {
        FOCUS(0xFFE040FB), FRIEND(0xFF55FF55), ALLY(0xFFAA55FF), ENEMY(0xFFFF5555), NEUTRAL(0xFFAAAAAA);
        private final int argb;
        Allegiance(int argb) { this.argb = argb; }
        public int argb() { return argb; }
    }
    public enum ConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, AUTHENTICATED, DENIED }

    public record Position(double x, double y, double z, String dimension) { }
    public record Player(String name, Position position, long lastSeen, String reporter,
                         Allegiance allegiance) { }
    public record Snitch(String snitch, String player, String reporter, Position position, long timestamp) { }
    public record Ping(String id, String label, int color, String sender, Position position, long expiresAt) { }
    public record Allegiances(Map<String, Allegiance> entries) {
        public Allegiances { entries = Map.copyOf(entries); }
        public Allegiance of(String name) {
            return name == null ? Allegiance.NEUTRAL
                    : entries.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), Allegiance.NEUTRAL);
        }
    }
    public record Connection(ConnectionState state, Optional<String> minecraftServer, Optional<String> role) {
        public boolean authenticated() { return state == ConnectionState.AUTHENTICATED; }
    }
    public record Notification(String text, int color, long timestamp, String source) { }
    public record SnitchArrival(String snitch, String player, String reporter,
                                Optional<Position> position, String action, String message, long timestamp) { }
}
