package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.openintel.render.UiFont;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChatScreen.class)
public abstract class ChatInputFontMixin {
    @ModifyExpressionValue(method = "init", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;advanceValidatingTextRenderer:Lnet/minecraft/client/font/TextRenderer;"))
    private TextRenderer openintel$chatInputFont(TextRenderer original) {
        return UiFont.chatInputRenderer(((TextRendererAccessor) original).openintel$getGlyphsProvider());
    }
}
