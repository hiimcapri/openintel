package dev.openintel;

import com.google.gson.JsonObject;
import dev.openintel.render.EventFeed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forwards snitch-alert chat lines (JukeAlert-style "* X entered snitch at
 * name [world x y z]") to the relay so the whole team sees them, not just
 * whoever is standing on the alert group.
 *
 * Detection is a two-stage regex: `snitchPattern` decides whether a line is
 * a snitch hit at all (configurable), then a fixed extractor pulls the
 * trailing [world x y z] block and the leading "* name" if present. When
 * extraction fails we still forward the raw text — teammates get the heads
 * up, just without a map marker.
 *
 * Dedupe: identical text is only forwarded once per 10s — some setups echo
 * the same alert through multiple chat channels.
 */
public final class SnitchRelay {
    private SnitchRelay() { }

    private static final Pattern PLAYER =
            Pattern.compile("\\*\\s*(?<player>[A-Za-z0-9_]{3,16})");
    private static final Pattern NAMED_EVENT = Pattern.compile(
            "(?<snitch>[^:]{2,64}?)\\s*:\\s*(?<player>[A-Za-z0-9_]{3,16})\\s+" +
                    "(?<action>entered snitch|logged (?:in|out))\\s+at\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_INTERACTION = Pattern.compile(
            "(?<snitch>[^:]{2,64}?)\\s*:\\s*(?<player>[A-Za-z0-9_]{3,16})\\s+" +
                    "(?<action>(?!entered snitch\\b|logged (?:in|out)\\b).{2,96}?)\\s+at\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SNITCH =
            Pattern.compile("(?i)entered snitch at\\s+(?<snitch>\\S+)");
    private static final Pattern COORDS = Pattern.compile(
            "\\[\\s*(?<world>\\S+)\\s+(?<x>-?\\d+)\\s+(?<y>-?\\d+)\\s+(?<z>-?\\d+)\\s*\\]");
    private static final Pattern COORDS_PAREN = Pattern.compile(
            "\\(\\s*(?<x>-?\\d+)[,\\s]+(?<y>-?\\d+)[,\\s]+(?<z>-?\\d+)\\s*\\)");

    private static final long DEDUPE_MS = 10_000;
    private static final Map<String, Long> recent = new HashMap<>();

    /** Wired to ClientReceiveMessageEvents.GAME — game/system chat only. */
    public static void onGameMessage(Text message, boolean overlay) {
        if (overlay) return;
        var cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.snitchRelay) return;

        String text = message.getString();
        if (text == null || text.isEmpty()) return;
        // Never forward our own status lines — they can quote the original
        // alert text and would loop the detection.
        if (text.startsWith("[OpenIntel]")) return;

        Pattern detection;
        try {
            detection = Pattern.compile(cfg.snitchPattern);
        } catch (Exception e) {
            return; // bad user regex — fail closed, don't forward everything
        }
        if (!detection.matcher(text).find()) return;
        if (!dedupe(text)) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "snitch");
        msg.addProperty("message", text);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            msg.addProperty("reporter", client.player.getGameProfile().name());
        }

        String player = null, snitchName = null, world = null;
        int x = 0, y = 0, z = 0;
        boolean hasCoords = false;

        Matcher nm = NAMED_EVENT.matcher(text);
        String eventKind = "event";
        if (!nm.find()) {
            nm = NAMED_INTERACTION.matcher(text);
            eventKind = "interaction";
        }
        if (nm.find(0)) {
            player = nm.group("player");
            snitchName = nm.group("snitch").replaceAll("^[+\\s*]+|[+\\s*]+$", "");
            msg.addProperty("player", player);
            msg.addProperty("snitch", snitchName);
            msg.addProperty("action", nm.group("action").trim());
            msg.addProperty("eventKind", eventKind);
        }
        if (player == null) {
            Matcher pm = PLAYER.matcher(text);
            if (pm.find()) {
                player = pm.group("player");
                msg.addProperty("player", player);
            }
        }
        if (snitchName == null) {
            Matcher sm = SNITCH.matcher(text);
            if (sm.find()) {
                snitchName = sm.group("snitch");
                if (snitchName.startsWith("(") || snitchName.startsWith("[")) {
                    snitchName = null;      // grabbed a coord block, not a name
                } else {
                    msg.addProperty("snitch", snitchName);
                }
            }
        }

        Matcher cm = COORDS.matcher(text);
        if (cm.find()) {
            try {
                world = cm.group("world");
                x = Integer.parseInt(cm.group("x"));
                y = Integer.parseInt(cm.group("y"));
                z = Integer.parseInt(cm.group("z"));
                msg.addProperty("world", world);
                msg.addProperty("x", x);
                msg.addProperty("y", y);
                msg.addProperty("z", z);
                hasCoords = true;
            } catch (NumberFormatException ignored) { }
        }
        if (!hasCoords) {
            // "snitch at (x, y, z)" — parens, no world name.
            Matcher pc = COORDS_PAREN.matcher(text);
            if (pc.find()) {
                try {
                    x = Integer.parseInt(pc.group("x"));
                    y = Integer.parseInt(pc.group("y"));
                    z = Integer.parseInt(pc.group("z"));
                    msg.addProperty("x", x);
                    msg.addProperty("y", y);
                    msg.addProperty("z", z);
                    hasCoords = true;
                } catch (NumberFormatException ignored) { }
            }
        }

        // Local marker now — the relay echo refreshes the same hit later, and
        // this still works if the relay is unreachable.
        if (hasCoords && snitchName != null && player != null) {
            String me = client.player != null ? client.player.getGameProfile().name() : "?";
            OpenIntelClient.tracker().addSnitchHit(
                    snitchName, player, me, x, y, z, world, System.currentTimeMillis());
        }

        OpenIntelClient.relay().send(msg);
        EventFeed.add("📡 Snitch: " + compact(text), 0xFFFFAA00);
    }

    private static boolean dedupe(String text) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUPE_MS) it.remove();
        }
        Long last = recent.get(text);
        if (last != null && now - last < DEDUPE_MS) return false;
        recent.put(text, now);
        return true;
    }

    /** Trim a long alert line down for the feed. */
    private static String compact(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > 64 ? t.substring(0, 61) + "..." : t;
    }
}
