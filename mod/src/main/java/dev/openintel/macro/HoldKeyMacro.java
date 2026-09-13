package dev.openintel.macro;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.function.Supplier;

/**
 * Toggleable key-holder: while active it keeps a vanilla bind (attack or
 * use) pressed as if your finger were on it. Refuses to engage mid-eat so a
 * held use key doesn't fight the item you're already consuming.
 */
public final class HoldKeyMacro extends InputMacro {
    private final Supplier<KeyBinding> target;
    private final String label;

    public HoldKeyMacro(KeyBinding toggle, Supplier<KeyBinding> target, String label) {
        super(toggle);
        this.target = target;
        this.label = label;
    }

    @Override
    protected boolean canEngage(MinecraftClient mc) {
        return super.canEngage(mc) && !mc.player.isUsingItem();
    }

    @Override
    protected void onEngage(MinecraftClient mc) { target.get().setPressed(true); }

    @Override
    protected void onRelease(MinecraftClient mc) { target.get().setPressed(false); }

    @Override
    protected String name() { return label; }
}
