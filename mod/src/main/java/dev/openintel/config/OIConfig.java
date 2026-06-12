package dev.openintel.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side configuration, persisted to config/openintel.json.
 *
 * Note: webhook URLs intentionally do NOT live here. All webhook traffic is
 * fired by the relay server so alerts are deduplicated and the URLs are never
 * distributed inside a client jar (anyone with a webhook URL can post to it,
 * or delete it).
 */
public class OIConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("openintel.json");

    /** WebSocket URL of the relay server, e.g. ws://intel.example.org:8765 */
    public String relayUrl = "ws://localhost:8765";

    /** Personal auth token issued by the relay admin (matches users.json on the relay). */
    public String token = "CHANGE_ME";

    /** How often (ms) the client uploads what it sees. 250–500 is plenty. */
    public int reportIntervalMs = 250;

    /** Drop a shared marker if it hasn't been refreshed in this many ms. */
    public int staleAfterMs = 10_000;

    /** Max distance (blocks) at which in-world markers are drawn. */
    public double maxMarkerDistance = 4096;

    /** Draw markers for players that are already visibly rendered next to you. */
    public boolean markVisiblePlayers = true;

    /** Edge-of-screen chevrons for targets outside your field of view. */
    public boolean edgeChevrons = true;

    /** Play a sound + chat line when an enemy first enters someone's render. */
    public boolean localEnemyAlert = true;

    /** How long (ms) a location ping marker stays on screen. */
    public int pingTtlMs = 10_000;

    public static OIConfig load() {
        try {
            if (Files.exists(PATH)) {
                return GSON.fromJson(Files.readString(PATH), OIConfig.class);
            }
        } catch (Exception ignored) {
        }
        OIConfig fresh = new OIConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }
}
