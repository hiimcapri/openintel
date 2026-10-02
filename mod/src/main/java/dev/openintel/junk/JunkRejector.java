package dev.openintel.junk;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

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

    public static void tick(MinecraftClient client) {
        var cfg = OpenIntelClient.config();
        if (!cfg.junkReject) { sweep = 0; return; }
        if (++sweep < 5) return;               // ~4 sweeps/sec, light on packets
        sweep = 0;
        if (client.player == null || client.interactionManager == null) return;
        if (client.currentScreen != null) return;                  // never click under an open GUI
        if (client.player.isCreative() || client.player.isSpectator()) return;
        if (!client.player.isAlive()) return;

        Set<String> junk = normalized(cfg.junkItems);
        if (junk.isEmpty()) return;
        var handler = client.player.playerScreenHandler;
        // PlayerScreenHandler: 9–35 main inventory, 36–44 hotbar.
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || !junk.contains(id(stack))) continue;
            client.interactionManager.clickSlot(handler.syncId, slot, 1,
                    SlotActionType.THROW, client.player);
            return;                                              // one stack per sweep
        }
    }

    private static String id(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
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
