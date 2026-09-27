package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.render.UiFont;
import net.minecraft.client.font.DrawnTextConsumer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ChatHud.class)
public abstract class ChatHudFontMixin {
    @WrapMethod(method = "addVisibleMessage")
    private void openintel$chatLayoutFont(ChatHudLine message, Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(message));
    }

    @WrapMethod(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V")
    private void openintel$chatDrawFont(DrawContext context, TextRenderer renderer,
                                       int ticks, int mouseX, int mouseY, boolean focused, boolean hide,
                                       Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(context, renderer, ticks, mouseX, mouseY, focused, hide));
    }

    @WrapMethod(method = "render(Lnet/minecraft/client/font/DrawnTextConsumer;IIZ)V")
    private void openintel$chatInteractionFont(DrawnTextConsumer consumer, int x, int y, boolean focused,
                                              Operation<Void> original) {
        UiFont.withHudFont(() -> original.call(consumer, x, y, focused));
    }
}
