package dev.openintel.api.hud;

import net.minecraft.resources.Identifier;

public interface HudRegistration extends AutoCloseable {
    Identifier id();

    @Override
    void close();
}
