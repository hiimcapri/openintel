package dev.openintel.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
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
                                  ScreenRect bounds) implements SimpleGuiElementRenderState {

    @Override
    public RenderPipeline pipeline() { return RenderPipelines.GUI; }

    @Override
    public TextureSetup textureSetup() { return TextureSetup.empty(); }

    @Override
    public @Nullable ScreenRect scissorArea() { return null; }

    @Override
    public void setupVertices(VertexConsumer vertices) { painter.accept(vertices); }
}
