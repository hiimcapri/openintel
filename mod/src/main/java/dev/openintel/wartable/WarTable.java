package dev.openintel.wartable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.OpenIntelApi;
import dev.openintel.render.EventFeed;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * War Table state: the shared annotation layer drawn over the server's
 * rendered map image. Strokes are authored by relay admins, stored on the
 * relay, and replayed to every client.
 *
 * Wire protocol (all inside {"type":"wartable", ...}):
 *   client -> relay   { action:"add",    stroke:{...} }
 *                     { action:"delete", id }
 *                     { action:"clear",  scope:"mine"|"all" }
 *   relay -> clients  same shapes, plus { from, t } stamped by the relay.
 *   full sync:        {"type":"wartable_sync", strokes:[...]}
 *
 * The relay is authoritative for the shared layer: a sync replaces cached
 * shared strokes entirely. Offline annotations live in a separate local layer
 * and survive syncs without ever being submitted to the relay.
 */
public final class WarTable {
    private WarTable() { }

    private static final Gson GSON = new Gson();

    /** Ordered by insert time; the relay preserves this order. */
    private static final Map<String, Stroke> strokes = new LinkedHashMap<>();
    /** Local-only annotations made while no authenticated relay session exists. */
    private static final Map<String, Stroke> local = new LinkedHashMap<>();
    /** ids we added locally that the relay hasn't echoed yet. */
    private static final Map<String, Stroke> pending = new LinkedHashMap<>();
    /** this session's own stroke ids, for undo (newest last). */
    private static final Deque<String> ownIds = new ArrayDeque<>();
    private static volatile int revision;          // bump → repaint

    public static Collection<Stroke> strokes() {
        // Local and pending strokes render immediately; the shared map remains
        // authoritative once a sync arrives.
        Map<String, Stroke> all = new LinkedHashMap<>(strokes);
        all.putAll(local);
        all.putAll(pending);
        return all.values();
    }

    /** Pins eligible for in-world markers: live shared pins, or local pins offline. */
    public static Collection<Stroke> waypointStrokes() {
        return live() ? strokes() : local.values();
    }

    public static int size() { return strokes.size() + local.size() + pending.size(); }
    public static int revision() { return revision; }

    /** Relay admins share edits; without an authenticated session the table is local-only. */
    public static boolean canDraw() {
        var snap = OpenIntelApi.relay().snapshot();
        return !snap.authenticated()
                || snap.role().map(r -> r.equalsIgnoreCase("admin")).orElse(false);
    }

    public static boolean live() {
        return OpenIntelApi.relay().snapshot().authenticated();
    }

    // ----------------------------------------------------------- outbound ---

