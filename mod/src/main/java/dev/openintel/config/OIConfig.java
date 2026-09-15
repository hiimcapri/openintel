package dev.openintel.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private static final String OLD_SNITCH_PATTERN = "(?i)entered snitch|snitch at|you have entered";
    private static final String DEFAULT_SNITCH_PATTERN = OLD_SNITCH_PATTERN
            + "|opened container|logged in at|logged out at|damaged sanctuary|broke block|placed block";

    /** WebSocket URL of the relay server, e.g. ws://51.222.24.116:8765 */
    public String relayUrl = "ws://51.222.24.116:8765";

    /** Minecraft multiplayer server allowed to use this relay. */
    public String minecraftServer = "play.civ.plus";

    /** Personal auth token issued by the relay admin (matches users.json on the relay).
     *  Default comes from a bundled resource so token-specific builds can be
     *  baked by patching one text file inside the jar — no recompile. */
    public String token = bundledToken();

    private static String bundledToken() {
        try (var in = OIConfig.class.getResourceAsStream("/openintel_token.txt")) {
            if (in != null) {
                String t = new String(in.readAllBytes()).trim();
                if (!t.isEmpty()) return t;
            }
        } catch (Exception ignored) { }
        return "CHANGE_ME";
    }

    /** How often (ms) the client uploads what it sees. 250–500 is plenty. */
    public int reportIntervalMs = 250;

    /** Drop a shared marker if it hasn't been refreshed in this many ms. */
    public int staleAfterMs = 10_000;

    /** Max distance (blocks) at which in-world markers are drawn; <= 0 = unlimited. */
    public double maxMarkerDistance = 0;

    /** Draw markers for players that are already visibly rendered next to you. */
    public boolean markVisiblePlayers = true;

    /** Edge-of-screen chevrons for targets outside your field of view. */
    public boolean edgeChevrons = true;

    /** Top/bottom edge list: horizontal anchor, % of screen width. */
    public int edgeRowXPct = 50;
    public int edgeTopXPct = 50;
    public int edgeBottomXPct = 50;

    /** Top/bottom edge list: arrow row inset from the screen edge, px. */
    public int edgeRowInset = 5;

    /** Left/right edge stack: vertical anchor, % of screen height. */
    public int edgeColumnYPct = 50;
    public int edgeLeftYPct = 50;
    public int edgeRightYPct = 50;

    /** Left/right edge stack: arrow column inset from the screen edge, px. */
    public int edgeColumnInset = 5;

    /** Play a sound + chat line when an enemy first enters someone's render. */
    public boolean localEnemyAlert = true;

    /** Master switch for every relay-driven overlay (markers, chevrons, nameplates). */
    public boolean relayRendering = true;

    /** Opacity of relay rendering (nameplates, markers, chevrons), 0–255. */
    public int relayOpacity = 230;

    /** Fade relay blips/markers out as their intel approaches staleAfterMs. */
    public boolean staleDecay = true;

    // ---------------------------------------------------------- presence ----

    /** Side-panel list of all relay-tracked players. */
    public boolean presenceEnabled = true;

    /** Presence panel anchor x (GUI-scaled px). */
    public int presenceX = -1;      // -1 = hug the right edge

    /** Presence panel anchor y (GUI-scaled px). */
    public int presenceY = 120;

    /** Max rows in the presence panel. */
    public int presenceMaxRows = 12;

    /** List players in other dimensions (with a dim tag, no distance). */
    public boolean presenceShowAllDims = true;

    // ---------------------------------------------------------- ping wheel ---

    /** Radial ping wheel (hold key to open, flick to a slot, release to send). */
    public boolean pingWheelEnabled = true;

    /** Seconds a shared ping stays on the map. */
    public int pingSeconds = 45;

    // ------------------------------------------------------------ events ----

    /** Toast feed for deaths/logouts/enemy sightings. */
    public boolean eventFeedEnabled = true;

    /** Seconds an event feed line stays fully visible before fading. */
    public int eventFeedSeconds = 8;
    public int eventFeedX = -1;
    public int eventFeedY = 4;

    public boolean armorHudEnabled = true;
    public int armorHudX = 8;
    public int armorHudY = 116;

    public boolean potionHudEnabled = true;
    public int potionHudX = -1;
    public int potionHudY = 40;

    /** Forward snitch-alert chat messages to the relay for the whole team. */
    public boolean snitchRelay = true;

    /** Regex matched against incoming chat text to detect a snitch hit. */
    public String snitchPattern = DEFAULT_SNITCH_PATTERN;

    /** Seconds a snitch-hit marker stays on screen, fading linearly to zero. */
    public int snitchMarkerSeconds = 120;

    /** Max distance (blocks) for snitch-hit markers; <= 0 = unlimited. */
    public int snitchMarkerRange = 0;

    /** Snitch marker icon + label color (ARGB). */
    public int snitchMarkerColor = 0xFFAAAAAA;

    /** Use the tripper's allegiance color instead of the configured snitch color. */
    public boolean snitchMarkerColorAuto = false;

    // -------------------------------------------------------------- radar ----

    /** Circular HUD radar. */
    public boolean radarEnabled = true;

    /** Dial center sits at (radarX, radarY) + radarSize from the top-left corner. */
    public int radarX = 8;
    public int radarY = 8;

    /** Dial radius in GUI-scaled pixels. */
    public int radarSize = 48;

    /** Radar sweep range in blocks. */
    public double radarRange = 64;

    /** Concentric range rings drawn inside the dial. */
    public int radarCircles = 4;

    /** true = north always up; false = the dial rotates with your view. */
    public boolean radarNorthUp = false;

    /** Compress distant blips so close contacts stay readable. */
    public boolean radarLogScale = true;

    /** Dropped items on the radar (noisy on busy servers). */
    public boolean radarShowItems = false;

    /** Boats and minecarts on the radar. */
    public boolean radarShowVehicles = true;

    /** Relay-reported players beyond render distance shown as rim dots. */
    public boolean radarShowRelay = true;

    /** Blip icon scale (player heads, item icons). */
    public float radarIconSize = 1.0f;

    /** Name label scale under blips. */
    public float radarTextSize = 1.0f;

    /** Dial background color (ARGB). */
    public int radarBgColor = 0x73090D14;

    /** Ring + spoke color (ARGB). */
    public int radarFgColor = 0x80FFFFFF;

    // ------------------------------------------------------------- macros ----

    /** Milliseconds between simulated attack presses for the attack macro. */
    public int attackMacroIntervalMs = 200;

    /** Snap yaw to the nearest 45° when the ice road macro engages. */
    public boolean iceRoadSnapYaw = true;

    /** Snap pitch to the nearest 45° when the ice road macro engages. */
    public boolean iceRoadSnapPitch = false;

    /** Hold use on food in the main hand while ice-roading. */
    public boolean iceRoadAutoEat = true;

    /** Park the ice road macro at <=6 hunger until you can eat again. */
    public boolean iceRoadStopAtHunger = false;

    public Map<String, ExternalHudState> externalHudElements = new LinkedHashMap<>();

    public static final class ExternalHudState {
        public Integer x;
        public Integer y;
        public Boolean enabled = true;

        public ExternalHudState() {
        }

        public ExternalHudState(int x, int y, boolean enabled) {
            this.x = x;
            this.y = y;
            this.enabled = enabled;
        }
    }

    public void repairExternalHudElements() {
        if (externalHudElements == null) externalHudElements = new LinkedHashMap<>();
        externalHudElements.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (ExternalHudState state : externalHudElements.values()) {
            if (state.x != null && state.x < 0) state.x = null;
            if (state.y != null && state.y < 0) state.y = null;
            if (state.enabled == null) state.enabled = true;
        }
    }

    public static OIConfig load() {
        try {
            if (Files.exists(PATH)) {
                String json = Files.readString(PATH);
                OIConfig c = GSON.fromJson(json, OIConfig.class);
                JsonObject raw = GSON.fromJson(json, JsonObject.class);
                if (c == null || raw == null) return new OIConfig();
                c.repairExternalHudElements();
                // The old default (4096) silently hid teammates across the map.
                // Migrate it to unlimited; explicit non-default caps survive.
                if (c.maxMarkerDistance == 4096) c.maxMarkerDistance = 0;
                if (OLD_SNITCH_PATTERN.equals(c.snitchPattern)) c.snitchPattern = DEFAULT_SNITCH_PATTERN;
                // Before the explicit auto-color flag existed, -1 was the default.
                // Convert that legacy default to neutral grey; future auto-color
                // selections are preserved through the explicit flag.
                if (!raw.has("snitchMarkerColorAuto")) {
                    c.snitchMarkerColorAuto = false;
                    if (c.snitchMarkerColor == -1) c.snitchMarkerColor = 0xFFAAAAAA;
                    c.save();
                }
                return c;
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
