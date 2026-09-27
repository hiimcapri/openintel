package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(InGameHud.class)
public abstract class HudTextFontMixin {
    @WrapMethod(method = {"renderOverlayMessage", "renderTitleAndSubtitle"})
    private void openintel$hudTextFont(DrawContext context, RenderTickCounter tickCounter, Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(context, tickCounter));
    }
}
