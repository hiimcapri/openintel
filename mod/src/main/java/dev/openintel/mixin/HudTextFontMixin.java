package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Hud.class)
public abstract class HudTextFontMixin {
    @WrapMethod(method = {"extractOverlayMessage", "extractTitle"})
    private void openintel$hudTextFont(GuiGraphicsExtractor context, DeltaTracker tickCounter, Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(context, tickCounter));
    }
}
