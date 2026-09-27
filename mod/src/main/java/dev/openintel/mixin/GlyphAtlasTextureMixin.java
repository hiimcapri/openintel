package dev.openintel.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.openintel.render.UiFont;
import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlyphAtlasTexture.class)
public abstract class GlyphAtlasTextureMixin extends AbstractTexture {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void openintel$clearFontPadding(CallbackInfo ci) {
        if (!UiFont.smoothAtlas(glTexture.getLabel(), glTexture.getFormat())) return;
        try (var blank = new NativeImage(NativeImage.Format.LUMINANCE,
                glTexture.getWidth(0), glTexture.getHeight(0), true)) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(glTexture, blank);
        }
    }
}
