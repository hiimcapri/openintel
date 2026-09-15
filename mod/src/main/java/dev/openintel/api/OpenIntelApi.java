package dev.openintel.api;

import dev.openintel.api.hud.HudApi;
import dev.openintel.api.internal.ApiBridge;
import net.fabricmc.loader.api.FabricLoader;

public final class OpenIntelApi {
    public static final int API_VERSION = 1;
    private OpenIntelApi() { }
    public static boolean isReady() { return ApiBridge.isReady(); }
    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("openintel")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
    public static PlayersApi players() { return ApiBridge.players(); }
    public static SnitchesApi snitches() { return ApiBridge.snitches(); }
    public static PingsApi pings() { return ApiBridge.pings(); }
    public static AllegiancesApi allegiances() { return ApiBridge.allegiances(); }
    public static RelayApi relay() { return ApiBridge.relay(); }
    public static NotificationsApi notifications() { return ApiBridge.notifications(); }
    public static ScreensApi screens() { return ApiBridge.screens(); }
    public static SettingsApi settings() { return ApiBridge.settings(); }
    public static EventsApi events() { return ApiBridge.events(); }
    public static HudApi hud() { return HudApi.getInstance(); }
}
