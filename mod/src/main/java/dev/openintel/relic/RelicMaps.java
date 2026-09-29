package dev.openintel.relic;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

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
    public record Relic(int x, int z, String dimension, String name) { }

    private static final Identifier TARGET_X = Identifier.fromNamespaceAndPath("minecraft", "target_x");
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

    private static String mapName(ItemStack stack) {
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) name = stack.get(DataComponents.ITEM_NAME);
        return mapName(stack.get(DataComponents.LORE), name);
    }

    private static String mapName(ItemLore lore, Component name) {
        if (lore != null) {
            for (Component line : lore.lines()) {
                String label = ChatFormatting.stripFormatting(line.getString()).strip();
                if (!label.isEmpty()) return label;
            }
        }
        String label = name == null ? "" : ChatFormatting.stripFormatting(name.getString()).strip();
        return label.isEmpty() ? "Relic" : label;
    }

    public static void tick(Minecraft mc) {
        if (++ticks % 20 != 0 || mc.player == null || mc.level == null) return;

        Map<String, Relic> found = new LinkedHashMap<>();
        for (var stack : mc.player.getInventory()) {
            if (!stack.is(Items.FILLED_MAP)) continue;
            var decos = stack.get(DataComponents.MAP_DECORATIONS);
            if (decos == null) continue;
            String name = mapName(stack);
            decos.decorations().forEach((key, d) -> {
                if (!d.type().unwrapKey().map(k -> k.identifier().equals(TARGET_X)).orElse(false)) {
                    return;
                }
                int x = (int) Math.round(d.x());
                int z = (int) Math.round(d.z());
                if (claimed.contains(x + ":" + z)) return;
                // MapState carries the dimension when it has arrived;
                // the component doesn't, so fall back to the player's.
                ResourceKey<Level> dim = mc.level.dimension();
                var state = MapItem.getSavedData(stack, mc.level);
                if (state != null) dim = state.dimension;
                found.putIfAbsent(x + ":" + z,
                        new Relic(x, z, dim.identifier().toString(), name));
            });
        }

        // New relics — toast; surfaces pick them up from `active`.
        for (var e : found.entrySet()) {
            if (active.put(e.getKey(), e.getValue()) != null) continue;
            Relic r = e.getValue();
            OpenIntelClient.status("relic map detected: " + r.name()
                    + " (" + Identifier.parse(r.dimension()).getPath() + ")");
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
    public static void onGameMessage(net.minecraft.network.chat.Component message, boolean overlay) {
        if (overlay) onOverlayMessage(message);
    }

    /** Shared by the GAME event and the Gui overlay mixin. */
    public static void onOverlayMessage(net.minecraft.network.chat.Component message) {
        if (!message.getString().toLowerCase(java.util.Locale.ROOT)
                .contains("found a relic")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        String dim = mc.level.dimension().identifier().toString();
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
        OpenIntelClient.status("relic claimed: " + best.name());
    }

    /** Fresh session — tracked relics and claims are session-scoped. */
    public static void reset() {
        active.clear();
        claimed.clear();
    }
}
