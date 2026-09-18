package dev.openintel;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.openintel.allegiance.AllegianceManager;
import dev.openintel.api.OpenIntelApi;
import dev.openintel.api.internal.ApiBridge;
import dev.openintel.config.OIConfig;
import dev.openintel.gui.HudEditorScreen;
import dev.openintel.gui.MacroConfigScreen;
import dev.openintel.gui.OpenIntelConfigScreen;
import dev.openintel.gui.RadarConfigScreen;
import dev.openintel.macro.AttackMacro;
import dev.openintel.macro.HoldKeyMacro;
import dev.openintel.macro.IceRoadMacro;
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
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public class OpenIntelClient implements ClientModInitializer {

    private static final KeyBinding.Category OI_CATEGORY =
            KeyBinding.Category.create(Identifier.of("openintel", "main"));

    private static OIConfig config;
    private static Tracker tracker;
    private static AllegianceManager allegiances;
    private static RelayClient relay;

    private static KeyBinding radarToggleKey;
    private static KeyBinding holdAttackKey;
    private static KeyBinding holdUseKey;
    private static KeyBinding attackToggleKey;
    private static KeyBinding iceRoadKey;
    private static KeyBinding pingKey;
    private AttackMacro attackMacro;
    private HoldKeyMacro holdAttackMacro;
    private HoldKeyMacro holdUseMacro;
    private IceRoadMacro iceRoadMacro;

    public static OIConfig config() { return config; }
    public static Tracker tracker() { return tracker; }
    public static AllegianceManager allegiances() { return allegiances; }
    public static RelayClient relay() { return relay; }

    /** All mod keybinds, for display in config screens. */
    public static KeyBinding[] allKeys() {
        return new KeyBinding[]{radarToggleKey, attackToggleKey, holdAttackKey, holdUseKey,
                iceRoadKey, pingKey};
    }

    @Override
    public void onInitializeClient() {
        config = OIConfig.load();
        tracker = new Tracker();
        allegiances = new AllegianceManager();
        relay = new RelayClient(
                msg -> tracker.handleMessage(msg, MinecraftClient.getInstance()),
                OpenIntelClient::status);

        registerKeybinds();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            relay.tick();
            tracker.tick(client);
            attackMacro.tick(client);
            holdAttackMacro.tick(client);
            holdUseMacro.tick(client);
            iceRoadMacro.tick(client);
            PingManager.tick();
            EventFeed.tick(client);
            ApiBridge.settingsChanged();
            while (radarToggleKey.wasPressed()) {
                config.radarEnabled = !config.radarEnabled;
                config.save();
                status("radar " + (config.radarEnabled ? "on" : "off"));
            }
            if (config.pingWheelEnabled && pingKey.wasPressed() && client.player != null
                    && client.currentScreen == null) {
                client.setScreen(new PingWheelScreen(pingKey,
                        PingWheelScreen.physicallyHeld(pingKey)));
            }
        });

        // Markers draw on the HUD layer; the camera is read from the game
        // renderer at draw time (same frame, same render thread).
        HudElementRegistry.addLast(Identifier.of("openintel", "markers"),
                (ctx, tickCounter) -> MarkerHud.render(ctx));
        HudElementRegistry.addLast(Identifier.of("openintel", "radar"),
                (ctx, tickCounter) -> RadarHud.render(ctx, tickCounter.getTickProgress(true)));
        HudElementRegistry.addLast(Identifier.of("openintel", "presence"),
                (ctx, tickCounter) -> PresenceHud.render(ctx));
        HudElementRegistry.addLast(Identifier.of("openintel", "eventfeed"),
                (ctx, tickCounter) -> EventFeed.render(ctx));
        HudElementRegistry.addLast(Identifier.of("openintel", "armor"),
                (ctx, tickCounter) -> ArmorHud.render(ctx));
        HudElementRegistry.addLast(Identifier.of("openintel", "potions"),
                (ctx, tickCounter) -> PotionHud.render(ctx));
        HudElementRegistry.addLast(Identifier.of("openintel", "integrations"),
                (ctx, tickCounter) -> OpenIntelApi.hud().renderAll(ctx, tickCounter.getTickProgress(true)));

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

        registerCommands();
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> ApiBridge.initialize());
    }

    public static boolean reconnectRelay() {
        MinecraftClient client = MinecraftClient.getInstance();
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

    public static String currentMinecraftServer(MinecraftClient client) {
        var entry = client.getCurrentServerEntry();
        return entry == null ? null : normalizeMinecraftServer(entry.address);
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
        iceRoadKey = keybind("key.openintel.ice_road", GLFW.GLFW_KEY_BACKSPACE);
        pingKey = keybind("key.openintel.ping", GLFW.GLFW_KEY_G);

        attackMacro = new AttackMacro(attackToggleKey);
        holdAttackMacro = new HoldKeyMacro(holdAttackKey,
                () -> MinecraftClient.getInstance().options.attackKey, "hold attack");
        holdUseMacro = new HoldKeyMacro(holdUseKey,
                () -> MinecraftClient.getInstance().options.useKey, "hold use");
        iceRoadMacro = new IceRoadMacro(iceRoadKey);
    }

    private static KeyBinding keybind(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding(id, InputUtil.Type.KEYSYM, key, OI_CATEGORY));
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
                dispatcher.register(ClientCommandManager.literal("oi")
                        .then(ClientCommandManager.literal("reconnect").executes(c -> {
                            reconnectRelay();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(c -> {
                            status(relay.isConnected() ? "connected to " + config.relayUrl
                                                       : "not connected");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("cut")
                                .requires(source -> OpenIntelApi.relay().snapshot().role()
                                        .map(role -> role.equalsIgnoreCase("admin")).orElse(false))
                                .executes(c -> setRelayCut("toggle"))
                                .then(ClientCommandManager.literal("on").executes(c -> setRelayCut("on")))
                                .then(ClientCommandManager.literal("off").executes(c -> setRelayCut("off")))
                                .then(ClientCommandManager.literal("status").executes(c -> setRelayCut("status"))))
                        .then(ClientCommandManager.literal("radar").executes(c -> {
                            // Defer one tick — the chat screen closes itself
                            // after the command dispatches and would wipe it.
                            MinecraftClient.getInstance().execute(() ->
                                    MinecraftClient.getInstance().setScreen(new RadarConfigScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("macros").executes(c -> {
                            MinecraftClient.getInstance().execute(() ->
                                    MinecraftClient.getInstance().setScreen(new MacroConfigScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("settings").executes(c -> {
                            MinecraftClient.getInstance().execute(() ->
                                    MinecraftClient.getInstance().setScreen(new OpenIntelConfigScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("hud").executes(c -> {
                            MinecraftClient.getInstance().execute(() ->
                                    MinecraftClient.getInstance().setScreen(new HudEditorScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("ping").executes(c -> {
                            MinecraftClient.getInstance().execute(() ->
                                    MinecraftClient.getInstance().setScreen(
                                            new PingWheelScreen(pingKey, false)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("snitchtest").executes(c -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player != null && mc.world != null) {
                                tracker.addSnitchHit("Test Vault", "Capri", "local",
                                        mc.player.getX() + 40, mc.player.getY(), mc.player.getZ(),
                                        mc.world.getRegistryKey().getValue().toString(),
                                        System.currentTimeMillis());
                                status("test snitch marker placed 40m out — fades over 2min");
                            }
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("url")
                                .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            config.relayUrl = StringArgumentType.getString(c, "url");
                                            config.save();
                                            status("relay url set — run /oi reconnect");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("token")
                                .then(ClientCommandManager.argument("token", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            config.token = StringArgumentType.getString(c, "token");
                                            config.save();
                                            status("token saved — run /oi reconnect");
                                            return 1;
                                        })))
                        // Captain-only (enforced server-side): mark a priority target.
                        .then(ClientCommandManager.literal("focus")
                                .then(ClientCommandManager.literal("clear").executes(c -> {
                                    sendFocus("clear", null);
                                    return 1;
                                }))
                                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                        .executes(c -> {
                                            sendFocus("add", StringArgumentType.getString(c, "player"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("unfocus")
                                .then(ClientCommandManager.argument("player", StringArgumentType.word())
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
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[OpenIntel] ").formatted(Formatting.GOLD)
                        .append(Text.literal(message).formatted(Formatting.GRAY)), false);
            }
        });
    }
}
