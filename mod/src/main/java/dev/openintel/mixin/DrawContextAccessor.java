package dev.openintel.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the retained GUI render state for custom element submission. */
@Mixin(DrawContext.class)
public interface DrawContextAccessor {
    @Accessor("state")
    GuiRenderState openintel$state();
}
