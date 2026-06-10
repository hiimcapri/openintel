package dev.openintel.mixin;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps nameplates consistent with the rest of the mod: the vanilla floating
 * name above any player in render distance is tinted with their allegiance
 * color (focus purple, friend green, ally purple, enemy red, neutral grey).
 *
 * The label text lives on the render state (EntityRenderState.nameTag), so
 * the tint is applied when the state is extracted each frame.
 */
@Mixin(AvatarRenderer.class)
public abstract class PlayerNameplateMixin {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL")
    )
    private void openintel$recolorNameplate(Avatar entity, AvatarRenderState state,
                                            float partialTick, CallbackInfo ci) {
        if (state.nameTag == null) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || entity == client.player) return;

        Allegiance a = OpenIntelClient.allegiances().of(entity.getName().getString());
        int rgb = a.argb & 0x00FFFFFF; // text styles take RGB without alpha
        state.nameTag = state.nameTag.copy().withStyle(style -> style.withColor(rgb));
    }
}
