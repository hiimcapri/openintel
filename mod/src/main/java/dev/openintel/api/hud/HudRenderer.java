package dev.openintel.api.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;

@FunctionalInterface
public interface HudRenderer {
    void render(GuiGraphicsExtractor localOriginContext, HudSize size, float tickDelta);
}
