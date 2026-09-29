package dev.openintel.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Font.class)
public interface TextRendererAccessor {
    @Accessor("provider")
    Font.Provider openintel$getGlyphsProvider();
}
