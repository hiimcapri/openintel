package dev.openintel.macro;

import dev.openintel.OpenIntelClient;
import dev.openintel.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Toggleable auto-presser: while active it injects a discrete press of a
 * vanilla bind every intervalMs — through the vanilla press counter, so
 * each one behaves exactly like a real click. Use it for attack spam or
 * for machine-gunning throwables (XP bottles, pearls) that holding the
 * key fires slower than clicking. Any inventory interaction or screen
 * releases it.
 */
public class IntervalMacro extends InputMacro {
    private final Supplier<KeyBinding> target;
    private final IntSupplier intervalMs;
    private final String label;
    private long lastPress;
    private boolean pressedThisCycle;

    public IntervalMacro(KeyBinding toggle, Supplier<KeyBinding> target,
                         IntSupplier intervalMs, String label) {
        super(toggle);
        this.target = target;
        this.intervalMs = intervalMs;
        this.label = label;
    }

    @Override
    protected void tickActive(MinecraftClient mc) {
        if (pressedThisCycle) {
            pressedThisCycle = false;
            return;
        }
        if (System.currentTimeMillis() - lastPress < intervalMs.getAsInt()) {
            return;
        }

        KeyBindingAccessor key = (KeyBindingAccessor) target.get();
        key.openintel$setTimesPressed(key.openintel$getTimesPressed() + 1);

        pressedThisCycle = true;
        lastPress = System.currentTimeMillis();
    }

    @Override
    protected String name() { return label; }
}
