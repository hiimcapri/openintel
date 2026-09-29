package dev.openintel.mixin;

import dev.openintel.relic.RelicMaps;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Actionbar/overlay text (the above-hotbar line) arrives through
 * setOverlayMessage on both paths — SetActionBarText packets and
 * overlay game messages — which ClientReceiveMessageEvents doesn't
 * fully cover. This is where "You found a relic!" shows up.
 */
@Mixin(Hud.class)
public abstract class OverlayMessageMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void openintel$overlayMessage(Component message, boolean tinted, CallbackInfo ci) {
        RelicMaps.onOverlayMessage(message);
    }
}
