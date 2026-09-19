package dev.openintel;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.openintel.allegiance.AllegianceManager;
import dev.openintel.api.OpenIntelApi;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.config.OIConfig;
import dev.openintel.gui.ClickGuiScreen;
import dev.openintel.gui.HudEditorScreen;
import dev.openintel.macro.AttackMacro;
import dev.openintel.macro.HoldKeyMacro;
import dev.openintel.macro.IceRoadMacro;
import dev.openintel.macro.IntervalMacro;
import dev.openintel.net.RelayClient;
import dev.openintel.ping.PingManager;
import dev.openintel.ping.PingWheelScreen;
import dev.openintel.radar.RadarHud;
import dev.openintel.render.ArmorHud;
import dev.openintel.render.EventFeed;
import dev.openintel.render.MarkerHud;
import dev.openintel.render.PotionHud;
import dev.openintel.render.PresenceHud;
import dev.openintel.tracker.Tracker;
import dev.openintel.xaero.XaeroBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public class OpenIntelClient implements ClientModInitializer {

    private static final KeyMapping.Category OI_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("openintel", "main"));

    private static OIConfig config;
    private static Tracker tracker;
    private static AllegianceManager allegiances;
    private static RelayClient relay;

    /** JourneyMap sync hook — set by the jm entrypoint only when JM is loaded. */
    public static volatile Runnable jmTick;

    private static KeyMapping radarToggleKey;
    private static KeyMapping holdAttackKey;
    private static KeyMapping holdUseKey;
    private static KeyMapping attackToggleKey;
    private static KeyMapping useToggleKey;
    private static KeyMapping iceRoadKey;
    private static KeyMapping pingKey;
    private AttackMacro attackMacro;
    private IntervalMacro useMacro;
    private HoldKeyMacro holdAttackMacro;
    private HoldKeyMacro holdUseMacro;
    private IceRoadMacro iceRoadMacro;

    public static OIConfig config() { return config; }
    public static Tracker tracker() { return tracker; }
    public static AllegianceManager allegiances() { return allegiances; }
    public static RelayClient relay() { return relay; }

    /** All mod keybinds, for display in config screens. */
    public static KeyMapping[] allKeys() {
        return new KeyMapping[]{radarToggleKey, attackToggleKey, useToggleKey,
                holdAttackKey, holdUseKey, iceRoadKey, pingKey};
    }

    @Override
    public void onInitializeClient() {
        config = OIConfig.load();
        tracker = new Tracker();
        allegiances = new AllegianceManager();
        relay = new RelayClient(
                msg -> tracker.handleMessage(msg, Minecraft.getInstance()),
                OpenIntelClient::status);

        registerKeybinds();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            relay.tick();
            tracker.tick(client);
            attackMacro.tick(client);
            useMacro.tick(client);
            holdAttackMacro.tick(client);
            holdUseMacro.tick(client);
            iceRoadMacro.tick(client);
            PingManager.tick();
            Runnable jm = jmTick;
            if (jm != null) jm.run();
            EventFeed.tick(client);
            ApiBridge.settingsChanged();
            while (radarToggleKey.consumeClick()) {
                config.radarEnabled = !config.radarEnabled;
                config.save();
                status("radar " + (config.radarEnabled ? "on" : "off"));
            }
            if (config.pingWheelEnabled && pingKey.consumeClick() && client.player != null
                    && client.screen == null) {
                client.setScreen(new PingWheelScreen(pingKey,
                        PingWheelScreen.physicallyHeld(pingKey)));
            }
        });

        // Markers draw on the HUD layer; the camera is read from the game
        // renderer at draw time (same frame, same render thread).
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("openintel", "markers"),
                (ctx, tickCounter) -> MarkerHud.render(ctx));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("openintel", "radar"),
                (ctx, tickCounter) -> RadarHud.render(ctx, tickCounter.getGameTimeDeltaPartialTick(true)));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("openintel", "presence"),
                (ctx, tickCounter) -> PresenceHud.render(ctx));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("openintel", "eventfeed"),
                (ctx, tickCounter) -> EventFeed.render(ctx));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("openintel", "armor"),
                (ctx, tickCounter) -> ArmorHud.render(ctx));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("openintel", "potions"),
                (ctx, tickCounter) -> PotionHud.render(ctx));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("openintel", "integrations"),
                (ctx, tickCounter) -> OpenIntelApi.hud().renderAll(ctx, tickCounter.getGameTimeDeltaPartialTick(true)));

        ClientEntityEvents.ENTITY_LOAD.register(EventFeed::onEntityLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register(EventFeed::onEntityUnload);
        ClientReceiveMessageEvents.GAME.register(SnitchRelay::onGameMessage);
        // Plugins can deliver alerts through either channel — catch both.
        // The 10s text dedupe in SnitchRelay covers any double-fire.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, instant) ->
                SnitchRelay.onGameMessage(message, false));
        ClientReceiveMessageEvents.MODIFY_GAME.register(SnitchRelay::restyleForChat);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reconnectRelay());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            relay.disconnect();
            EventFeed.clear();
            PingManager.clear();
            tracker.clear();
            allegiances.replaceAll(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        });

        // Xaero World Map overlay — same soft-dep contract as JourneyMap:
        // dev.openintel.xaero is only loaded when the bridge mod is present.
        if (FabricLoader.getInstance().isModLoaded("xaero_world_map_bridge")) {
            try {
                XaeroBridge.register();
            } catch (Throwable ignored) { }
        }

        registerCommands();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> ApiBridge.initialize());
    }

    public static boolean reconnectRelay() {
        Minecraft client = Minecraft.getInstance();
        relay.disconnect();
        tracker.clear();
        PingManager.clear();
        EventFeed.clear();
        allegiances.replaceAll(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        String actual = currentMinecraftServer(client);
        String selected = normalizeMinecraftServer(config.minecraftServer);
        if (actual == null) {
            status("relay disabled outside multiplayer");
            return false;
        }
        if (selected.isEmpty() || !selected.equals(actual)) {
            status("relay disabled on " + actual + " — selected server is "
                    + (selected.isEmpty() ? "not configured" : selected));
            return false;
        }
        relay.connect(config.relayUrl, config.token, actual);
        status("connecting to relay for " + actual);
        return true;
    }

    public static String currentMinecraftServer(Minecraft client) {
        var entry = client.getCurrentServer();
        return entry == null ? null : normalizeMinecraftServer(entry.ip);
    }

    public static String normalizeMinecraftServer(String address) {
        if (address == null) return "";
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        int scheme = normalized.indexOf("://");
        if (scheme >= 0) normalized = normalized.substring(scheme + 3);
        int path = normalized.indexOf('/');
        if (path >= 0) normalized = normalized.substring(0, path);
        while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith(":25565")) normalized = normalized.substring(0, normalized.length() - 6);
        return normalized;
    }

    private void registerKeybinds() {
        radarToggleKey = keybind("key.openintel.radar", GLFW.GLFW_KEY_R);
        holdAttackKey = keybind("key.openintel.hold_attack", GLFW.GLFW_KEY_MINUS);
        holdUseKey = keybind("key.openintel.hold_use", GLFW.GLFW_KEY_EQUAL);
        attackToggleKey = keybind("key.openintel.attack_macro", GLFW.GLFW_KEY_0);
        useToggleKey = keybind("key.openintel.use_macro", GLFW.GLFW_KEY_RIGHT_BRACKET);
        iceRoadKey = keybind("key.openintel.ice_road", GLFW.GLFW_KEY_BACKSPACE);
        pingKey = keybind("key.openintel.ping", GLFW.GLFW_KEY_G);

        attackMacro = new AttackMacro(attackToggleKey);
        useMacro = new IntervalMacro(useToggleKey,
                () -> Minecraft.getInstance().options.keyUse,
                () -> config.useMacroIntervalMs, "use macro");
        holdAttackMacro = new HoldKeyMacro(holdAttackKey,
                () -> Minecraft.getInstance().options.keyAttack, "hold attack");
        holdUseMacro = new HoldKeyMacro(holdUseKey,
                () -> Minecraft.getInstance().options.keyUse, "hold use");
        iceRoadMacro = new IceRoadMacro(iceRoadKey);
    }

    private static KeyMapping keybind(String id, int key) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(id, InputConstants.Type.KEYSYM, key, OI_CATEGORY));
    }

    private static int setRelayCut(String action) {
        if (!relay.isAuthenticated()) {
            status("connect to the relay before using /oi cut");
            return 0;
        }
        JsonObject message = new JsonObject();
        message.addProperty("type", "cut");
        message.addProperty("action", action);
        if (!relay.trySend(message)) {
            status("could not send cut request");
            return 0;
        }
        return 1;
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("oi")
                        .then(ClientCommands.literal("reconnect").executes(c -> {
                            reconnectRelay();
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(c -> {
                            status(relay.isConnected() ? "connected to " + config.relayUrl
                                                       : "not connected");
                            return 1;
                        }))
                        .then(ClientCommands.literal("cut")
                                .requires(source -> OpenIntelApi.relay().snapshot().role()
                                        .map(role -> role.equalsIgnoreCase("admin")).orElse(false))
                                .executes(c -> setRelayCut("toggle"))
                                .then(ClientCommands.literal("on").executes(c -> setRelayCut("on")))
                                .then(ClientCommands.literal("off").executes(c -> setRelayCut("off")))
                                .then(ClientCommands.literal("status").executes(c -> setRelayCut("status"))))
                        .then(ClientCommands.literal("radar").executes(c -> {
                            // Defer one tick — the chat screen closes itself
                            // after the command dispatches and would wipe it.
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreen(new ClickGuiScreen(null, "radar")));
                            return 1;
                        }))
                        .then(ClientCommands.literal("macros").executes(c -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreen(new ClickGuiScreen(null, "macros")));
                            return 1;
                        }))
                        .then(ClientCommands.literal("settings").executes(c -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreen(new ClickGuiScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("hud").executes(c -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreen(new HudEditorScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("ping").executes(c -> {
                            Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreen(
                                            new PingWheelScreen(pingKey, false)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("snitchtest").executes(c -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.player != null && mc.level != null) {
                                tracker.addSnitchHit("Test Vault", "Capri", "local",
                                        mc.player.getX() + 40, mc.player.getY(), mc.player.getZ(),
                                        mc.level.dimension().identifier().toString(),
                                        System.currentTimeMillis());
                                status("test snitch marker placed 40m out — fades over 2min");
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("url")
                                .then(ClientCommands.argument("url", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            config.relayUrl = StringArgumentType.getString(c, "url");
                                            config.save();
                                            status("relay url set — run /oi reconnect");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("token")
                                .then(ClientCommands.argument("token", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            config.token = StringArgumentType.getString(c, "token");
                                            config.save();
                                            status("token saved — run /oi reconnect");
                                            return 1;
                                        })))
                        // Captain-only (enforced server-side): mark a priority target.
                        .then(ClientCommands.literal("focus")
                                .then(ClientCommands.literal("clear").executes(c -> {
                                    sendFocus("clear", null);
                                    return 1;
                                }))
                                .then(ClientCommands.argument("player", StringArgumentType.word())
                                        .executes(c -> {
                                            sendFocus("add", StringArgumentType.getString(c, "player"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("unfocus")
                                .then(ClientCommands.argument("player", StringArgumentType.word())
                                        .executes(c -> {
                                            sendFocus("remove", StringArgumentType.getString(c, "player"));
                                            return 1;
                                        })))));
    }

    private static void sendFocus(String action, String subject) {
        var result = switch (action) {
            case "clear" -> OpenIntelApi.allegiances().requestClearFocus();
            case "remove" -> OpenIntelApi.allegiances().requestUnfocus(subject);
            default -> OpenIntelApi.allegiances().requestFocus(subject);
        };
        result.thenAccept(value -> { if (!value.accepted()) status(value.message()); });
    }

    public static void status(String message) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("[OpenIntel] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
            }
        });
    }
}
