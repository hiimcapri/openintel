package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Option model and content for {@link ClickGuiScreen}.
 *
 * The screen only knows the shapes below: a {@link Category} is a sidebar
 * entry, a {@link Group} is a section divider inside a category, and an
 * {@link Option} is one settings row (label + tooltip + a main widget with
 * an optional narrow aux widget on its right). All widgets are built here
 * once and repositioned by the screen during layout.
 *
 * Keybind rows set {@link Option#keybind()}; clicking their widget calls
 * back through {@link RebindHandler} so the screen can own capture state.
 */
public final class ClickGui {
    private ClickGui() { }

    public sealed interface Item permits Group, Option { }

    /** Section divider inside a category. */
    public record Group(String title) implements Item { }

    /**
     * One settings row.
     * @param label   row text, drawn left
     * @param tooltip hover description, may be null
     * @param widget  main control, right-aligned
     * @param aux     optional narrow control to the right of widget (e.g. reset)
     * @param keybind non-null when the row rebinds a KeyBinding
     */
    public record Option(String label, String tooltip, ClickableWidget widget,
                         ClickableWidget aux, KeyBinding keybind) implements Item {
        public Option(String label, String tooltip, ClickableWidget widget) {
            this(label, tooltip, widget, null, null);
        }
    }

    /** A sidebar category. */
    public record Category(String id, String title, List<Item> items) { }

    /** Rebind capture is owned by the screen; rows only report clicks. */
    public interface RebindHandler {
        void begin(KeyBinding kb);
        void reset(KeyBinding kb);
    }

    // ------------------------------------------------------------ content ----

    public static List<Category> build(Screen self, RebindHandler rebind) {
        OIConfig cfg = OpenIntelClient.config();
        List<Category> cats = new ArrayList<>();

        // ---------------------------------------------------------- general
        cats.add(new Category("general", "General", List.of(
                new Group("Relay connection"),
                new Option("Relay URL", "WebSocket address of the OpenIntel relay server.",
                        textField(cfg.relayUrl, "ws://host:port", 512, s -> cfg.relayUrl = s)),
                new Option("Minecraft server", "Only connect to the relay while playing this server.",
                        textField(cfg.minecraftServer, "play.example.net", 255, s -> cfg.minecraftServer = s)),
                new Option("Token", "Your personal relay token. Keep it private.",
                        textField(cfg.token, "relay token", 256, s -> cfg.token = s)),
                new Option("Reconnect", "Save credentials and reconnect to the relay now.",
                        button("Reconnect", () -> {
                            OpenIntelClient.config().save();
                            OpenIntelClient.reconnectRelay();
                        }))
        )));

        // ---------------------------------------------------------- markers
        cats.add(new Category("markers", "Markers", List.of(
                new Group("Player markers"),
                new Option(tr("options.openintel.relay.rendering"), "Master switch for all relay markers.",
                        bool("options.openintel.relay.rendering", cfg.relayRendering, v -> cfg.relayRendering = v)),
                new Option(tr("options.openintel.relay.visible_markers"), "Keep markers on players you can already see.",
                        bool("options.openintel.relay.visible_markers", cfg.markVisiblePlayers, v -> cfg.markVisiblePlayers = v)),
                new Option(tr("options.openintel.relay.edge_chevrons"), "Pin off-screen targets to the screen edges.",
                        bool("options.openintel.relay.edge_chevrons", cfg.edgeChevrons, v -> cfg.edgeChevrons = v)),
                new Option(tr("options.openintel.relay.stale_decay"), "Fade markers as their intel ages out.",
                        bool("options.openintel.relay.stale_decay", cfg.staleDecay, v -> cfg.staleDecay = v)),
                new Option(tr("options.openintel.relay.enemy_alert"), "Chat alert when an enemy is spotted by the relay.",
                        bool("options.openintel.relay.enemy_alert", cfg.localEnemyAlert, v -> cfg.localEnemyAlert = v)),
                new Option(tr("options.openintel.relay.opacity"), "Alpha applied to every relay marker.",
                        slider("options.openintel.relay.opacity", 10, 255, cfg.relayOpacity,
                                "", v -> cfg.relayOpacity = v)),
                new Option(tr("options.openintel.relay.max_dist"), "Hide markers beyond this distance; 0 is unlimited.",
                        rangeSlider("options.openintel.relay.max_dist",
                                (int) Math.min(20000, Math.max(0, cfg.maxMarkerDistance)),
                                v -> cfg.maxMarkerDistance = v))
        )));

        // ------------------------------------------------------------- radar
        cats.add(new Category("radar", "Radar", List.of(
                new Group("Radar"),
                new Option(tr("options.openintel.radar.enabled"), "Show the circular player radar.",
                        bool("options.openintel.radar.enabled", cfg.radarEnabled, v -> cfg.radarEnabled = v)),
                new Option(tr("options.openintel.radar.north_up"), "Lock the dial to north instead of rotating with you.",
                        bool("options.openintel.radar.north_up", cfg.radarNorthUp, v -> cfg.radarNorthUp = v)),
                new Option(tr("options.openintel.radar.compress"), "Magnify the inner field while compressing the outer ring. 0 = true linear.",
                        slider("options.openintel.radar.compress", 0, 100, cfg.radarCompression,
                                "%", v -> cfg.radarCompression = v)),
                new Option(tr("options.openintel.radar.items"), "Draw dropped items on the radar.",
                        bool("options.openintel.radar.items", cfg.radarShowItems, v -> cfg.radarShowItems = v)),
                new Option(tr("options.openintel.radar.vehicles"), "Draw boats and minecarts on the radar.",
                        bool("options.openintel.radar.vehicles", cfg.radarShowVehicles, v -> cfg.radarShowVehicles = v)),
                new Option(tr("options.openintel.radar.relay"), "Pin relay contacts beyond render distance to the rim.",
                        bool("options.openintel.radar.relay", cfg.radarShowRelay, v -> cfg.radarShowRelay = v)),
                new Group("Contacts"),
                new Option(tr("options.openintel.radar.players"), "Draw player contacts at all.",
                        bool("options.openintel.radar.players", cfg.radarShowPlayers, v -> cfg.radarShowPlayers = v)),
                new Option(tr("options.openintel.radar.ping_filter"), "Which players appear: everyone, non-relay players, or enemies only.",
                        cycle("options.openintel.radar.ping_filter", cfg.radarPlayerFilter,
                                v -> cfg.radarPlayerFilter = v)),
                new Option(tr("options.openintel.radar.pings"), "Draw shared pings on the radar.",
                        bool("options.openintel.radar.pings", cfg.radarShowPings, v -> cfg.radarShowPings = v)),
                new Group("Dial"),
                new Option(tr("options.openintel.radar.size"), "Radar radius in pixels.",
                        slider("options.openintel.radar.size", 16, 160, cfg.radarSize,
                                " px", v -> cfg.radarSize = v)),
                new Option(tr("options.openintel.radar.range"), "How far the dial reaches, in blocks.",
                        slider("options.openintel.radar.range", 8, 512, (int) cfg.radarRange,
                                " blocks", v -> cfg.radarRange = v)),
                new Option(tr("options.openintel.radar.circles"), "Number of range rings; 0 hides them.",
                        slider("options.openintel.radar.circles", 0, 8, cfg.radarCircles,
                                "", v -> cfg.radarCircles = v)),
                new Option(tr("options.openintel.radar.icon_size"), "Scale of contact icons.",
                        slider("options.openintel.radar.icon_size", 50, 200, (int) (cfg.radarIconSize * 100),
                                "%", v -> cfg.radarIconSize = v / 100f)),
                new Option(tr("options.openintel.radar.text_size"), "Scale of distance labels.",
                        slider("options.openintel.radar.text_size", 50, 200, (int) (cfg.radarTextSize * 100),
                                "%", v -> cfg.radarTextSize = v / 100f)),
                new Group("Colors"),
                new Option(tr("options.openintel.radar.bg_alpha"), "Background fill opacity.",
                        alphaSlider("options.openintel.radar.bg_alpha", cfg.radarBgColor,
                                v -> cfg.radarBgColor = v)),
                new Option(tr("options.openintel.radar.fg_alpha"), "Ring and blip opacity.",
                        alphaSlider("options.openintel.radar.fg_alpha", cfg.radarFgColor,
                                v -> cfg.radarFgColor = v)),
                new Option("Radar lines", "RGB of the radar rings and blips.",
                        colorButton(self, "Radar lines", () -> cfg.radarFgColor,
                                v -> cfg.radarFgColor = v, null, null)),
                new Option("Radar background", "RGB of the dial fill.",
                        colorButton(self, "Radar background", () -> cfg.radarBgColor,
                                v -> cfg.radarBgColor = v, null, null))
        )));

        // -------------------------------------------------------------- hud
        cats.add(new Category("hud", "HUD", List.of(
                new Group("Layout"),
                new Option("HUD editor", "Drag every HUD element into place on a live preview.",
                        button("Open HUD editor…", () ->
                                MinecraftClient.getInstance().setScreen(new HudEditorScreen(self)))),
                new Group("Elements"),
                new Option(tr("options.openintel.armor.enabled"), "Equipped armor with durability percentages.",
                        bool("options.openintel.armor.enabled", cfg.armorHudEnabled, v -> cfg.armorHudEnabled = v)),
                new Option(tr("options.openintel.potions.enabled"), "Active potion effects and timers.",
                        bool("options.openintel.potions.enabled", cfg.potionHudEnabled, v -> cfg.potionHudEnabled = v)),
                new Group("Presence panel"),
                new Option(tr("options.openintel.presence.enabled"), "Connected relay players and their status.",
                        bool("options.openintel.presence.enabled", cfg.presenceEnabled, v -> cfg.presenceEnabled = v)),
                new Option(tr("options.openintel.presence.all_dims"), "Show players in other dimensions too.",
                        bool("options.openintel.presence.all_dims", cfg.presenceShowAllDims, v -> cfg.presenceShowAllDims = v)),
                new Option(tr("options.openintel.presence.rows"), "Maximum rows before the list truncates.",
                        slider("options.openintel.presence.rows", 4, 24, cfg.presenceMaxRows,
                                "", v -> cfg.presenceMaxRows = v)),
                new Group("Event feed"),
                new Option(tr("options.openintel.feed.enabled"), "Presence, sightings, and snitch events in a feed.",
                        bool("options.openintel.feed.enabled", cfg.eventFeedEnabled, v -> cfg.eventFeedEnabled = v)),
                new Option(tr("options.openintel.feed.seconds"), "How long feed entries linger.",
                        slider("options.openintel.feed.seconds", 3, 30, cfg.eventFeedSeconds,
                                "s", v -> cfg.eventFeedSeconds = v))
        )));

        // ----------------------------------------------------------- snitch
        cats.add(new Category("snitch", "Snitch", List.of(
                new Group("Snitch relay"),
                new Option(tr("options.openintel.snitch.enabled"), "Forward JukeAlert hits to the relay and draw markers.",
                        bool("options.openintel.snitch.enabled", cfg.snitchRelay, v -> cfg.snitchRelay = v)),
                new Option(tr("options.openintel.snitch.seconds"), "How long a snitch-hit marker stays on screen.",
                        slider("options.openintel.snitch.seconds", 30, 300, cfg.snitchMarkerSeconds,
                                "s", v -> cfg.snitchMarkerSeconds = v)),
                new Option(tr("options.openintel.snitch.range"), "Hide snitch markers beyond this distance; 0 is unlimited.",
                        rangeSlider("options.openintel.snitch.range", cfg.snitchMarkerRange,
                                v -> cfg.snitchMarkerRange = v)),
                new Option("Snitch marker color", "Fixed color, or the tripper's allegiance color.",
                        colorButton(self, "Snitch marker color",
                                () -> cfg.snitchMarkerColorAuto ? -1 : cfg.snitchMarkerColor,
                                v -> {
                                    cfg.snitchMarkerColor = v;
                                    cfg.snitchMarkerColorAuto = false;
                                },
                                "Allegiance color", () -> {
                                    cfg.snitchMarkerColorAuto = true;
                                    cfg.snitchMarkerColor = 0xFFAAAAAA;
                                }))
        )));

        // ------------------------------------------------------------ pings
        cats.add(new Category("pings", "Pings", List.of(
                new Group("Ping wheel"),
                new Option(tr("options.openintel.ping.enabled"), "Hold the ping key to broadcast a shared marker.",
                        bool("options.openintel.ping.enabled", cfg.pingWheelEnabled, v -> cfg.pingWheelEnabled = v)),
                new Option(tr("options.openintel.ping.seconds"), "How long a shared ping stays visible.",
                        slider("options.openintel.ping.seconds", 10, 120, cfg.pingSeconds,
                                "s", v -> cfg.pingSeconds = v))
        )));

        // ------------------------------------------------------ integrations
        cats.add(new Category("integrations", "Integrations", List.of(
                new Group("JourneyMap"),
                new Option(tr("options.openintel.jm.markers"), "Draw snitch hits and pings on JourneyMap's fullscreen map.",
                        bool("options.openintel.jm.markers", cfg.jmMarkers, v -> cfg.jmMarkers = v))
        )));

        // ----------------------------------------------------------- macros
        cats.add(new Category("macros", "Macros", List.of(
                new Group("Attack macro"),
                new Option(tr("options.openintel.macro.attack_interval"), "Milliseconds between swings.",
                        slider("options.openintel.macro.attack_interval", 50, 1000, cfg.attackMacroIntervalMs,
                                " ms", v -> cfg.attackMacroIntervalMs = v)),
                new Group("Ice road"),
                new Option(tr("options.openintel.macro.snap_yaw"), "Snap movement to 45-degree angles.",
                        bool("options.openintel.macro.snap_yaw", cfg.iceRoadSnapYaw, v -> cfg.iceRoadSnapYaw = v)),
                new Option(tr("options.openintel.macro.snap_pitch"), "Snap view pitch for boat gliding.",
                        bool("options.openintel.macro.snap_pitch", cfg.iceRoadSnapPitch, v -> cfg.iceRoadSnapPitch = v)),
                new Option(tr("options.openintel.macro.auto_eat"), "Eat automatically when hungry.",
                        bool("options.openintel.macro.auto_eat", cfg.iceRoadAutoEat, v -> cfg.iceRoadAutoEat = v)),
                new Option(tr("options.openintel.macro.stop_hunger"), "Park the boat when hunger runs too low.",
                        bool("options.openintel.macro.stop_hunger", cfg.iceRoadStopAtHunger, v -> cfg.iceRoadStopAtHunger = v))
        )));

        // --------------------------------------------------------- keybinds
        List<Item> keyItems = new ArrayList<>();
        keyItems.add(new Group("Controls"));
        for (KeyBinding kb : OpenIntelClient.allKeys()) {
            ButtonWidget bind = ButtonWidget.builder(kb.getBoundKeyLocalizedText(),
                    b -> rebind.begin(kb)).build();
            ButtonWidget reset = ButtonWidget.builder(Text.literal("Reset"),
                    b -> rebind.reset(kb)).build();
            keyItems.add(new Option(Text.translatable(kb.getId()).getString(),
                    "Click to rebind, then press a key or mouse button. ESC cancels.",
                    bind, reset, kb));
        }
        cats.add(new Category("keybinds", "Keybinds", keyItems));

        return cats;
    }

    // ------------------------------------------------------------ helpers ----

    private static String tr(String key) {
        return Text.translatable(key).getString();
    }

    private static ClickableWidget bool(String key, boolean current,
                                        Consumer<Boolean> apply) {
        return SimpleOption.ofBoolean(key, current, apply)
                .createWidget(MinecraftClient.getInstance().options, 0, 0, 150);
    }

    private static ClickableWidget slider(String key, int min, int max, int current,
                                          String suffix, Consumer<Integer> apply) {
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": " + v + suffix),
                new SimpleOption.ValidatingIntSliderCallbacks(min, max, true),
                current, apply)
                .createWidget(MinecraftClient.getInstance().options, 0, 0, 150);
    }

    /** 0–20000 slider that reads "unlimited" at 0. */
    private static ClickableWidget rangeSlider(String key, int current,
                                               Consumer<Integer> apply) {
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": " + (v <= 0 ? "unlimited" : v + "m")),
                new SimpleOption.ValidatingIntSliderCallbacks(0, 20000, true),
                current, apply)
                .createWidget(MinecraftClient.getInstance().options, 0, 0, 150);
    }

    /** 0–255 slider that writes only the alpha byte of an ARGB color. */
    private static ClickableWidget alphaSlider(String key, int color,
                                               Consumer<Integer> apply) {
        int alpha = (color >>> 24) & 0xFF;
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": " + v),
                new SimpleOption.ValidatingIntSliderCallbacks(0, 255, true),
                alpha, v -> apply.accept((color & 0x00FFFFFF) | (v << 24)))
                .createWidget(MinecraftClient.getInstance().options, 0, 0, 150);
    }

    /** Cycles a RadarBlipFilter; the label resolves through the enum's lang key. */
    private static ClickableWidget cycle(String key, OIConfig.RadarBlipFilter current,
                                         Consumer<OIConfig.RadarBlipFilter> apply) {
        var values = List.of(OIConfig.RadarBlipFilter.values());
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": ").append(Text.translatable(v.translationKey)),
                new SimpleOption.PotentialValuesBasedCallbacks<>(values,
                        com.mojang.serialization.Codec.STRING.xmap(
                                OIConfig.RadarBlipFilter::byName, Enum::name)),
                current, apply)
                .createWidget(MinecraftClient.getInstance().options, 0, 0, 150);
    }

    private static ClickableWidget textField(String current, String placeholder,
                                             int maxLen, Consumer<String> apply) {
        TextFieldWidget field = new TextFieldWidget(
                MinecraftClient.getInstance().textRenderer, 0, 0, 150, 20,
                Text.literal("field"));
        field.setMaxLength(maxLen);
        field.setText(current);
        field.setPlaceholder(Text.literal(placeholder));
        field.setChangedListener(s -> apply.accept(s.trim()));
        return field;
    }

    private static ClickableWidget button(String label, Runnable action) {
        return ButtonWidget.builder(Text.literal(label), b -> action.run()).build();
    }

    /**
     * Button row that opens the HSV color picker. `get` returns -1 when the
     * option is in its "auto" state; `autoLabel`/`autoAction` offer an escape
     * hatch (e.g. "Allegiance color") back to it.
     */
    private static ClickableWidget colorButton(Screen self, String label,
                                               java.util.function.IntSupplier get,
                                               java.util.function.IntConsumer set,
                                               String autoLabel, Runnable autoAction) {
        ButtonWidget[] ref = new ButtonWidget[1];
        java.util.function.IntConsumer apply = v -> {
            set.accept(v);
            ref[0].setMessage(Text.literal(v == -1 ? "auto" : String.format("#%08X", v)));
        };
        ref[0] = ButtonWidget.builder(Text.literal(""), b ->
                MinecraftClient.getInstance().setScreen(new ColorPickerScreen(self, label,
                        get.getAsInt() == -1 ? 0xFFFF5555 : get.getAsInt(),
                        apply, autoLabel, autoAction))).build();
        apply.accept(get.getAsInt());
        return ref[0];
    }
}
