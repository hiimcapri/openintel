package dev.openintel.mixin;

import dev.openintel.render.UiFont;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(TextGuiElementRenderState.class)
public abstract class TextGuiFontMixin {
    @ModifyVariable(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/Object;<init>()V",
            shift = At.Shift.AFTER, remap = false), argsOnly = true)
    private OrderedText openintel$captureHudFont(OrderedText text) {
        return UiFont.capture(text);
    }
}
