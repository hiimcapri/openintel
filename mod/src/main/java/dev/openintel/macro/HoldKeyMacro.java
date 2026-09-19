package dev.openintel.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import java.util.function.Supplier;

/**
 * Toggleable key-holder: while active it keeps a vanilla bind (attack or
 * use) pressed as if your finger were on it. Refuses to engage mid-eat so a
 * held use key doesn't fight the item you're already consuming.
 */
public final class HoldKeyMacro extends InputMacro {
    private final Supplier<KeyMapping> target;
    private final String label;

    public HoldKeyMacro(KeyMapping toggle, Supplier<KeyMapping> target, String label) {
        super(toggle);
        this.target = target;
        this.label = label;
    }

    @Override
    protected boolean canEngage(Minecraft mc) {
        return super.canEngage(mc) && !mc.player.isUsingItem();
    }

    @Override
    protected void onEngage(Minecraft mc) { target.get().setDown(true); }

    @Override
    protected void onRelease(Minecraft mc) { target.get().setDown(false); }

    @Override
    protected String name() { return label; }
}
