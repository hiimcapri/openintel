package dev.openintel.junk;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Client-side junk filter. Dedicated servers own item pickup, so the filter
 * can't refuse the pickup itself — instead it throws matching stacks back
 * out the moment they land, one slot per sweep. Same approach the classic
 * Civ junk mods used; works on any server.
 */
public final class JunkRejector {
    private JunkRejector() { }

    private static int sweep = 0;

    public static void tick(Minecraft client) {
        var cfg = OpenIntelClient.config();
        if (!cfg.junkReject) { sweep = 0; return; }
        if (++sweep < 5) return;               // ~4 sweeps/sec, light on packets
        sweep = 0;
        if (client.player == null || client.gameMode == null) return;
        if (client.gui.screen() != null) return;                 // never click under an open GUI
        if (client.player.isCreative() || client.player.isSpectator()) return;
        if (!client.player.isAlive()) return;

        Set<String> junk = normalized(cfg.junkItems);
        if (junk.isEmpty()) return;
        var menu = client.player.inventoryMenu;
        // InventoryMenu: 9–35 main inventory, 36–44 hotbar.
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack.isEmpty() || !junk.contains(id(stack))) continue;
            client.gameMode.handleContainerInput(menu.containerId, slot, 1,
                    ContainerInput.THROW, client.player);
            return;                                              // one stack per sweep
        }
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    static Set<String> normalized(List<String> items) {
        Set<String> out = new HashSet<>();
        for (String item : items) {
            String s = item.toLowerCase(Locale.ROOT).trim();
            if (s.isEmpty()) continue;
            out.add(s.contains(":") ? s : "minecraft:" + s);
        }
        return out;
    }
}
