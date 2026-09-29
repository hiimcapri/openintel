package dev.openintel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "journeymap.client.render.draw.BaseOverlayDrawStep", remap = false)
public abstract class JourneyMapTextMixin {
    @Shadow(remap = false)
    public abstract String getModId();

    @WrapMethod(method = "drawText", remap = false, require = 0)
    private void openintel$mapLabelFont(GuiGraphicsExtractor context, double offsetX, double offsetY,
                                       @Coerce Object renderer, double scale, double rotation,
                                       Operation<Void> original) {
        UiFont.withMapFont(getModId(), () -> original.call(context, offsetX, offsetY, renderer, scale, rotation));
    }
}
