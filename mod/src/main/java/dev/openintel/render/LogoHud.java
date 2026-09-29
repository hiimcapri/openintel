package dev.openintel.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.openintel.OpenIntelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class LogoHud {
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("openintel", "textures/gui/white.png");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("openintel", "pipeline/logo"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("openintel", "core/logo"))
            .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                    com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS, false))
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

    public static void render(GuiGraphicsExtractor context) {
        var config = OpenIntelClient.config();
        var client = Minecraft.getInstance();
        if (config == null || !config.logoHudEnabled || client.player == null || client.level == null
                || client.gui.hud.isHidden() || client.gui.screen() != null) return;
        var bounds = layout(context.guiWidth(), context.guiHeight(),
                client.getWindow().getWidth(), client.getWindow().getHeight());
        if (bounds.width <= 0 || bounds.height <= 0) return;
        var pose = context.pose();
        pose.pushMatrix();
        try {
            pose.translate(bounds.x, bounds.y);
            pose.scale(bounds.width, bounds.height);
            context.blit(PIPELINE, WHITE, 0, 0, 0f, 0f, 1, 1, 1, 1, 0xFFFFFFFF);
        } finally {
            pose.popMatrix();
        }
    }
}
