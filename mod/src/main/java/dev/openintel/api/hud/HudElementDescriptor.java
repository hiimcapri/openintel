package dev.openintel.api.hud;

import net.minecraft.util.Identifier;

import java.util.Objects;

public record HudElementDescriptor(Identifier id, String name, HudSize size, HudPosition defaultPosition,
                                   HudPosition position, boolean enabled, boolean runtimeFailed) {
    public HudElementDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(defaultPosition, "defaultPosition");
        Objects.requireNonNull(position, "position");
    }
}
