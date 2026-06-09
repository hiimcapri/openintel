package dev.openintel.mixin;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps nameplates consistent with the rest of the mod: the vanilla floating
 * name above any player in render distance is tinted with their allegiance
 * color (focus purple, friend green, ally purple, enemy red, neutral grey).
 *
 * Since 1.21.2 the label text lives on the render state (EntityRenderState
 * .displayName) instead of being passed to renderLabelIfPresent, so the tint
 * is applied when the state is built each frame.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerNameplateMixin {

    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void openintel$recolorNameplate(PlayerLikeEntity entity, PlayerEntityRenderState state,
                                            float tickProgress, CallbackInfo ci) {
        if (state.displayName == null || state.playerName == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || state.id == client.player.getId()) return;

        Allegiance a = OpenIntelClient.allegiances().of(state.playerName.getString());
        int rgb = a.argb & 0x00FFFFFF; // Text styles take RGB without alpha
        state.displayName = state.displayName.copy().styled(style -> style.withColor(rgb));
    }
}
