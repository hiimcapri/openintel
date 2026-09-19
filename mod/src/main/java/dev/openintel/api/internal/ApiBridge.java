package dev.openintel.api.internal;

import com.google.gson.JsonObject;
import dev.openintel.OpenIntelClient;
import dev.openintel.api.*;
import dev.openintel.gui.ClickGuiScreen;
import dev.openintel.gui.HudEditorScreen;
import dev.openintel.ping.PingManager;
import dev.openintel.render.EventFeed;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ApiBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("openintel-api");
    private static volatile boolean ready;
    private static volatile SettingsApi.View settingsState;
    private static final SettingsApi SETTINGS = () -> Optional.ofNullable(settingsState);
    private static volatile List<Snapshots.Player> playerState = List.of();
    private static volatile List<Snapshots.Snitch> snitchState = List.of();
    private static volatile List<Snapshots.Ping> pingState = List.of();
    private static volatile Snapshots.Allegiances allegianceState = new Snapshots.Allegiances(Map.of());
    private static volatile Snapshots.Connection connectionState = new Snapshots.Connection(
            Snapshots.ConnectionState.DISCONNECTED, Optional.empty(), Optional.empty());
    private static final CopyOnWriteArrayList<Listener<?>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ArrayDeque<ApiEvent> PENDING = new ArrayDeque<>();
    private static final int MAX_EVENTS_PER_DISPATCH = 256;
    private static boolean delivering;
    private static int eventsInDispatch;
    private static boolean overflowReported;

    private static final PlayersApi PLAYERS = () -> playerState;
    private static final SnitchesApi SNITCHES = () -> snitchState;
    private static final RelayApi RELAY = () -> connectionState;
    private static final PingsApi PINGS = new PingsApi() {
        public List<Snapshots.Ping> list() { return pingState; }
        public CompletableFuture<ActionResult> send(String label, int color, double x, double y, double z, String dimension) {
            return action(true, () -> {
                if (!validText(label, 64)) return invalid("Ping label must contain 1-64 printable characters");
                if (!validPosition(x, y, z, dimension)) return invalid("Ping requires finite world coordinates and a valid dimension Identifier (maximum 128 characters)");
                return PingManager.sendShared(label, color, x, y, z, Identifier.of(dimension).toString()) ? submitted()
                        : new ActionResult(ActionResult.Status.FAILED, "Relay could not submit the ping");
            });
        }
    };
    private static final AllegiancesApi ALLEGIANCES = new AllegiancesApi() {
        public Snapshots.Allegiances snapshot() { return allegianceState; }
        public CompletableFuture<ActionResult> requestFocus(String player) { return focus("add", player); }
        public CompletableFuture<ActionResult> requestUnfocus(String player) { return focus("remove", player); }
        public CompletableFuture<ActionResult> requestClearFocus() { return focus("clear", null); }
    };
    private static final NotificationsApi NOTIFICATIONS = (text, color) -> action(false, () -> {
        if (!validText(text, 512)) return invalid("Notification must contain 1-512 printable characters");
        EventFeed.add(text, color);
        return completed("Notification delivered; feed visibility follows user settings");
    });
    private static final ScreensApi SCREENS = new ScreensApi() {
        public CompletableFuture<ActionResult> openSettings() {
            return screen(false);
        }
        public CompletableFuture<ActionResult> openHudEditor() {
            return screen(true);
        }
    };
    private static final EventsApi EVENTS = new EventsApi() {
        public <E extends ApiEvent> Subscription listen(Class<E> type, Consumer<? super E> consumer) {
            Listener<E> listener = new Listener<>(Objects.requireNonNull(type), Objects.requireNonNull(consumer));
            LISTENERS.add(listener);
            return () -> {
                listener.active.set(false);
                LISTENERS.remove(listener);
            };
        }
    };

    private ApiBridge() { }
    public static boolean isReady() { return ready; }
    public static PlayersApi players() { return PLAYERS; }
    public static SnitchesApi snitches() { return SNITCHES; }
    public static PingsApi pings() { return PINGS; }
    public static AllegiancesApi allegiances() { return ALLEGIANCES; }
    public static RelayApi relay() { return RELAY; }
    public static NotificationsApi notifications() { return NOTIFICATIONS; }
    public static ScreensApi screens() { return SCREENS; }
    public static EventsApi events() { return EVENTS; }
    public static SettingsApi settings() { return SETTINGS; }

    public static void settingsChanged() {
        if (!ready) return;
        requireClientThread();
        var c = OpenIntelClient.config();
        var value = new SettingsApi.View(
                new SettingsApi.Radar(c.radarEnabled, c.radarX, c.radarY, c.radarSize, c.radarRange,
                        c.radarCircles, c.radarNorthUp, c.radarCompression > 0, c.radarShowItems,
                        c.radarShowVehicles, c.radarShowRelay, c.radarIconSize, c.radarTextSize,
                        c.radarBgColor, c.radarFgColor),
                new SettingsApi.Markers(c.relayRendering, c.markVisiblePlayers, c.edgeChevrons,
                        c.relayOpacity, c.staleDecay, c.staleAfterMs, c.maxMarkerDistance,
                        c.edgeTopXPct, c.edgeBottomXPct, c.edgeLeftYPct, c.edgeRightYPct,
                        c.edgeRowInset, c.edgeColumnInset),
                new SettingsApi.Snitches(c.snitchRelay, c.snitchMarkerSeconds, c.snitchMarkerRange,
                        c.snitchMarkerColor, c.snitchMarkerColorAuto),
                new SettingsApi.Overlays(new SettingsApi.Overlay(c.presenceEnabled, c.presenceX, c.presenceY),
                        c.presenceMaxRows, c.presenceShowAllDims,
                        new SettingsApi.Overlay(c.eventFeedEnabled, c.eventFeedX, c.eventFeedY), c.eventFeedSeconds,
                        new SettingsApi.Overlay(c.armorHudEnabled, c.armorHudX, c.armorHudY),
                        new SettingsApi.Overlay(c.potionHudEnabled, c.potionHudX, c.potionHudY)),
                c.pingWheelEnabled, c.pingSeconds, c.reportIntervalMs);
        if (!value.equals(settingsState)) {
            settingsState = value;
            publish(new ApiEvent.SettingsChanged(value));
        }
    }

    public static void initialize() {
        requireClientThread();
        if (ready) return;
        ready = true;
        settingsChanged();
        for (var entry : FabricLoader.getInstance().getEntrypointContainers("openintel:integration", OpenIntelIntegration.class)) {
            try {
                entry.getEntrypoint().onOpenIntelInitialize();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable error) {
                LOGGER.error("OpenIntel integration failed: {}", entry.getProvider().getMetadata().getId(), error);
            }
        }
        publish(new ApiEvent.Ready(OpenIntelApi.modVersion()));
        LOGGER.info("OpenIntel API v{} initialized", OpenIntelApi.API_VERSION);
    }

    public static void trackerChanged(ApiEvent.Cause cause) {
        requireClientThread();
        if (!ready) return;
        var players = new ArrayList<Snapshots.Player>();
        for (var p : OpenIntelClient.tracker().all()) {
            players.add(new Snapshots.Player(p.name, new Snapshots.Position(p.x, p.y, p.z, p.dimension),
                    p.lastSeen, p.reporter, Snapshots.Allegiance.valueOf(p.allegiance.name())));
        }
        players.sort(Comparator.comparing(Snapshots.Player::name));
        var snitches = new ArrayList<Snapshots.Snitch>();
        for (var s : OpenIntelClient.tracker().snitchHits()) {
            snitches.add(new Snapshots.Snitch(s.snitch, s.player, s.reporter,
                    new Snapshots.Position(s.x, s.y, s.z, s.dimension), s.t));
        }
        snitches.sort(Comparator.comparing(Snapshots.Snitch::player).thenComparing(Snapshots.Snitch::snitch));
        var oldPlayers = playerState;
        var oldSnitches = snitchState;
        playerState = List.copyOf(players);
        snitchState = List.copyOf(snitches);
        if (!oldPlayers.equals(playerState) || cause == ApiEvent.Cause.CLEAR)
            publish(new ApiEvent.PlayersChanged(oldPlayers, playerState, cause));
        if (!oldSnitches.equals(snitchState) || cause == ApiEvent.Cause.CLEAR)
            publish(new ApiEvent.SnitchesChanged(oldSnitches, snitchState, cause));
    }

    public static void pingsChanged(ApiEvent.Cause cause) {
        requireClientThread();
        if (!ready) return;
        var values = new ArrayList<Snapshots.Ping>();
        for (var p : PingManager.active()) {
            values.add(new Snapshots.Ping(p.id, p.label, p.color, p.sender,
                    new Snapshots.Position(p.x, p.y, p.z, p.dimension), p.expiresAt));
        }
        values.sort(Comparator.comparing(Snapshots.Ping::id));
        var previous = pingState;
        pingState = List.copyOf(values);
        if (!previous.equals(pingState) || cause == ApiEvent.Cause.CLEAR)
            publish(new ApiEvent.PingsChanged(previous, pingState, cause));
    }

    public static void allegiancesChanged(Collection<String> users, Collection<String> allies,
                                           Collection<String> enemies, Collection<String> focus) {
        requireClientThread();
        if (!ready) return;
        Map<String, Snapshots.Allegiance> values = new HashMap<>();
        putNames(values, allies, Snapshots.Allegiance.ALLY);
        putNames(values, enemies, Snapshots.Allegiance.ENEMY);
        putNames(values, users, Snapshots.Allegiance.FRIEND);
        putNames(values, focus, Snapshots.Allegiance.FOCUS);
        var previous = allegianceState;
        allegianceState = new Snapshots.Allegiances(values);
        if (!previous.equals(allegianceState)) publish(new ApiEvent.AllegiancesChanged(previous, allegianceState));
    }

    private static void putNames(Map<String, Snapshots.Allegiance> values, Collection<String> names, Snapshots.Allegiance value) {
        if (names != null) names.forEach(name -> values.put(name.toLowerCase(Locale.ROOT), value));
    }

    public static void connectionChanged(Snapshots.ConnectionState state, String server, String role) {
        requireClientThread();
        if (!ready) return;
        var previous = connectionState;
        connectionState = new Snapshots.Connection(state, Optional.ofNullable(server), Optional.ofNullable(role));
        if (!previous.equals(connectionState)) publish(new ApiEvent.ConnectionChanged(previous, connectionState));
    }

    public static void notification(String text, int color, String source) {
        requireClientThread();
        if (ready) publish(new ApiEvent.NotificationReceived(new Snapshots.Notification(text, color, System.currentTimeMillis(), source)));
    }

    public static void notificationsCleared() {
        requireClientThread();
        if (ready) publish(new ApiEvent.NotificationsCleared());
    }

    public static void snitchArrived(Snapshots.SnitchArrival arrival) {
        requireClientThread();
        if (ready) publish(new ApiEvent.SnitchArrived(arrival));
    }

    public static void relaySnitch(JsonObject msg) {
        Optional<Snapshots.Position> position = Optional.empty();
        String world = string(msg, "world", "openintel:unknown");
        if (msg.has("x") && msg.has("y") && msg.has("z")) {
            double x = msg.get("x").getAsDouble(), y = msg.get("y").getAsDouble(), z = msg.get("z").getAsDouble();
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
                position = Optional.of(new Snapshots.Position(x, y, z, world));
        }
        snitchArrived(new Snapshots.SnitchArrival(string(msg, "snitch", "?"), string(msg, "player", "?"),
                string(msg, "from", string(msg, "reporter", "?")), position,
                string(msg, "action", "tripped a snitch"), string(msg, "message", ""),
                msg.has("t") ? msg.get("t").getAsLong() : System.currentTimeMillis()));
    }

    private static String string(JsonObject msg, String key, String fallback) {
        return msg.has(key) && !msg.get(key).isJsonNull() ? msg.get(key).getAsString() : fallback;
    }

    private static CompletableFuture<ActionResult> focus(String command, String player) {
        return action(true, () -> {
            if (!command.equals("clear") && (player == null || !player.matches("[A-Za-z0-9_]{3,16}")))
                return invalid("Player name must be 3-16 ASCII letters, digits, or underscores");
            JsonObject message = new JsonObject();
            message.addProperty("type", "focus");
            message.addProperty("action", command);
            if (player != null) message.addProperty("subject", player);
            return OpenIntelClient.relay().trySend(message) ? submitted()
                    : new ActionResult(ActionResult.Status.FAILED, "Relay could not submit the focus request");
        });
    }

    private static CompletableFuture<ActionResult> screen(boolean editor) {
        return action(false, () -> {
            var client = MinecraftClient.getInstance();
            client.setScreen(editor ? new HudEditorScreen(client.currentScreen) : new ClickGuiScreen(client.currentScreen));
            return completed("Screen opened");
        });
    }

    private static CompletableFuture<ActionResult> action(boolean shared, Supplier<ActionResult> operation) {
        if (!ready) return CompletableFuture.completedFuture(new ActionResult(ActionResult.Status.NOT_READY, "OpenIntel has not initialized"));
        var future = new CompletableFuture<ActionResult>();
        try {
            var client = MinecraftClient.getInstance();
            var submittedWorld = client.world;
            var submittedRelay = OpenIntelClient.relay();
            long submittedGeneration = submittedRelay.sessionGeneration();
            client.execute(() -> {
                if (future.isCancelled()) return;
                try {
                    if (client.world != submittedWorld || OpenIntelClient.relay() != submittedRelay
                            || submittedRelay.sessionGeneration() != submittedGeneration) {
                        future.complete(new ActionResult(ActionResult.Status.SESSION_CHANGED,
                                "The game or relay session changed before this action could execute; submit a new request"));
                        return;
                    }
                    if (shared) {
                        if (client.player == null || client.world == null || client.isInSingleplayer()
                                || OpenIntelClient.currentMinecraftServer(client) == null) {
                            future.complete(new ActionResult(ActionResult.Status.NOT_IN_MULTIPLAYER, "Join the selected multiplayer server first"));
                            return;
                        }
                        String selected = OpenIntelClient.normalizeMinecraftServer(OpenIntelClient.config().minecraftServer);
                        if (selected.isEmpty() || !selected.equals(OpenIntelClient.currentMinecraftServer(client))) {
                            future.complete(new ActionResult(ActionResult.Status.WRONG_SERVER, "This is not the selected Minecraft server"));
                            return;
                        }
                        if (!submittedRelay.isAuthenticated()) {
                            future.complete(new ActionResult(ActionResult.Status.NOT_AUTHENTICATED, "Relay authentication has not completed"));
                            return;
                        }
                    }
                    future.complete(operation.get());
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable error) {
                    LOGGER.error("OpenIntel API action failed", error);
                    future.complete(new ActionResult(ActionResult.Status.FAILED, "Action failed; see the client log"));
                }
            });
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable error) {
            future.complete(new ActionResult(ActionResult.Status.FAILED, "Client executor is unavailable"));
        }
        return future;
    }

    private static boolean validText(String text, int maximum) {
        return text != null && !text.isBlank() && text.length() <= maximum
                && text.chars().noneMatch(c -> Character.isISOControl(c) || c == 0x00a7);
    }

    private static boolean validPosition(double x, double y, double z, String dimension) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 30_000_000
                || dimension == null || dimension.length() > 128) return false;
        return dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && Identifier.tryParse(dimension) != null;
    }

    private static ActionResult completed(String message) { return new ActionResult(ActionResult.Status.COMPLETED, message); }
    private static ActionResult submitted() {
        return new ActionResult(ActionResult.Status.SUBMITTED, "Submitted to relay; acceptance and permissions remain server-authoritative");
    }
    private static ActionResult invalid(String message) { return new ActionResult(ActionResult.Status.INVALID_ARGUMENT, message); }

    private static void requireClientThread() {
        if (!MinecraftClient.getInstance().isOnThread()) throw new IllegalStateException("OpenIntel mutation requires the client thread");
    }

    private static void publish(ApiEvent event) {
        if (delivering && eventsInDispatch >= MAX_EVENTS_PER_DISPATCH) {
            if (!overflowReported) {
                overflowReported = true;
                LOGGER.warn("OpenIntel event dispatch exceeded {} events; dropping excess recursive events (further overflow logs suppressed)", MAX_EVENTS_PER_DISPATCH);
            }
            return;
        }
        PENDING.addLast(event);
        if (delivering) {
            eventsInDispatch++;
            return;
        }
        delivering = true;
        eventsInDispatch = 1;
        try {
            while (!PENDING.isEmpty()) {
                var next = PENDING.removeFirst();
                for (var listener : LISTENERS) listener.deliver(next);
            }
        } finally {
            PENDING.clear();
            delivering = false;
            eventsInDispatch = 0;
        }
    }

    private static final class Listener<E extends ApiEvent> {
        private final Class<E> type;
        private final Consumer<? super E> consumer;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Listener(Class<E> type, Consumer<? super E> consumer) { this.type = type; this.consumer = consumer; }
        private void deliver(ApiEvent event) {
            if (!active.get() || !type.isInstance(event)) return;
            try {
                consumer.accept(type.cast(event));
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable error) {
                active.set(false);
                LISTENERS.remove(this);
                LOGGER.error("OpenIntel API listener disabled after failure for {}; register a new subscription to retry", type.getName(), error);
            }
        }
    }
}
