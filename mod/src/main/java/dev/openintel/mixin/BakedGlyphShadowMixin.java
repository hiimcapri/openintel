package dev.openintel.mixin;

import com.mojang.blaze3d.textures.GpuTextureView;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(BakedSheetGlyph.class)
public abstract class BakedGlyphShadowMixin {
    @Shadow @Final private GpuTextureView textureView;

    @Unique
    private boolean openintel$isCleanFont() {
        return textureView != null && UiFont.smoothAtlas(
                textureView.texture().getLabel(), textureView.texture().getFormat());
    }

    @ModifyVariable(method = {
            "createGlyph(FFIILnet/minecraft/network/chat/Style;FF)Lnet/minecraft/client/gui/font/TextRenderable$Styled;",
            "createEffect(FFFFFIIF)Lnet/minecraft/client/gui/font/TextRenderable;"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int openintel$disableShadow(int color) {
        return openintel$isCleanFont() ? UiFont.shadowColor(color) : color;
    }

    @ModifyVariable(method = "createGlyph(FFIILnet/minecraft/network/chat/Style;FF)Lnet/minecraft/client/gui/font/TextRenderable$Styled;",
            at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private float openintel$glyphShadowOffset(float offset) {
        return openintel$isCleanFont() ? UiFont.shadowOffset(offset) : offset;
    }

    @ModifyVariable(method = "createEffect(FFFFFIIF)Lnet/minecraft/client/gui/font/TextRenderable;",
            at = @At("HEAD"), argsOnly = true, ordinal = 5)
    private float openintel$decorationShadowOffset(float offset) {
        return openintel$isCleanFont() ? UiFont.shadowOffset(offset) : offset;
    }
}
