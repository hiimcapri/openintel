package dev.openintel.mixin;

import dev.openintel.render.UiFont;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.StyleSpriteSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    @ModifyVariable(method = "getGlyphs", at = @At("HEAD"), argsOnly = true)
    private StyleSpriteSource openintel$selectUiFont(StyleSpriteSource source) {
        return UiFont.select(source);
    }
}
