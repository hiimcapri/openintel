package dev.openintel.wartable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.openintel.OpenIntelClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public sanctuary geometry from the same site that serves the live map.
 * Unlike the map PNG, these are world-space circles ({name,x,z,radius,world}),
 * so they remain aligned no matter which rendered map layer is underneath.
 */
public final class Sanctuaries {
    private Sanctuaries() { }

    public record Sanctuary(String name, double x, double z, double radius, String world) {
        public boolean overworld() {
            return "world".equalsIgnoreCase(world);
        }
    }

    private static final long REFRESH_MS = 55_000;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final AtomicBoolean loading = new AtomicBoolean();

    private static volatile List<Sanctuary> sanctuaries = List.of();
    private static volatile long fetchedAt;
    private static volatile long lastAttempt;
    private static volatile int revision;
    private static volatile String error;

    public static List<Sanctuary> all() { return sanctuaries; }
    public static int revision() { return revision; }
    public static String error() { return error; }

    /** Fetch on open and again periodically while the War Table is open. */
    public static void ensureLoaded() {
        if (System.currentTimeMillis() - lastAttempt < REFRESH_MS) return;
        if (!loading.compareAndSet(false, true)) return;
        lastAttempt = System.currentTimeMillis();
        Thread t = new Thread(Sanctuaries::load, "openintel-sanctuaries");
        t.setDaemon(true);
        t.start();
    }

    private static void load() {
        try {
            String url = endpoint();
            if (url == null) {
                error = "no live map url configured";
                return;
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "OpenIntel-WarTable")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }

            JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
            List<Sanctuary> parsed = new ArrayList<>(arr.size());
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                var o = e.getAsJsonObject();
                if (!o.has("name") || !o.has("x") || !o.has("z") || !o.has("radius")) continue;
                double x = o.get("x").getAsDouble();
                double z = o.get("z").getAsDouble();
                double radius = o.get("radius").getAsDouble();
                if (!Double.isFinite(x) || !Double.isFinite(z)
                        || !Double.isFinite(radius) || radius <= 0) continue;
                String name = cleanName(o.get("name").getAsString());
                String world = o.has("world") ? o.get("world").getAsString() : "world";
                parsed.add(new Sanctuary(name, x, z, radius, world));
            }
            sanctuaries = List.copyOf(parsed);
            fetchedAt = System.currentTimeMillis();
            error = null;
            revision++;
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            loading.set(false);
        }
    }

    /** The map API is under /livemap-api; sanctuaries live at site root /api. */
    static String endpoint() {
        var cfg = OpenIntelClient.config();
        if (cfg == null || cfg.liveMapBase == null || cfg.liveMapBase.isBlank()) return null;
        try {
            URI base = URI.create(cfg.liveMapBase);
            URI resolved = base.resolve("/api/sanctuaries");
            if (("localhost".equalsIgnoreCase(resolved.getHost())
                    || "127.0.0.1".equals(resolved.getHost()))
                    && resolved.getPort() == 3002) {
                resolved = new URI(resolved.getScheme(), null, resolved.getHost(), 3003,
                        resolved.getPath(), null, null);
            }
            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String cleanName(String name) {
        if (name == null) return "Sanctuary";
        String cleaned = name.replaceAll("(?i)§[0-9A-FK-OR]", "").trim();
        return cleaned.isEmpty() ? "Sanctuary" : cleaned;
    }

    /** First letters as a compact label when a long name does not fit. */
    public static String acronym(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("[\\s_\\-]+")) {
            if (!word.isEmpty()) out.append(Character.toUpperCase(word.charAt(0)));
        }
        return out.isEmpty() ? name : out.toString().toUpperCase(Locale.ROOT);
    }
}
