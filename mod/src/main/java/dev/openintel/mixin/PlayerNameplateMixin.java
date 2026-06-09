package dev.openintel.mixin;

import dev.openintel.OpenIntelClient;
import dev.openintel.allegiance.AllegianceManager.Allegiance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps nameplates consistent with the rest of the mod: the vanilla floating
 * name above any player in render distance is tinted with their allegiance
 * color (focus purple, friend green, ally purple, enemy red, neutral grey).
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerNameplateMixin {

    @ModifyVariable(
            method = "renderLabelIfPresent(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/text/Text;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IF)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Text openintel$recolorNameplate(Text original, AbstractClientPlayerEntity player,
                                            Text text, MatrixStack matrices,
                                            VertexConsumerProvider consumers, int light, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || player == client.player) return original;

        Allegiance a = OpenIntelClient.allegiances().of(player.getGameProfile().getName());
        int rgb = a.argb & 0x00FFFFFF; // Text styles take RGB without alpha
        return original.copy().styled(style -> style.withColor(rgb));
    }
}
