package dev.openintel.mixin;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Focus targets get the spectator-style outline in OpenIntel red while they
 * are actually loaded. The label is left untouched — the body outline is the
 * signal. Remote-only focus targets keep their normal relay marker.
 */
@Mixin(net.minecraft.client.render.entity.EntityRenderer.class)
public abstract class FocusGlowMixin {

    @Inject(method = "updateRenderState", at = @At("TAIL"))
    private void openintel$focusOutline(Entity entity, EntityRenderState state,
                                        float tickProgress, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity player)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || player == client.player) return;
        if (OpenIntelClient.allegiances().of(player.getName().getString()) != Allegiance.FOCUS) return;
        state.outlineColor = 0xFFFF4444;
    }
}
