package dev.openintel;

import dev.openintel.tracker.Tracker;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Component;
import java.lang.reflect.Method;
import java.util.Objects;

public final class SnitchDimensionTest {
    public static void main(String[] args) throws Exception {
        for (String dimension : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            Component coordinates = Component.literal("(1201, 67, -500)").withStyle(style ->
                    style.withHoverEvent(new HoverEvent.ShowText(Component.literal(dimension))));
            Component message = Component.literal("A Snitch: Player123 entered snitch at ").append(coordinates);
            equal(dimension, SnitchRelay.hoverWorld(message));
            equal(dimension, SnitchRelay.hoverWorld(Component.literal("root").withStyle(style ->
                    style.withHoverEvent(new HoverEvent.ShowText(Component.literal(dimension))))));
        }
        equal(null, SnitchRelay.hoverWorld(Component.literal("EndSpawn: Player123 entered snitch at (1, 2, 3)")));
        equal(null, SnitchRelay.hoverWorld(Component.literal("test").withStyle(style ->
                style.withHoverEvent(new HoverEvent.ShowText(Component.literal("minecraft:the_end_fake"))))));
        Component conflicting = Component.literal("one").withStyle(style -> style.withHoverEvent(
                new HoverEvent.ShowText(Component.literal("minecraft:the_end"))))
                .append(Component.literal("two").withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(Component.literal("minecraft:overworld")))));
        equal("openintel:unknown", SnitchRelay.hoverWorld(conflicting));
        Method bind = Tracker.class.getDeclaredMethod("bindDim", String.class);
        bind.setAccessible(true);
        equal(null, bind.invoke(null, (Object) null));
        equal(null, bind.invoke(null, "  "));
        equal("openintel:unknown", bind.invoke(null, "unknown-world"));
        equal("minecraft:the_end", bind.invoke(null, "world_the_end"));
        equal("minecraft:the_nether", bind.invoke(null, "world_nether"));
        equal("minecraft:overworld", bind.invoke(null, "world"));
        equal("minecraft:the_end", bind.invoke(null, "minecraft:the_end"));
        Tracker tracker = new Tracker();
        tracker.addSnitchHit("OverworldVault", "Player123", "Reporter123", 1, 2, 3, null, 1);
        tracker.addSnitchHit("EndSpawn", "Player123", "Reporter123", 1, 2, 3, "openintel:unknown", 1);
        if (tracker.snitchHits().iterator().hasNext()) throw new AssertionError("Unknown world created a marker");
        System.out.println("Snitch dimension tests passed");
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
