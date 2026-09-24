package dev.openintel.relic;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Detects relic maps: filled_map items carrying a `map_decorations`
 * component with a `target_x` marker. The component stores world
 * coordinates directly, so no pixel math is needed — the map image
 * itself is just a template per region.
 *
 * Scans the inventory at ~1Hz and reconciles: a relic exists exactly
 * while a map pointing at it is in the inventory. New relics are
 * reported in chat and tracked in `active`, the source of truth every
 * map surface reads: JmBridge reconciles it onto JourneyMap as a
 * MarkerOverlay, Xaero paints it, MarkerHud draws it in-world. Relics
 * drop out of all three when the map leaves the inventory or the
 * relic is claimed.
 */
public final class RelicMaps {

    /** A detected relic: block coords plus dimension id string. */
    public record Relic(int x, int z, String dimension) { }

    private static final Identifier TARGET_X = Identifier.of("minecraft", "target_x");
    /** Keyed "x:z" — every map for the same relic shares coords. */
    private static final Map<String, Relic> active = new LinkedHashMap<>();
    /** Coords claimed this session — a kept map must not re-add them. */
    private static final Set<String> claimed = new HashSet<>();
    private static int ticks;

    private RelicMaps() { }

    /** Live relic points, for overlay renderers (Xaero bridge). */
    public static Collection<Relic> all() {
        return active.values();
    }

    public static void tick(MinecraftClient mc) {
        if (++ticks % 20 != 0 || mc.player == null || mc.world == null) return;

        Map<String, Relic> found = new LinkedHashMap<>();
        for (var stack : mc.player.getInventory()) {
            if (!stack.isOf(Items.FILLED_MAP)) continue;
            var decos = stack.get(DataComponentTypes.MAP_DECORATIONS);
            if (decos == null) continue;
            decos.decorations().forEach((key, d) -> {
                if (!d.type().getKey().map(k -> k.getValue().equals(TARGET_X)).orElse(false)) {
                    return;
                }
                int x = (int) Math.round(d.x());
                int z = (int) Math.round(d.z());
                if (claimed.contains(x + ":" + z)) return;
                // MapState carries the dimension when it has arrived;
                // the component doesn't, so fall back to the player's.
                RegistryKey<World> dim = mc.world.getRegistryKey();
                var state = FilledMapItem.getMapState(stack, mc.world);
                if (state != null) dim = state.dimension;
                found.putIfAbsent(x + ":" + z,
                        new Relic(x, z, dim.getValue().toString()));
            });
        }

        // New relics — toast; surfaces pick them up from `active`.
        for (var e : found.entrySet()) {
            if (active.putIfAbsent(e.getKey(), e.getValue()) != null) continue;
            Relic r = e.getValue();
            OpenIntelClient.status("relic map detected at " + r.x() + ", " + r.z()
                    + " (" + Identifier.of(r.dimension()).getPath() + ")");
        }

        // Stale relics — the backing map left the inventory.
        active.entrySet().removeIf(e -> !found.containsKey(e.getKey()));
    }

    /**
     * The server announces a pickup over the actionbar ("You found a
     * relic!") — that's our signal to drop the nearest relic marker
     * from every surface: our active set (Xaero overlay) and JM's
     * waypoint store via the remover hook.
     */
    public static void onGameMessage(net.minecraft.text.Text message, boolean overlay) {
        if (overlay) onOverlayMessage(message);
    }

    /** Shared by the GAME event and the InGameHud overlay mixin. */
    public static void onOverlayMessage(net.minecraft.text.Text message) {
        if (!message.getString().toLowerCase(java.util.Locale.ROOT)
                .contains("found a relic")) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        String dim = mc.world.getRegistryKey().getValue().toString();
        double px = mc.player.getX(), pz = mc.player.getZ();

        // Nearest active relic within claim radius — 96m is generous,
        // relics are sparse and you must stand on it to claim.
        Relic best = null;
        String bestKey = null;
        double bestD = 96.0 * 96.0;
        for (var e : active.entrySet()) {
            Relic r = e.getValue();
            if (!dim.equals(r.dimension())) continue;
            double d = (r.x() - px) * (r.x() - px) + (r.z() - pz) * (r.z() - pz);
            if (d < bestD) { bestD = d; best = r; bestKey = e.getKey(); }
        }
        if (best == null) return;
        active.remove(bestKey);
        claimed.add(bestKey);
        OpenIntelClient.status("relic claimed at " + best.x() + ", " + best.z());
    }

    /** Fresh session — tracked relics and claims are session-scoped. */
    public static void reset() {
        active.clear();
        claimed.clear();
    }
}
