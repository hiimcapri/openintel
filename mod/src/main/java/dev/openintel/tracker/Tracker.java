package dev.openintel.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The intel core:
 *  - every reportIntervalMs, uploads own position + every player in local
 *    render distance (whether or not THEY run the mod) to the relay;
 *  - consumes merged "state" broadcasts from the relay into a marker map
 *    that the renderers read;
 *  - raises a local sound/chat alert the first time an enemy shows up.
 *
 * The relay (not the client) fires the Discord webhook ping, so the whole
 * network produces exactly one ping per enemy per cooldown window.
 */
public class Tracker {

    /** One shared marker as known to the network. */
    public static final class RemotePlayer {
        public final String name;
        public volatile double x, y, z;
        public volatile String dimension;
        public volatile long lastSeen;
        public volatile String reporter;
        public volatile Allegiance allegiance;

        RemotePlayer(String name) { this.name = name; }
    }

    private final Map<String, RemotePlayer> players = new ConcurrentHashMap<>();
    private long lastReport = 0;
    private long lastAlertSweep = 0;

    public Iterable<RemotePlayer> all() {
        return players.values();
    }

    // ------------------------------------------------------------------ //
    //  Outbound: what do *I* see right now?                               //
    // ------------------------------------------------------------------ //

    public void tick(Minecraft client) {
        var cfg = OpenIntelClient.config();
        long now = System.currentTimeMillis();

        // Expire stale markers.
        players.values().removeIf(p -> now - p.lastSeen > cfg.staleAfterMs);

        if (client.player == null || client.level == null) return;
        if (!OpenIntelClient.relay().isConnected()) return;
        if (now - lastReport < cfg.reportIntervalMs) return;
        lastReport = now;

        String dim = client.level.dimension().identifier().toString();
        JsonArray reports = new JsonArray();

        // Myself.
        reports.add(report(client.player.getGameProfile().name(),
                client.player.getX(), client.player.getY(), client.player.getZ(), dim));

        // Everyone the vanilla client is rendering near me — mod user or not.
        for (AbstractClientPlayer p : client.level.players()) {
            if (p == client.player) continue;
            reports.add(report(p.getGameProfile().name(), p.getX(), p.getY(), p.getZ(), dim));
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "positions");
        msg.add("reports", reports);
        OpenIntelClient.relay().send(msg);
    }

    private static JsonObject report(String name, double x, double y, double z, String dim) {
        JsonObject o = new JsonObject();
        o.addProperty("subject", name);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("dim", dim);
        return o;
    }

    // ------------------------------------------------------------------ //
    //  Inbound: merged network state from the relay                       //
    // ------------------------------------------------------------------ //

    public void handleMessage(JsonObject msg, Minecraft client) {
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            case "welcome", "allegiances" -> applyAllegiances(msg);
            case "state" -> applyState(msg, client);
            case "notice" -> OpenIntelClient.status(msg.has("msg") ? msg.get("msg").getAsString() : "");
            case "deny" -> OpenIntelClient.status("relay rejected token — ask an admin to approve you");
            default -> { }
        }
    }

    private void applyAllegiances(JsonObject msg) {
        OpenIntelClient.allegiances().replaceAll(
                names(msg.getAsJsonArray("users")),
                names(msg.getAsJsonArray("allies")),
                names(msg.getAsJsonArray("enemies")),
                names(msg.getAsJsonArray("focus")));
        // Recolor existing markers immediately.
        players.values().forEach(p -> p.allegiance = OpenIntelClient.allegiances().of(p.name));
    }

    private static java.util.List<String> names(JsonArray arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (arr != null) for (JsonElement e : arr) out.add(e.getAsString());
        return out;
    }

    private void applyState(JsonObject msg, Minecraft client) {
        if (client.player == null) return;
        String self = client.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        JsonArray arr = msg.getAsJsonArray("players");
        if (arr == null) return;

        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            if (name.toLowerCase(Locale.ROOT).equals(self)) continue;

            RemotePlayer rp = players.computeIfAbsent(name, RemotePlayer::new);
            boolean isNew = rp.lastSeen == 0;
            rp.x = o.get("x").getAsDouble();
            rp.y = o.get("y").getAsDouble();
            rp.z = o.get("z").getAsDouble();
            rp.dimension = o.get("dim").getAsString();
            rp.reporter = o.has("reporter") ? o.get("reporter").getAsString() : "?";
            rp.lastSeen = now;
            rp.allegiance = OpenIntelClient.allegiances().of(name);

            if (isNew && (rp.allegiance == Allegiance.ENEMY || rp.allegiance == Allegiance.FOCUS)) {
                localEnemyAlert(client, rp, now);
            }
        }
    }

    private void localEnemyAlert(Minecraft client, RemotePlayer rp, long now) {
        if (!OpenIntelClient.config().localEnemyAlert) return;
        if (now - lastAlertSweep < 3000) return; // don't stack sounds during a raid
        lastAlertSweep = now;

        client.execute(() -> {
            if (client.player == null) return;
            client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 0.6f);
            client.player.sendSystemMessage(Component.literal("[OpenIntel] ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("⚠ Enemy " + rp.name + " spotted at "
                                    + (int) rp.x + ", " + (int) rp.z
                                    + " (by " + rp.reporter + ")")
                            .withStyle(ChatFormatting.RED)));
        });
    }
}
