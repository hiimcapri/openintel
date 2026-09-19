package dev.openintel.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the retained GUI render state for custom element submission. */
@Mixin(GuiGraphicsExtractor.class)
public interface DrawContextAccessor {
    @Accessor("guiRenderState")
    GuiRenderState openintel$state();
}
