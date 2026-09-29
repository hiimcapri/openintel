package dev.openintel.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.openintel.OpenIntelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

public final class LogoHud {
    private static final Identifier WHITE = Identifier.of("openintel", "textures/gui/white.png");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("openintel", "pipeline/logo"))
            .withFragmentShader(Identifier.of("openintel", "core/logo"))
            .withDepthWrite(false)
            .build();

    public record Bounds(float x, float y, float width, float height) { }

    private LogoHud() { }

    public static void registerPipeline() {
        RenderPipelines.register(PIPELINE);
    }

    public static Bounds layout(int width, int height, int pixelWidth, int pixelHeight) {
        if (width <= 0 || height <= 0 || pixelWidth <= 0 || pixelHeight <= 0) return new Bounds(0, 0, 0, 0);
        int shorter = Math.min(pixelWidth, pixelHeight);
        int margin = Math.min(Math.round(Math.clamp(shorter * 0.01f, 6f, 16f)), shorter / 4);
        int size = Math.min(Math.round(Math.clamp(shorter * 0.042f, 28f, 72f)), shorter - margin * 2);
        float sx = width / (float) pixelWidth, sy = height / (float) pixelHeight;
        return new Bounds((pixelWidth - margin - size) * sx, (pixelHeight - margin - size) * sy, size * sx, size * sy);
    }

    public static void render(DrawContext context) {
        var config = OpenIntelClient.config();
        var client = MinecraftClient.getInstance();
        if (config == null || !config.logoHudEnabled || client.player == null || client.world == null
                || client.options.hudHidden || client.currentScreen != null) return;
        var bounds = layout(context.getScaledWindowWidth(), context.getScaledWindowHeight(),
                client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());
        if (bounds.width <= 0 || bounds.height <= 0) return;
        var pose = context.getMatrices();
        pose.pushMatrix();
        try {
            pose.translate(bounds.x, bounds.y);
            pose.scale(bounds.width, bounds.height);
            context.drawTexture(PIPELINE, WHITE, 0, 0, 0f, 0f, 1, 1, 1, 1, 0xFFFFFFFF);
        } finally {
            pose.popMatrix();
        }
    }
}
