package dev.openintel.macro;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * Toggleable auto-attacker (CivModern parity): discrete attack-key presses
 * every attackMacroIntervalMs — ~5 CPS at the default 200ms — so swings
 * behave exactly like real clicks. Any inventory interaction or screen
 * releases it.
 */
public final class AttackMacro extends IntervalMacro {

    public AttackMacro(KeyBinding toggle) {
        super(toggle, () -> MinecraftClient.getInstance().options.attackKey,
                () -> OpenIntelClient.config().attackMacroIntervalMs, "attack macro");
    }
}
