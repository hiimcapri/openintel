package dev.openintel.ping;

import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ping-wheel-style location pings, but network-wide: any mod user can ping
 * the block they are looking at, and the marker shows for everyone on the
 * relay (not just players in packet range). Pings expire client-side after
 * config.pingTtlMs.
 */
public class PingManager {

    /** One active ping as received from the relay. */
    public static final class Ping {
        public final String by;
        public final double x, y, z;
        public final String dimension;
        public final long expiresAt;

        Ping(String by, double x, double y, double z, String dimension, long expiresAt) {
            this.by = by;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.expiresAt = expiresAt;
        }
    }

    private final List<Ping> pings = new CopyOnWriteArrayList<>();

    public Iterable<Ping> all() {
        long now = System.currentTimeMillis();
        pings.removeIf(p -> now > p.expiresAt);
        return pings;
    }

    /** Relay broadcast {type:"ping", x, y, z, dim, by}. */
    public void handlePing(JsonObject msg, Minecraft client) {
        try {
            String by = msg.has("by") ? msg.get("by").getAsString() : "?";
            double x = msg.get("x").getAsDouble();
            double y = msg.get("y").getAsDouble();
            double z = msg.get("z").getAsDouble();
            String dim = msg.has("dim") ? msg.get("dim").getAsString() : "minecraft:overworld";

            pings.add(new Ping(by, x, y, z, dim,
                    System.currentTimeMillis() + OpenIntelClient.config().pingTtlMs));

            client.execute(() -> {
                if (client.player != null) {
                    client.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.8f, 1.6f);
                }
            });
        } catch (Exception ignored) {
        }
    }
}
