package dev.openintel.mixin;

import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextRenderer.class)
public interface TextRendererAccessor {
    @Accessor("fonts")
    TextRenderer.GlyphsProvider openintel$getGlyphsProvider();
}
