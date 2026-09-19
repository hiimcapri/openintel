package dev.openintel.macro;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

/**
 * Base for toggle-on-keypress macros that drive vanilla inputs.
 *
 * The toggle key flips the macro on/off; while on, tickActive() runs every
 * client tick. By default the macro disengages itself the moment the player
 * loses "input custody": a screen opens, the mouse unlocks, the selected
 * hotbar slot changes (scroll wheel, number keys, inventory swaps — one
 * check covers all of it, no scroll-event mixin needed), or a hotbar key is
 * held. Macros that should ride through those (ice road) override
 * {@link #losesInputCustody}.
 */
public abstract class InputMacro {
    private final KeyMapping toggle;
    private boolean active;
    private int watchedSlot = -1;

    protected InputMacro(KeyMapping toggle) {
        this.toggle = toggle;
    }

    /** Wire into END_CLIENT_TICK. */
    public final void tick(Minecraft mc) {
        while (toggle.consumeClick()) {
            if (active) {
                deactivate(mc);
            } else if (mc.player != null && canEngage(mc)) {
                activate(mc);
            }
        }
        if (!active || mc.player == null) return;

        int slot = mc.player.getInventory().getSelectedSlot();
        if (watchedSlot < 0) watchedSlot = slot;
        if (losesInputCustody(mc, slot)) {
            deactivate(mc);
            return;
        }
        tickActive(mc);
    }

    private void activate(Minecraft mc) {
        active = true;
        watchedSlot = -1;
        onEngage(mc);
        OpenIntelClient.status(name() + " on");
    }

    private void deactivate(Minecraft mc) {
        active = false;
        onRelease(mc);
        OpenIntelClient.status(name() + " off");
    }

    private static boolean hotbarKeyDown(Minecraft mc) {
        for (KeyMapping k : mc.options.keyHotbarSlots) {
            if (k.isDown()) return true;
        }
        return false;
    }

    public boolean isActive() { return active; }

    /** Extra gate for engaging (e.g. don't grab use while eating). */
    protected boolean canEngage(Minecraft mc) { return mc.screen == null; }

    /**
     * Whether the macro has lost input custody this tick and should
     * disengage. Default: any open screen, unlocked mouse, hotbar-slot
     * change, or held hotbar key. Override to keep running through them —
     * ice road does, matching CivModern (chat, inventory and scrolling
     * don't stop it; only the toggle key does).
     */
    protected boolean losesInputCustody(Minecraft mc, int slot) {
        return mc.screen != null || !mc.mouseHandler.isMouseGrabbed()
                || slot != watchedSlot || hotbarKeyDown(mc);
    }

    protected void onEngage(Minecraft mc) { }
    protected void onRelease(Minecraft mc) { }
    protected void tickActive(Minecraft mc) { }

    /** Short name used in the "[OpenIntel] <name> on/off" chat feedback. */
    protected abstract String name();
}
