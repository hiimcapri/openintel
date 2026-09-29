package dev.openintel.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.openintel.render.UiFont;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.client.gui.render.TextureSetup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlyphRenderState.class)
public abstract class GlyphGuiElementMixin {
    @Shadow @Final private TextRenderable renderable;

    @Unique
    private boolean openintel$isSmoothFont() {
        var view = renderable.textureView();
        return view != null && UiFont.smoothAtlas(view.texture().getLabel(), view.texture().getFormat());
    }

    @Inject(method = "pipeline", at = @At("HEAD"), cancellable = true)
    private void openintel$fontPipeline(CallbackInfoReturnable<RenderPipeline> cir) {
        if (openintel$isSmoothFont()) cir.setReturnValue(UiFont.PIPELINE);
    }

    @Inject(method = "textureSetup", at = @At("HEAD"), cancellable = true)
    private void openintel$fontSampler(CallbackInfoReturnable<TextureSetup> cir) {
        if (openintel$isSmoothFont()) {
            cir.setReturnValue(TextureSetup.singleTexture(renderable.textureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
        }
    }
}