    /** Locally committed stroke — show now, then push to the relay. */
    public static boolean add(Stroke.Tool tool, int color, float width,
                              float[] points, String label) {
        if (!canDraw()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        String author = client.player != null ? client.player.getGameProfile().name() : "?";
        String id = Long.toString(System.currentTimeMillis(), 36) + "-"
                + Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(), 36);

        Stroke s = new Stroke(id, tool, color, width, points, label, author, 0);
        if (!Stroke.validate(s)) return false;

        if (live()) {
            pending.put(id, s);
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "wartable");
            msg.addProperty("action", "add");
            msg.add("stroke", s.toJson());
            OpenIntelClient.relay().send(msg);
        } else {
            local.put(id, s);
            persistLocal();
        }
        ownIds.addLast(id);
        revision++;
        return true;
    }

    public static void delete(String id) {
        if (id == null || !canDraw()) return;
        boolean localRemoved = local.remove(id) != null;
        boolean sharedRemoved = strokes.remove(id) != null;
        sharedRemoved |= pending.remove(id) != null;
        ownIds.remove(id);
        revision++;
        if (localRemoved) persistLocal();
        if (sharedRemoved) persistShared();
        if (!live() || !sharedRemoved) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "wartable");
        msg.addProperty("action", "delete");
        msg.addProperty("id", id);
        OpenIntelClient.relay().send(msg);
    }

    /** Remove the newest stroke drawn by this session. */
    public static boolean undoOwn() {
        while (!ownIds.isEmpty()) {
            String id = ownIds.pollLast();
            if (strokes.containsKey(id) || local.containsKey(id) || pending.containsKey(id)) {
                delete(id);
                return true;
            }
        }
        return false;
    }

    public static void clearAll() {
        if (!canDraw()) return;
        strokes.clear();
        local.clear();
        pending.clear();
        ownIds.clear();
        revision++;
        persist();
        if (!live()) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "wartable");
        msg.addProperty("action", "clear");
        msg.addProperty("scope", "all");
        OpenIntelClient.relay().send(msg);
    }

    // ------------------------------------------------------------ inbound ---

    /** Relay op: add / delete / clear. */
    public static void receive(JsonObject msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            JsonObject copy = msg.deepCopy();
            var world = client.world;
            client.execute(() -> { if (client.world == world) receive(copy); });
            return;
        }
        String action = msg.has("action") ? msg.get("action").getAsString() : "";
        String from = msg.has("from") ? msg.get("from").getAsString() : "?";
        switch (action) {
            case "add" -> {
                Stroke s = Stroke.fromJson(msg.getAsJsonObject("stroke"));
                if (s == null) return;
                pending.remove(s.id);
                if (local.remove(s.id) != null) persistLocal();
                strokes.put(s.id, s);   // put replaces → edits dedupe by id
                revision++;
            }
            case "delete" -> {
                String id = msg.has("id") ? msg.get("id").getAsString() : null;
                if (id == null) return;
                boolean removed = strokes.remove(id) != null || pending.remove(id) != null;
                if (local.remove(id) != null) {
                    removed = true;
                    persistLocal();
                }
                if (removed) revision++;
            }
            case "clear" -> {
                String scope = msg.has("scope") ? msg.get("scope").getAsString() : "all";
                if ("mine".equals(scope)) {
                    strokes.values().removeIf(s -> s.author != null && s.author.equalsIgnoreCase(from));
                } else {
                    strokes.clear();
                }
                pending.clear();
                ownIds.removeIf(id -> !local.containsKey(id));
                revision++;
                String self = client.player != null
                        ? client.player.getGameProfile().name().toLowerCase(Locale.ROOT) : "";
                if (!from.equalsIgnoreCase(self)) {
                    EventFeed.addRelay(from + " cleared the war table", 0xFFFFAA00);
                }
            }
            default -> { }
        }
    }

    /** Full authoritative set — replaces shared state on (re)auth; local edits survive. */
    public static void applySync(JsonObject msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && !client.isOnThread()) {
            JsonObject copy = msg.deepCopy();
            var world = client.world;
            client.execute(() -> { if (client.world == world) applySync(copy); });
            return;
        }
        strokes.clear();
        JsonArray arr = msg.getAsJsonArray("strokes");
        if (arr != null) {
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Stroke s = Stroke.fromJson(e.getAsJsonObject());
                if (s != null) strokes.put(s.id, s);
            }
        }
        pending.clear();
        if (local.keySet().removeAll(strokes.keySet())) persistLocal();
        ownIds.removeIf(id -> !local.containsKey(id));
        revision++;
        persist();
    }

    // ---------------------------------------------------------- eraser -----

    /** Nearest stroke to a world point within `tol` blocks, or null. */
    public static Stroke nearStroke(double wx, double wz, double tol) {
        Stroke best = null;
        double bestD = tol;
        for (Stroke s : strokes()) {
            double d = s.distanceTo(wx, wz);
            if (d < bestD) { best = s; bestD = d; }
        }
        return best;
    }

    // ------------------------------------------------------- persistence ----

    /** Last-known shared strokes plus this client's local annotations. */
    public static void persist() {
        persistShared();
        persistLocal();
    }

    private static void persistShared() {
        Path path = configFile("wartable-strokes.json");
        if (path == null) return;
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Stroke s : strokes.values()) arr.add(s.toJson());
            for (Stroke s : pending.values()) arr.add(s.toJson());
            root.add("strokes", arr);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception ignored) { }
    }

    private static void persistLocal() {
        Path path = configFile("wartable-local-strokes.json");
        if (path == null) return;
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Stroke s : local.values()) arr.add(s.toJson());
            root.add("strokes", arr);
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root));
        } catch (Exception ignored) { }
    }

    private static Path configFile(String name) {
        try {
            Path config = FabricLoader.getInstance().getConfigDir();
            return config == null ? null : config.resolve("openintel").resolve(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void load() {
        strokes.clear();
        local.clear();
        pending.clear();
        loadInto(configFile("wartable-strokes.json"), strokes);
        loadInto(configFile("wartable-local-strokes.json"), local);
        revision++;
    }

    private static void loadInto(Path path, Map<String, Stroke> target) {
        if (path == null) return;
        try {
            if (!Files.exists(path)) return;
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("strokes");
            if (arr == null) return;
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                Stroke s = Stroke.fromJson(e.getAsJsonObject());
                if (s != null) target.putIfAbsent(s.id, s);
            }
        } catch (Exception ignored) { }
    }

    /** Session teardown — own-undo bookkeeping is per-session. */
    public static void reset() {
        ownIds.clear();
        pending.clear();
        persist();
    }
}
