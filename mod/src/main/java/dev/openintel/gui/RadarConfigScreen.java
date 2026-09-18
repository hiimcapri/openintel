package dev.openintel.gui;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.radar.RadarHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

/**
 * Vanilla-style options screen for the radar, opened by /oi radar.
 *
 * Sliders use applyValueImmediately so the dial preview — rendered live
 * behind the options list — tracks every drag. Everything writes straight
 * into OIConfig and persists when the screen closes.
 */
public class RadarConfigScreen extends GameOptionsScreen {

    public RadarConfigScreen(Screen parent) {
        super(parent, MinecraftClient.getInstance().options, Text.literal("OpenIntel Radar"));
    }

    @Override
    protected void addOptions() {
        OIConfig cfg = OpenIntelClient.config();

        body.addHeader(Text.literal("Radar"));
        body.addAll(
                bool("options.openintel.radar.enabled", cfg.radarEnabled, v -> cfg.radarEnabled = v),
                bool("options.openintel.radar.north_up", cfg.radarNorthUp, v -> cfg.radarNorthUp = v),
                slider("options.openintel.radar.compress", 0, 100, cfg.radarCompression,
                        "%", v -> cfg.radarCompression = v),
                bool("options.openintel.radar.items", cfg.radarShowItems, v -> cfg.radarShowItems = v),
                bool("options.openintel.radar.vehicles", cfg.radarShowVehicles, v -> cfg.radarShowVehicles = v),
                bool("options.openintel.radar.relay", cfg.radarShowRelay, v -> cfg.radarShowRelay = v)
        );

        body.addHeader(Text.literal("Contacts"));
        body.addAll(
                bool("options.openintel.radar.players", cfg.radarShowPlayers, v -> cfg.radarShowPlayers = v),
                cycle("options.openintel.radar.ping_filter", cfg.radarPlayerFilter,
                        v -> cfg.radarPlayerFilter = v),
                bool("options.openintel.radar.pings", cfg.radarShowPings, v -> cfg.radarShowPings = v)
        );

        body.addHeader(Text.literal("Dial"));
        body.addAll(
                slider("options.openintel.radar.size", 16, 160, cfg.radarSize,
                        " px", v -> cfg.radarSize = v),
                slider("options.openintel.radar.range", 8, 512, (int) cfg.radarRange,
                        " blocks", v -> cfg.radarRange = v),
                slider("options.openintel.radar.circles", 0, 8, cfg.radarCircles,
                        "", v -> cfg.radarCircles = v),
                slider("options.openintel.radar.icon_size", 50, 200, (int) (cfg.radarIconSize * 100),
                        "%", v -> cfg.radarIconSize = v / 100f),
                slider("options.openintel.radar.text_size", 50, 200, (int) (cfg.radarTextSize * 100),
                        "%", v -> cfg.radarTextSize = v / 100f)
        );

        body.addHeader(Text.literal("Colors"));
        body.addAll(
                alphaSlider("options.openintel.radar.bg_alpha", cfg.radarBgColor,
                        v -> cfg.radarBgColor = v),
                alphaSlider("options.openintel.radar.fg_alpha", cfg.radarFgColor,
                        v -> cfg.radarFgColor = v)
        );
    }

    /** Live preview: the dial keeps rendering behind the options list. */
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        if (client != null && client.world != null && client.player != null) {
            RadarHud.render(ctx, delta);
        }
    }

    @Override
    public void removed() {
        super.removed();
        OpenIntelClient.config().save();
    }

    // ------------------------------------------------------------ helpers ----

    private static SimpleOption<Boolean> bool(String key, boolean current,
                                              java.util.function.Consumer<Boolean> apply) {
        return SimpleOption.ofBoolean(key, current, apply);
    }

    /** Cycles a RadarBlipFilter; the label resolves through the enum's lang key. */
    private static SimpleOption<OIConfig.RadarBlipFilter> cycle(
            String key, OIConfig.RadarBlipFilter current,
            java.util.function.Consumer<OIConfig.RadarBlipFilter> apply) {
        var values = java.util.List.of(OIConfig.RadarBlipFilter.values());
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": ").append(Text.translatable(v.translationKey)),
                new SimpleOption.PotentialValuesBasedCallbacks<>(values,
                        com.mojang.serialization.Codec.STRING.xmap(
                                OIConfig.RadarBlipFilter::byName, Enum::name)),
                current, apply);
    }

    private static SimpleOption<Integer> slider(String key, int min, int max, int current,
                                                String suffix, java.util.function.Consumer<Integer> apply) {
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": " + v + suffix),
                new SimpleOption.ValidatingIntSliderCallbacks(min, max, true),
                current, apply);
    }

    /** 0–255 slider that writes only the alpha byte of an ARGB color. */
    private static SimpleOption<Integer> alphaSlider(String key, int color,
                                                     java.util.function.Consumer<Integer> apply) {
        int alpha = (color >>> 24) & 0xFF;
        return new SimpleOption<>(key, SimpleOption.emptyTooltip(),
                (text, v) -> text.copy().append(": " + v),
                new SimpleOption.ValidatingIntSliderCallbacks(0, 255, true),
                alpha, v -> apply.accept((color & 0x00FFFFFF) | (v << 24)));
    }
}
