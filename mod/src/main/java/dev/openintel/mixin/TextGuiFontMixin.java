package dev.openintel.mixin;

import dev.openintel.render.UiFont;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiTextRenderState.class)
public abstract class TextGuiFontMixin {
    @ModifyVariable(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/Object;<init>()V",
            shift = At.Shift.AFTER, remap = false), argsOnly = true)
    private FormattedCharSequence openintel$captureHudFont(FormattedCharSequence text) {
        return UiFont.capture(text);
    }
}
