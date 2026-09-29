package dev.openintel.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the attack macro inject a real key press into the vanilla input path:
 * bumping timesPressed makes KeyBinding.wasPressed() fire once, which is what
 * MinecraftClient's input handler consumes to swing.
 */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    int openintel$getTimesPressed();

    @Accessor("timesPressed")
    void openintel$setTimesPressed(int timesPressed);
}
