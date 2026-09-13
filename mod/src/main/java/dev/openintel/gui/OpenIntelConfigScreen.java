package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One-stop settings screen, opened by /oi settings.
 *
 * Connection fields (relay URL + token) are plain TextFieldWidgets dropped
 * into the options list — they live-write into OIConfig and the Reconnect
 * button applies them without a restart.
 *
 * Keybind rows use a "listening" button: press it, then press the new key
 * (or mouse button). ESC cancels the rebind.
 */
public class OpenIntelConfigScreen extends GameOptionsScreen {

    private KeyBinding listeningFor;
    private final Map<KeyBinding, ButtonWidget> keyButtons = new LinkedHashMap<>();

    public OpenIntelConfigScreen(Screen parent) {
        super(parent, MinecraftClient.getInstance().options, Text.literal("OpenIntel Settings"));
    }

    @Override
    protected void addOptions() {
        OIConfig cfg = OpenIntelClient.config();

        // ------------------------------------------------------ connection
        body.addHeader(Text.literal("Relay connection"));

        TextFieldWidget url = new TextFieldWidget(client.textRenderer, 0, 0, 300, 20,
                Text.literal("Relay URL"));
        url.setMaxLength(512);
        url.setText(cfg.relayUrl);
        url.setPlaceholder(Text.literal("ws://host:port"));
        url.setChangedListener(s -> cfg.relayUrl = s.trim());
        body.addWidgetEntry(url, null);

        TextFieldWidget token = new TextFieldWidget(client.textRenderer, 0, 0, 300, 20,
                Text.literal("Token"));
        token.setMaxLength(256);
        token.setText(cfg.token);
        token.setPlaceholder(Text.literal("relay token"));
        token.setChangedListener(s -> cfg.token = s.trim());
        body.addWidgetEntry(token, null);

        body.addWidgetEntry(ButtonWidget.builder(Text.literal("Reconnect"), b -> {
                    OpenIntelClient.config().save();
                    OpenIntelClient.relay().disconnect();
                    OpenIntelClient.relay().connect(cfg.relayUrl, cfg.token);
                    OpenIntelClient.status("reconnecting…");
                }).build(), null);

        // ------------------------------------------------------ relay rendering
        body.addHeader(Text.literal("Relay rendering"));
        body.addAll(
                bool("options.openintel.relay.rendering", cfg.relayRendering,
                        v -> cfg.relayRendering = v),
                bool("options.openintel.relay.visible_markers", cfg.markVisiblePlayers,
                        v -> cfg.markVisiblePlayers = v),
                bool("options.openintel.relay.edge_chevrons", cfg.edgeChevrons,
                        v -> cfg.edgeChevrons = v),
                bool("options.openintel.relay.stale_decay", cfg.staleDecay,
                        v -> cfg.staleDecay = v),
                bool("options.openintel.relay.enemy_alert", cfg.localEnemyAlert,
                        v -> cfg.localEnemyAlert = v),
                slider("options.openintel.relay.opacity", 10, 255, cfg.relayOpacity,
                        "", v -> cfg.relayOpacity = v),
                new SimpleOption<>("options.openintel.relay.max_dist",
                        SimpleOption.emptyTooltip(),
                        (text, v) -> text.copy().append(": " + (v <= 0 ? "unlimited" : v + "m")),
                        new SimpleOption.ValidatingIntSliderCallbacks(0, 20000, true),
                        (int) Math.min(20000, Math.max(0, cfg.maxMarkerDistance)),
                        v -> cfg.maxMarkerDistance = v)
        );

        // ------------------------------------------------------ HUD layout
        body.addHeader(Text.literal("HUD layout"));
        body.addWidgetEntry(ButtonWidget.builder(Text.literal("Open HUD editor…"), b ->
                client.setScreen(new HudEditorScreen(this))).build(), null);
        body.addAll(
                bool("options.openintel.armor.enabled", cfg.armorHudEnabled,
                        v -> cfg.armorHudEnabled = v),
                bool("options.openintel.potions.enabled", cfg.potionHudEnabled,
                        v -> cfg.potionHudEnabled = v)
        );

        // ------------------------------------------------------ presence
        body.addHeader(Text.literal("Presence panel"));
        body.addAll(
                bool("options.openintel.presence.enabled", cfg.presenceEnabled,
                        v -> cfg.presenceEnabled = v),
                bool("options.openintel.presence.all_dims", cfg.presenceShowAllDims,
                        v -> cfg.presenceShowAllDims = v),
                slider("options.openintel.presence.rows", 4, 24, cfg.presenceMaxRows,
                        "", v -> cfg.presenceMaxRows = v)
        );

        // ------------------------------------------------------ pings
        body.addHeader(Text.literal("Ping wheel"));
        body.addAll(
                bool("options.openintel.ping.enabled", cfg.pingWheelEnabled,
                        v -> cfg.pingWheelEnabled = v),
                slider("options.openintel.ping.seconds", 10, 120, cfg.pingSeconds,
                        "s", v -> cfg.pingSeconds = v)
        );

        // ------------------------------------------------------ events
        body.addHeader(Text.literal("Event feed & snitch relay"));
        body.addAll(
                bool("options.openintel.feed.enabled", cfg.eventFeedEnabled,
                        v -> cfg.eventFeedEnabled = v),
                slider("options.openintel.feed.seconds", 3, 30, cfg.eventFeedSeconds,
                        "s", v -> cfg.eventFeedSeconds = v),
                bool("options.openintel.snitch.enabled", cfg.snitchRelay,
                        v -> cfg.snitchRelay = v),
                slider("options.openintel.snitch.seconds", 30, 300, cfg.snitchMarkerSeconds,
                        "s", v -> cfg.snitchMarkerSeconds = v),
                new SimpleOption<>("options.openintel.snitch.range",
                        SimpleOption.emptyTooltip(),
                        (text, v) -> text.copy().append(": " + (v <= 0 ? "unlimited" : v + "m")),
                        new SimpleOption.ValidatingIntSliderCallbacks(0, 20000, true),
                        cfg.snitchMarkerRange, v -> cfg.snitchMarkerRange = v)
        );
        body.addWidgetEntry(colorButton("Snitch marker color", () -> cfg.snitchMarkerColor,
                v -> cfg.snitchMarkerColor = v,
                "Allegiance color", () -> cfg.snitchMarkerColor = -1), null);

        // ------------------------------------------------------ colors
        body.addHeader(Text.literal("Colors"));
        body.addWidgetEntry(colorButton("Radar lines", () -> cfg.radarFgColor,
                v -> cfg.radarFgColor = v, null, null), null);
        body.addWidgetEntry(colorButton("Radar background", () -> cfg.radarBgColor,
                v -> cfg.radarBgColor = v, null, null), null);
        // ------------------------------------------------------ keybinds
        body.addHeader(Text.literal("Keybinds"));
        for (KeyBinding kb : OpenIntelClient.allKeys()) {
            ButtonWidget keyBtn = ButtonWidget.builder(
                            Text.translatable(kb.getId()).append(": ")
                                    .append(kb.getBoundKeyLocalizedText()),
                            b -> listeningFor = kb)
                    .width(200).build();
            ButtonWidget reset = ButtonWidget.builder(Text.literal("Reset"), b -> {
                        kb.setBoundKey(kb.getDefaultKey());
                        finishRebind();
                    }).width(60).build();
            keyButtons.put(kb, keyBtn);
            body.addWidgetEntry(keyBtn, reset);
        }

        // ------------------------------------------------------ sub-screens
        body.addHeader(Text.literal("More settings"));
        body.addWidgetEntry(ButtonWidget.builder(Text.literal("Radar…"), b ->
                client.setScreen(new RadarConfigScreen(this))).build(), null);
        body.addWidgetEntry(ButtonWidget.builder(Text.literal("Macros…"), b ->
                client.setScreen(new MacroConfigScreen(this))).build(), null);
    }

