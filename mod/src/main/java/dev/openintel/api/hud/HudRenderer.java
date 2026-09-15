package dev.openintel.api.hud;

import net.minecraft.client.gui.DrawContext;

@FunctionalInterface
public interface HudRenderer {
    void render(DrawContext localOriginContext, HudSize size, float tickDelta);
}
