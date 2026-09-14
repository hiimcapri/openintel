package dev.openintel;

import dev.openintel.tracker.Tracker;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import java.lang.reflect.Method;
import java.util.Objects;

public final class SnitchDimensionTest {
    public static void main(String[] args) throws Exception {
        for (String dimension : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            Text coordinates = Text.literal("(1201, 67, -500)").styled(style ->
                    style.withHoverEvent(new HoverEvent.ShowText(Text.literal(dimension))));
            Text message = Text.literal("A Snitch: Player123 entered snitch at ").append(coordinates);
            equal(dimension, SnitchRelay.hoverWorld(message));
            equal(dimension, SnitchRelay.hoverWorld(Text.literal("root").styled(style ->
                    style.withHoverEvent(new HoverEvent.ShowText(Text.literal(dimension))))));
        }
        equal(null, SnitchRelay.hoverWorld(Text.literal("EndSpawn: Player123 entered snitch at (1, 2, 3)")));
        equal(null, SnitchRelay.hoverWorld(Text.literal("test").styled(style ->
                style.withHoverEvent(new HoverEvent.ShowText(Text.literal("minecraft:the_end_fake"))))));
        Text conflicting = Text.literal("one").styled(style -> style.withHoverEvent(
                new HoverEvent.ShowText(Text.literal("minecraft:the_end"))))
                .append(Text.literal("two").styled(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(Text.literal("minecraft:overworld")))));
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