    // ---------------------------------------------------- rebind capture ---

    @Override
    public boolean keyPressed(KeyInput input) {
        if (listeningFor != null) {
            if (input.getKeycode() != GLFW.GLFW_KEY_ESCAPE) {
                listeningFor.setBoundKey(InputUtil.fromKeyCode(input));
            }
            finishRebind();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (listeningFor != null) {
            listeningFor.setBoundKey(InputUtil.Type.MOUSE.createFromCode(click.button()));
            finishRebind();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void finishRebind() {
        KeyBinding.updateKeysByCode();
        if (client != null) client.options.write();
        listeningFor = null;
        refreshKeyLabels();
    }

    private void refreshKeyLabels() {
        for (var e : keyButtons.entrySet()) {
            e.getValue().setMessage(Text.translatable(e.getKey().getId())
                    .append(": ").append(e.getKey().getBoundKeyLocalizedText()));
        }
    }

    @Override
    public void removed() {
        super.removed();
        OpenIntelClient.config().save();
    }

    // ------------------------------------------------------------ helpers ----

    private static SimpleOption<Boolean> bool(String key, boolean current,
                                              Consumer<Boolean> apply) {
        return SimpleOption.ofBoolean(key, current, apply);
    }

    private static SimpleOption<Integer> slider(String key, int min, int max, int current,
                                                String suffix, Consumer<Integer> apply) {
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": " + v + suffix),
                new SimpleOption.ValidatingIntSliderCallbacks(min, max, true),
                current, apply);
    }

    /** Settings row that opens the HSV color picker. autoLabel/autoAction add
     *  an escape hatch (e.g. "Allegiance color" → -1) to the picker. */
    private ButtonWidget colorButton(String label,
                                     java.util.function.IntSupplier get,
                                     java.util.function.IntConsumer set,
                                     String autoLabel, Runnable autoAction) {
        ButtonWidget[] ref = new ButtonWidget[1];
        java.util.function.IntConsumer apply = v -> {
            set.accept(v);
            ref[0].setMessage(Text.literal(label + ": "
                    + (v == -1 ? "auto" : String.format("#%08X", v))));
        };
        ref[0] = ButtonWidget.builder(Text.literal(""), b ->
                client.setScreen(new ColorPickerScreen(this, label,
                        get.getAsInt() == -1 ? 0xFFFF5555 : get.getAsInt(),
                        apply, autoLabel, autoAction))).build();
        apply.accept(get.getAsInt());
        return ref[0];
    }
}
