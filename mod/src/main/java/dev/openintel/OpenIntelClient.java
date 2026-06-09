package dev.openintel;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.openintel.allegiance.AllegianceManager;
import dev.openintel.config.OIConfig;
import dev.openintel.net.RelayClient;
import dev.openintel.render.MarkerHud;
import dev.openintel.tracker.Tracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public class OpenIntelClient implements ClientModInitializer {

    private static OIConfig config;
    private static Tracker tracker;
    private static AllegianceManager allegiances;
    private static RelayClient relay;

    public static OIConfig config() { return config; }
    public static Tracker tracker() { return tracker; }
    public static AllegianceManager allegiances() { return allegiances; }
    public static RelayClient relay() { return relay; }

    @Override
    public void onInitializeClient() {
        config = OIConfig.load();
        tracker = new Tracker();
        allegiances = new AllegianceManager();
        relay = new RelayClient(
                msg -> tracker.handleMessage(msg, MinecraftClient.getInstance()),
                OpenIntelClient::status);

        relay.connect(config.relayUrl, config.token);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            relay.tick();
            tracker.tick(client);
        });

        // Markers draw on the HUD layer; the camera is read from the game
        // renderer at draw time (same frame, same render thread).
        HudElementRegistry.addLast(Identifier.of("openintel", "markers"),
                (ctx, tickCounter) -> MarkerHud.render(ctx));

        registerCommands();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("oi")
                        .then(ClientCommandManager.literal("reconnect").executes(c -> {
                            relay.disconnect();
                            relay.connect(config.relayUrl, config.token);
                            status("reconnecting…");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(c -> {
                            status(relay.isConnected() ? "connected to " + config.relayUrl
                                                       : "not connected");
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
        if (!relay.isConnected()) {
            status("not connected to relay");
            return;
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "focus");
        msg.addProperty("action", action);
        if (subject != null) msg.addProperty("subject", subject);
        relay.send(msg);
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
