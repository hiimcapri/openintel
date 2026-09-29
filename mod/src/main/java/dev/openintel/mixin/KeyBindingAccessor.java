package dev.openintel.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the attack macro inject a real key press into the vanilla input path:
 * bumping clickCount makes KeyMapping.consumeClick() fire once, which is what
 * Minecraft's input handler consumes to swing.
 */
@Mixin(KeyMapping.class)
public interface KeyBindingAccessor {
    @Accessor("clickCount")
    int openintel$getTimesPressed();

    @Accessor("clickCount")
    void openintel$setTimesPressed(int clickCount);
}
