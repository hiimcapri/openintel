package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChatComponent.class)
public abstract class ChatHudFontMixin {
    @WrapMethod(method = "refreshTrimmedMessages")
    private void openintel$chatLayoutFont(Operation<Void> original) {
        UiFont.withHudFont(original::call);
    }

    @WrapMethod(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V")
    private void openintel$chatDrawFont(GuiGraphicsExtractor context, Font renderer,
                                       int ticks, int mouseX, int mouseY,
                                       ChatComponent.DisplayMode mode, boolean hide,
                                       Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(context, renderer, ticks, mouseX, mouseY, mode, hide));
    }

    @WrapMethod(method = "captureClickableText(Lnet/minecraft/client/gui/ActiveTextCollector;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V")
    private void openintel$chatInteractionFont(ActiveTextCollector consumer, int x, int y,
                                              ChatComponent.DisplayMode mode,
                                              Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(consumer, x, y, mode));
    }
}
