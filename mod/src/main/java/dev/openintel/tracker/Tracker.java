package dev.openintel.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.ApiEvent;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import dev.openintel.ping.PingManager;
import dev.openintel.render.EventFeed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

        RemotePlayer(String name) {
            this.name = name;
            this.allegiance = Allegiance.NEUTRAL;
        }
    }

    /** A snitch hit: which snitch, who tripped it, where, when. */
    public static final class SnitchHit {
        public final String snitch, player, reporter, dimension;
        public final double x, y, z;
        public final long t;

        SnitchHit(String snitch, String player, String reporter,
                  double x, double y, double z, String dimension, long t) {
            this.snitch = snitch;
            this.player = player;
            this.reporter = reporter;
            this.x = x; this.y = y; this.z = z;
            this.dimension = dimension;
            this.t = t;
        }
    }

    private final Map<String, RemotePlayer> players = new ConcurrentHashMap<>();
    private final Map<String, SnitchHit> snitchHits = new ConcurrentHashMap<>();
    private java.util.Set<String> knownUsers = java.util.Set.of();
    private long lastReport = 0;
    private long lastAlertSweep = 0;

    public Iterable<RemotePlayer> all() {
        return players.values();
    }

    public Iterable<SnitchHit> snitchHits() {
        return snitchHits.values();
    }

    /** Drop all intel (called on disconnect). */
    public void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(this::clear);
            return;
        }
        players.clear();
        snitchHits.clear();
        knownUsers = java.util.Set.of();
        lastReport = 0;
        lastAlertSweep = 0;
        ApiBridge.trackerChanged(ApiEvent.Cause.CLEAR);
    }

    // ------------------------------------------------------------------ //
    //  Outbound: what do *I* see right now?                               //
    // ------------------------------------------------------------------ //

    public void tick(MinecraftClient client) {
        if (!client.isOnThread()) {
            client.execute(() -> tick(client));
            return;
        }
        var cfg = OpenIntelClient.config();
        long now = System.currentTimeMillis();

        // Expire stale markers — a friendly going dark is feed-worthy.
        boolean expiredPlayers = players.values().removeIf(p -> {
            if (now - p.lastSeen <= cfg.staleAfterMs) return false;
            if (p.allegiance == Allegiance.FRIEND || p.allegiance == Allegiance.ALLY
                    || p.allegiance == Allegiance.FOCUS) {
                EventFeed.add(p.name + " went dark", 0xFFAAAAAA);
            }
            return true;
        });
        // Snitch-hit markers live on their own 2-minute clock.
        boolean expiredSnitches = snitchHits.values().removeIf(h -> now - h.t > cfg.snitchMarkerSeconds * 1000L);
        if (expiredPlayers || expiredSnitches) ApiBridge.trackerChanged(ApiEvent.Cause.EXPIRED);

        if (client.player == null || client.world == null) return;
        if (!OpenIntelClient.relay().isConnected()) return;
        if (now - lastReport < cfg.reportIntervalMs) return;
        lastReport = now;

        String dim = client.world.getRegistryKey().getValue().toString();
        JsonArray reports = new JsonArray();

        // Myself.
        reports.add(report(client.player.getGameProfile().name(),
                client.player.getX(), client.player.getY(), client.player.getZ(), dim));

        // Everyone the vanilla client is rendering near me — mod user or not.
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
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

    public void handleMessage(JsonObject msg, MinecraftClient client) {
        if (!client.isOnThread()) {
            JsonObject copy = msg.deepCopy();
            var world = client.world;
            client.execute(() -> { if (client.world == world) handleMessage(copy, client); });
            return;
        }
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            case "welcome", "allegiances" -> applyAllegiances(msg);
            case "state" -> applyState(msg, client);
            case "intel_reset" -> {
                clear();
                PingManager.clear();
                EventFeed.clear();
            }
            case "ping" -> PingManager.receive(msg);
            case "snitch" -> applySnitch(msg);
            case "notice" -> {
                String text = msg.has("msg") ? msg.get("msg").getAsString() : "";
                OpenIntelClient.status(text);
                if (text.startsWith("[Broadcast]")) EventFeed.add(text, 0xFFFFAA00);
                else ApiBridge.notification(text, 0xFFAAAAAA, "relay");
            }
            case "deny" -> {
                String reason = msg.has("reason") ? msg.get("reason").getAsString() : "bad_token";
                if (reason.equals("wrong_server")) {
                    String expected = msg.has("expectedServer") ? msg.get("expectedServer").getAsString() : "configured server";
                    OpenIntelClient.status("relay rejected this Minecraft server — expected " + expected);
                } else {
                    OpenIntelClient.status("relay rejected token — ask an admin to approve you");
                }
            }
            default -> { }
        }
        if (type.equals("welcome") || type.equals("allegiances") || type.equals("state") || type.equals("snitch"))
            ApiBridge.trackerChanged(ApiEvent.Cause.UPDATE);
    }

    private void applyAllegiances(JsonObject msg) {
        java.util.List<String> users = names(msg.getAsJsonArray("users"));

        // Relay roster diff → join/leave feed lines.
        java.util.Set<String> now = new java.util.HashSet<>(users);
        if (!knownUsers.isEmpty()) {
            for (String n : now) {
                if (!knownUsers.contains(n)) {
                    EventFeed.add(n + " joined the relay", 0xFF55FF55);
                }
            }
            for (String n : knownUsers) {
                if (!now.contains(n)) {
                    EventFeed.add(n + " left the relay", 0xFFFFAA00);
                }
            }
        }
        knownUsers = now;

        OpenIntelClient.allegiances().replaceAll(
                users,
                names(msg.getAsJsonArray("allies")),
                names(msg.getAsJsonArray("enemies")),
                names(msg.getAsJsonArray("focus")));
        // Recolor existing markers immediately.
        players.values().forEach(p -> p.allegiance = OpenIntelClient.allegiances().of(p.name));
    }

    /**
     * A teammate's snitch alert: feed line plus a temporary marker at the
     * hit position so it shows on markers + radar without waiting for a
     * real position report.
     */
    private void applySnitch(JsonObject msg) {
        ApiBridge.relaySnitch(msg);
        String who = msg.has("player") ? msg.get("player").getAsString() : "?";
        String from = msg.has("from") ? msg.get("from").getAsString()
                : msg.has("reporter") ? msg.get("reporter").getAsString() : "?";

        // Our own forward echoes back through the relay — we already fed it
        // locally in SnitchRelay, so skip the noise but keep the marker.
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean self = mc.player != null && mc.player.getGameProfile().name()
                .equalsIgnoreCase(from);
        if (!self) {
            String action = msg.has("action") ? msg.get("action").getAsString() : "tripped a snitch";
            String text = "📡 " + who + " " + action;
            if (msg.has("x")) {
                text += " at " + msg.get("x").getAsInt() + ", " + msg.get("z").getAsInt();
            }
            text += " (" + from + ")";
            EventFeed.add(text, 0xFFFFAA00);
        }

        if (msg.has("x") && msg.has("y") && msg.has("z")
                && msg.has("player") && msg.has("snitch")) {
            String snitch = msg.get("snitch").getAsString();
            if (who.isBlank() || who.equals("?") || snitch.isBlank()) return;
            long t = msg.has("t") ? msg.get("t").getAsLong() : System.currentTimeMillis();

            // A named world pins the marker to that dimension — only shown to
            // players actually in it. No world name → guess from the snitch
            // name ("EndSpawn" → the End), then the tripper's last-seen dim
            // (they're AT the snitch), then the reporter's, then ours.
            String dim = bindDim(msg.has("world") && !msg.get("world").isJsonNull()
                    ? msg.get("world").getAsString() : null);
            if (dim == null || dim.equals("openintel:unknown")) return;

            // Approved relay users already stream live positions — a snitch
            // marker on them is redundant noise.
            if (OpenIntelClient.allegiances().of(who) == Allegiance.FRIEND) return;

            // One marker per tripper — a new hit updates their location, no
            // ghost trail through a snitch field. Unknown trippers key on the
            // hit itself so they don't collapse onto each other.
            snitchHits.put(who.equals("?") ? snitch + "@" + msg.get("x").getAsInt()
                            + "," + msg.get("z").getAsInt() : who,
                    new SnitchHit(snitch, who, from,
                            msg.get("x").getAsDouble(), msg.get("y").getAsDouble(),
                            msg.get("z").getAsDouble(), dim, t));
        }
    }

    /**
     * A snitch hit seen locally — SnitchRelay calls this directly so the
     * marker appears instantly (and still works with the relay down). The
     * relay echo refreshes the same map key, so no duplication.
     */
    public void addSnitchHit(String snitch, String player, String reporter,
                             double x, double y, double z, String world, long t) {
        String dim = bindDim(world);
        if (dim == null || dim.equals("openintel:unknown")) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            var currentWorld = client.world;
            client.execute(() -> {
                if (client.world == currentWorld) addSnitchHit(snitch, player, reporter, x, y, z, world, t);
            });
            return;
        }
        if (OpenIntelClient.allegiances().of(player) == Allegiance.FRIEND) return;
        snitchHits.put(player.equals("?") ? snitch + "@" + (int) x + "," + (int) z : player,
                new SnitchHit(snitch, player, reporter, x, y, z, dim, t));
        ApiBridge.trackerChanged(ApiEvent.Cause.UPDATE);
    }

    /**
     * Dimension binding for a snitch hit. A world name pins the marker to
     * that dimension: parseable → registry id; unparseable → the raw name
     * (which won't equal any real dim id, so it's simply never shown).
     * No world name → null, caller picks a fallback.
     */
    private static String bindDim(String world) {
        if (world == null || world.isBlank()) return null;
        String n = normalizeDim(world);
        if (n != null) return n;
        return "openintel:unknown";
    }

    /**
     * Guess a hit's dimension from the snitch name — "EndSpawn", "NetherHub",
     * "Lands-End". Deliberately tight (camelCase / separators only) so names
     * like "Endurance" or "Defend Point" don't false-positive.
     */
    private static String inferDimFromName(String snitch) {
        if (snitch == null) return null;
        if (snitch.matches("(?i)^end([_\\-\\s]|[A-Z]|$).*")
                || snitch.matches("(?i).*[_\\-\\s]end([_\\-\\s]|$).*")) {
            return "minecraft:the_end";
        }
        if (snitch.matches("(?i)^nether([_\\-\\s]|[A-Z]|$).*")
                || snitch.matches("(?i).*[_\\-\\s]nether([_\\-\\s]|$).*")) {
            return "minecraft:the_nether";
        }
        return null;
    }

    /** Bukkit world name → registry id; null if we can't map it. */
    private static String normalizeDim(String world) {
        if (world == null || world.isEmpty()) return null;
        String w = world.trim().toLowerCase(Locale.ROOT);
        if (w.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return w;
        return switch (w) {
            case "world", "overworld" -> "minecraft:overworld";
            case "world_nether", "nether", "the_nether" -> "minecraft:the_nether";
            case "world_the_end", "end", "the_end" -> "minecraft:the_end";
            default -> null;
        };
    }

    private static java.util.List<String> names(JsonArray arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (arr != null) for (JsonElement e : arr) out.add(e.getAsString());
        return out;
    }

    private void applyState(JsonObject msg, MinecraftClient client) {
        if (client.player == null) return;
        String self = client.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        JsonArray arr = msg.getAsJsonArray("players");
        if (arr == null) return;
        if (msg.has("replace") && msg.get("replace").getAsBoolean()) {
            java.util.Set<String> visible = new java.util.HashSet<>();
            for (JsonElement e : arr) visible.add(e.getAsJsonObject().get("name").getAsString());
            players.keySet().retainAll(visible);
        }

        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            if (name.toLowerCase(Locale.ROOT).equals(self)) continue;

            boolean[] isNew = {false};
            RemotePlayer rp = players.compute(name, (key, current) -> {
                if (current == null) {
                    current = new RemotePlayer(key);
                    isNew[0] = true;
                }
                current.x = o.get("x").getAsDouble();
                current.y = o.get("y").getAsDouble();
                current.z = o.get("z").getAsDouble();
                current.dimension = o.get("dim").getAsString();
                current.reporter = o.has("reporter") ? o.get("reporter").getAsString() : "?";
                current.allegiance = OpenIntelClient.allegiances().of(name);
                current.lastSeen = now;
                return current;
            });

            if (isNew[0] && (rp.allegiance == Allegiance.ENEMY || rp.allegiance == Allegiance.FOCUS)) {
                localEnemyAlert(client, rp, now);
            }
        }
    }

    private void localEnemyAlert(MinecraftClient client, RemotePlayer rp, long now) {
        if (!OpenIntelClient.config().localEnemyAlert) return;
        if (now - lastAlertSweep < 3000) return; // don't stack sounds during a raid
        lastAlertSweep = now;

        client.execute(() -> {
            if (client.player == null) return;
            client.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 0.6f);
            client.player.sendMessage(Text.literal("[OpenIntel] ")
                    .formatted(Formatting.GOLD)
                    .append(Text.literal("⚠ Enemy " + rp.name + " spotted at "
                                    + (int) rp.x + ", " + (int) rp.z
                                    + " (by " + rp.reporter + ")")
                            .formatted(Formatting.RED)), false);
        });
    }
}
