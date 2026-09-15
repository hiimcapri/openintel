package dev.openintel.ping;

import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.ApiEvent;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.render.EventFeed;
import net.minecraft.client.MinecraftClient;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared location pings. A ping is a labelled point relayed to every
 * authed client: sender picks a slot on the wheel, we stamp the crosshair
 * position, and teammates see it as a marker + radar blip until it expires.
 *
 * Wire format (client -> relay -> clients):
 *   { type:"ping", id, label, x, y, z, dim, color, from, t }
 * `id` dedupes the sender's own echo; `from`/`t` are stamped by the relay.
 */
public final class PingManager {
    private PingManager() { }

    public static final class Ping {
        public final String id;
        public final String label;
        public final int color;
        public final String sender;
        public volatile double x, y, z;
        public volatile String dimension;
        public volatile long expiresAt;

        Ping(String id, String label, int color, String sender) {
            this.id = id;
            this.label = label;
            this.color = color;
            this.sender = sender;
        }
    }

    private static final Map<String, Ping> pings = new ConcurrentHashMap<>();

    public static Iterable<Ping> active() { return pings.values(); }

    public static void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(PingManager::clear);
            return;
        }
        pings.clear();
        ApiBridge.pingsChanged(ApiEvent.Cause.CLEAR);
    }

    /** Drop expired pings. Called from the client tick. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(PingManager::tick);
            return;
        }
        long now = System.currentTimeMillis();
        if (pings.values().removeIf(p -> now > p.expiresAt)) ApiBridge.pingsChanged(ApiEvent.Cause.EXPIRED);
    }

    /** Local send: register immediately, then push to the relay. */
    public static void send(String label, int color, double x, double y, double z, String dim) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            var world = client.world;
            client.execute(() -> { if (client.world == world) send(label, color, x, y, z, dim); });
            return;
        }
        String sender = client.player != null ? client.player.getGameProfile().name() : "?";
        String id = sender + "-" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString((int) x ^ (int) z, 36);

        add(id, label, color, sender, x, y, z, dim);
        EventFeed.add("You pinged \"" + label + "\" at "
                + (int) x + ", " + (int) z, color);

        if (OpenIntelClient.relay().isConnected()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "ping");
            msg.addProperty("id", id);
            msg.addProperty("label", label);
            msg.addProperty("x", x);
            msg.addProperty("y", y);
            msg.addProperty("z", z);
            msg.addProperty("dim", dim);
            msg.addProperty("color", color);
            OpenIntelClient.relay().send(msg);
        }
    }

    public static boolean sendShared(String label, int color, double x, double y, double z, String dim) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || client.player == null || !OpenIntelClient.relay().isAuthenticated()) return false;
        String sender = client.player.getGameProfile().name();
        String id = java.util.UUID.randomUUID().toString();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "ping");
        msg.addProperty("id", id);
        msg.addProperty("label", label);
        msg.addProperty("x", x);
        msg.addProperty("y", y);
        msg.addProperty("z", z);
        msg.addProperty("dim", dim);
        msg.addProperty("color", color);
        if (!OpenIntelClient.relay().trySend(msg)) return false;
        add(id, label, color, sender, x, y, z, dim);
        EventFeed.add("You pinged \"" + label + "\" at " + (int) x + ", " + (int) z, color);
        return true;
    }

    /** Inbound ping from the relay (includes our own echo — deduped by id). */
    public static void receive(JsonObject msg) {
        MinecraftClient executor = MinecraftClient.getInstance();
        if (!executor.isOnThread()) {
            JsonObject copy = msg.deepCopy();
            var world = executor.world;
            executor.execute(() -> { if (executor.world == world) receive(copy); });
            return;
        }
        if (!msg.has("id") || !msg.has("label")
                || !msg.has("x") || !msg.has("y") || !msg.has("z")) return;
        String id = msg.get("id").getAsString();
        if (pings.containsKey(id)) return;

        String sender = msg.has("from") ? msg.get("from").getAsString() : "?";
        String label = msg.get("label").getAsString();
        int color = msg.has("color") ? msg.get("color").getAsInt() : 0xFFFFAA00;

        Ping p = add(id, label, color, sender,
                msg.get("x").getAsDouble(), msg.get("y").getAsDouble(),
                msg.get("z").getAsDouble(),
                msg.has("dim") ? msg.get("dim").getAsString() : null);

        MinecraftClient client = MinecraftClient.getInstance();
        String self = client.player != null
                ? client.player.getGameProfile().name().toLowerCase(Locale.ROOT) : "";
        if (!sender.toLowerCase(Locale.ROOT).equals(self)) {
            EventFeed.add(sender + " pinged \"" + label + "\" at "
                    + (int) p.x + ", " + (int) p.z, color);
        }
    }

    private static Ping add(String id, String label, int color, String sender,
                            double x, double y, double z, String dim) {
        Ping p = new Ping(id, label, color, sender);
        p.x = x;
        p.y = y;
        p.z = z;
        p.dimension = dim;
        p.expiresAt = System.currentTimeMillis()
                + OpenIntelClient.config().pingSeconds * 1000L;
        pings.put(id, p);
        ApiBridge.pingsChanged(ApiEvent.Cause.UPDATE);
        return p;
    }
}
