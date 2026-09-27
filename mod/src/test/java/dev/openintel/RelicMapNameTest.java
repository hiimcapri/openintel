package dev.openintel;

import dev.openintel.relic.RelicMaps;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public final class RelicMapNameTest {
    public static void main(String[] args) throws Exception {
        var method = RelicMaps.class.getDeclaredMethod("mapName", LoreComponent.class, Text.class);
        method.setAccessible(true);
        Text title = Text.literal("Treasure Map").formatted(Formatting.AQUA);
        var lore = new LoreComponent(List.of(
                Text.literal("White Rabbit's Timepiece").formatted(Formatting.RED), Text.literal("Original")));
        check(method.invoke(null, lore, title).equals("White Rabbit's Timepiece"), "Relic lore takes priority over generic map title");
        lore = new LoreComponent(List.of(Text.literal("  "),
                Text.literal("  White Rabbit's Timepiece  ").formatted(Formatting.RED), Text.literal("Original")));
        check(method.invoke(null, lore, title).equals("White Rabbit's Timepiece"), "Blank lore skipped and whitespace trimmed");
        lore = new LoreComponent(List.of(Text.literal("\u00a7cUpdated Relic\u00a7r")));
        check(method.invoke(null, lore, title).equals("Updated Relic"), "Updated lore is read without legacy formatting codes");
        check(method.invoke(null, null, Text.literal("Named Relic Map")).equals("Named Relic Map"), "Item title fallback");
        check(method.invoke(null, LoreComponent.DEFAULT, title).equals("Treasure Map"), "Empty lore fallback");
        check(method.invoke(null, null, Text.empty()).equals("Relic"), "Blank title fallback has no coordinates");
        check(method.invoke(null, null, null).equals("Relic"), "Missing components fallback");
        System.out.println("Relic map name tests passed");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
