package dev.openintel;

import dev.openintel.relic.RelicMaps;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

public final class RelicMapNameTest {
    public static void main(String[] args) throws Exception {
        var method = RelicMaps.class.getDeclaredMethod("mapName", ItemLore.class, Component.class);
        method.setAccessible(true);
        Component title = Component.literal("Treasure Map").withStyle(ChatFormatting.AQUA);
        var lore = new ItemLore(List.of(
                Component.literal("White Rabbit's Timepiece").withStyle(ChatFormatting.RED), Component.literal("Original")));
        check(method.invoke(null, lore, title).equals("White Rabbit's Timepiece"), "Relic lore takes priority over generic map title");
        lore = new ItemLore(List.of(Component.literal("  "),
                Component.literal("  White Rabbit's Timepiece  ").withStyle(ChatFormatting.RED), Component.literal("Original")));
        check(method.invoke(null, lore, title).equals("White Rabbit's Timepiece"), "Blank lore skipped and whitespace trimmed");
        lore = new ItemLore(List.of(Component.literal("\u00a7cUpdated Relic\u00a7r")));
        check(method.invoke(null, lore, title).equals("Updated Relic"), "Updated lore is read without legacy formatting codes");
        check(method.invoke(null, null, Component.literal("Named Relic Map")).equals("Named Relic Map"), "Item title fallback");
        check(method.invoke(null, ItemLore.EMPTY, title).equals("Treasure Map"), "Empty lore fallback");
        check(method.invoke(null, null, Component.empty()).equals("Relic"), "Blank title fallback has no coordinates");
        check(method.invoke(null, null, null).equals("Relic"), "Missing components fallback");
        System.out.println("Relic map name tests passed");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
