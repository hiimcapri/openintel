package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChatScreen.class)
public abstract class ChatInputFontMixin {
    @ModifyExpressionValue(method = "init", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;fontFilterFishy:Lnet/minecraft/client/gui/Font;"))
    private Font openintel$chatInputFont(Font original) {
        return UiFont.chatInputRenderer(((TextRendererAccessor) original).openintel$getGlyphsProvider());
    }
}
