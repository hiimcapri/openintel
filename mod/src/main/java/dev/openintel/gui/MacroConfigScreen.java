package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

/**
 * Options screen for the input macros, opened by /oi macros. Lists the
 * current keybinds up top (rebind them under Options > Controls > OpenIntel),
 * then the tuning knobs that live in openintel.json.
 */
public class MacroConfigScreen extends GameOptionsScreen {

    public MacroConfigScreen(Screen parent) {
        super(parent, MinecraftClient.getInstance().options, Text.literal("OpenIntel Macros"));
    }

    @Override
    protected void addOptions() {
        OIConfig cfg = OpenIntelClient.config();

        body.addHeader(Text.literal("Keybinds"));
        for (KeyBinding kb : OpenIntelClient.allKeys()) {
            body.addHeader(Text.translatable(kb.getId())
                    .append(" — ")
                    .append(kb.getBoundKeyLocalizedText()));
        }

        body.addHeader(Text.literal("Attack macro"));
        body.addAll(
                new SimpleOption<>("options.openintel.macro.attack_interval",
                        SimpleOption.constantTooltip(Text.literal("Milliseconds between swings")),
                        (text, v) -> text.copy().append(": " + v + " ms"),
                        new SimpleOption.ValidatingIntSliderCallbacks(50, 1000, true),
                        cfg.attackMacroIntervalMs, v -> cfg.attackMacroIntervalMs = v)
        );

        body.addHeader(Text.literal("Ice road"));
        body.addAll(
                SimpleOption.ofBoolean("options.openintel.macro.snap_yaw",
                        cfg.iceRoadSnapYaw, v -> cfg.iceRoadSnapYaw = v),
                SimpleOption.ofBoolean("options.openintel.macro.snap_pitch",
                        cfg.iceRoadSnapPitch, v -> cfg.iceRoadSnapPitch = v),
                SimpleOption.ofBoolean("options.openintel.macro.auto_eat",
                        cfg.iceRoadAutoEat, v -> cfg.iceRoadAutoEat = v),
                SimpleOption.ofBoolean("options.openintel.macro.stop_hunger",
                        cfg.iceRoadStopAtHunger, v -> cfg.iceRoadStopAtHunger = v)
        );
    }

    @Override
    public void removed() {
        super.removed();
        OpenIntelClient.config().save();
    }
}
