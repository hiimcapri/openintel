package dev.openintel.macro;

import dev.openintel.OpenIntelClient;
import dev.openintel.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * Toggleable auto-attacker (CivModern parity): while active it injects a
 * discrete attack-key press every attackMacroIntervalMs — ~5 CPS at the
 * default 200ms — through the vanilla press counter, so swings behave
 * exactly like real clicks. Any inventory interaction or screen releases it.
 */
public final class AttackMacro extends InputMacro {
    private long lastAttack;
    private boolean pressedThisCycle;

    public AttackMacro(KeyBinding toggle) {
        super(toggle);
    }

    @Override
    protected void tickActive(MinecraftClient mc) {
        if (pressedThisCycle) {
            pressedThisCycle = false;
            return;
        }
        if (System.currentTimeMillis() - lastAttack
                < OpenIntelClient.config().attackMacroIntervalMs) {
            return;
        }

        KeyBindingAccessor attack = (KeyBindingAccessor) mc.options.attackKey;
        attack.openintel$setTimesPressed(attack.openintel$getTimesPressed() + 1);

        pressedThisCycle = true;
        lastAttack = System.currentTimeMillis();
    }

    @Override
    protected String name() { return "attack macro"; }
}
