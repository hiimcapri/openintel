package dev.openintel.macro;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * Base for toggle-on-keypress macros that drive vanilla inputs.
 *
 * The toggle key flips the macro on/off; while on, tickActive() runs every
 * client tick. The macro disengages itself the moment the player loses
 * "input custody": a screen opens, the mouse unlocks, the selected hotbar
 * slot changes (scroll wheel, number keys, inventory swaps — one check
 * covers all of it, no scroll-event mixin needed), or a hotbar key is held.
 */
public abstract class InputMacro {
    private final KeyBinding toggle;
    private boolean active;
    private int watchedSlot = -1;

    protected InputMacro(KeyBinding toggle) {
        this.toggle = toggle;
    }

    /** Wire into END_CLIENT_TICK. */
    public final void tick(MinecraftClient mc) {
        while (toggle.wasPressed()) {
            if (active) {
                deactivate(mc);
            } else if (mc.player != null && canEngage(mc)) {
                activate(mc);
            }
        }
        if (!active || mc.player == null) return;

        int slot = mc.player.getInventory().getSelectedSlot();
        if (watchedSlot < 0) watchedSlot = slot;
        if (mc.currentScreen != null || !mc.mouse.isCursorLocked()
                || slot != watchedSlot || hotbarKeyDown(mc)) {
            deactivate(mc);
            return;
        }
        tickActive(mc);
    }

    private void activate(MinecraftClient mc) {
        active = true;
        watchedSlot = -1;
        onEngage(mc);
        OpenIntelClient.status(name() + " on");
    }

    private void deactivate(MinecraftClient mc) {
        active = false;
        onRelease(mc);
        OpenIntelClient.status(name() + " off");
    }

    private static boolean hotbarKeyDown(MinecraftClient mc) {
        for (KeyBinding k : mc.options.hotbarKeys) {
            if (k.isPressed()) return true;
        }
        return false;
    }

    public boolean isActive() { return active; }

    /** Extra gate for engaging (e.g. don't grab use while eating). */
    protected boolean canEngage(MinecraftClient mc) { return mc.currentScreen == null; }

    protected void onEngage(MinecraftClient mc) { }
    protected void onRelease(MinecraftClient mc) { }
    protected void tickActive(MinecraftClient mc) { }

    /** Short name used in the "[OpenIntel] <name> on/off" chat feedback. */
    protected abstract String name();
}
