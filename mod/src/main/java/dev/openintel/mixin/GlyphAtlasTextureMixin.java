package dev.openintel.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FontTexture.class)
public abstract class GlyphAtlasTextureMixin extends AbstractTexture {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void openintel$clearFontPadding(CallbackInfo ci) {
        if (!UiFont.smoothAtlas(texture.getLabel(), texture.getFormat())) return;
        try (var blank = new NativeImage(NativeImage.Format.LUMINANCE,
                texture.getWidth(0), texture.getHeight(0), true)) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, blank);
        }
    }
}
