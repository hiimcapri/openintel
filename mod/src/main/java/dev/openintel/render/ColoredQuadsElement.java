package dev.openintel.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.render.TextureSetup;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fc;

import java.util.function.Consumer;

/**
 * Generic GUI element that submits arbitrary position-color quads through
 * the retained GUI render state — the same mechanism vanilla uses for
 * fill(), but free-form. Emit a triangle as a quad with two coincident
 * vertices; per-vertex colors give interpolated gradients (soft edges).
 */
public record ColoredQuadsElement(Matrix3x2fc pose, Consumer<VertexConsumer> painter,
                                  ScreenRectangle bounds) implements GuiElementRenderState {

    @Override
    public RenderPipeline pipeline() { return RenderPipelines.GUI; }

    @Override
    public TextureSetup textureSetup() { return TextureSetup.noTexture(); }

    @Override
    public @Nullable ScreenRectangle scissorArea() { return null; }

    @Override
    public void buildVertices(VertexConsumer vertices) { painter.accept(vertices); }
}
