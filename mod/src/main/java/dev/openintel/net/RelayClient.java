package dev.openintel.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal WebSocket client (JDK built-in, no extra deps) with auto-reconnect.
 * Sends JSON text frames; hands complete inbound messages to a consumer.
 */
public class RelayClient implements WebSocket.Listener {
    private static final Gson GSON = new Gson();

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final ConcurrentHashMap<WebSocket, StringBuilder> partials = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong inFlightGeneration = new AtomicLong(-1);
    private final Consumer<JsonObject> onMessage;
    private final Consumer<String> onStatus;

    private volatile String url;
    private volatile String token;
    private volatile String minecraftServer;
    private volatile boolean wantConnected = false;
    private volatile long lastAttempt = 0;
    private volatile int backoffMs = 1000;

    public RelayClient(Consumer<JsonObject> onMessage, Consumer<String> onStatus) {
        this.onMessage = onMessage;
        this.onStatus = onStatus;
    }

    public void connect(String url, String token, String minecraftServer) {
        this.url = url;
        this.token = token;
        this.minecraftServer = minecraftServer;
        this.wantConnected = true;
        generation.incrementAndGet();
        WebSocket previous = socket.getAndSet(null);
        if (previous != null) previous.sendClose(WebSocket.NORMAL_CLOSURE, "reconnecting");
        attempt();
    }

    public void disconnect() {
        wantConnected = false;
        generation.incrementAndGet();
        WebSocket ws = socket.getAndSet(null);
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

    public boolean isConnected() {
        return socket.get() != null;
    }

    /** Call from the client tick loop; reconnects with backoff if dropped. */
    public void tick() {
        if (wantConnected && socket.get() == null
                && System.currentTimeMillis() - lastAttempt > backoffMs) {
            attempt();
        }
    }

    public void send(JsonObject message) {
        WebSocket ws = socket.get();
        if (ws != null) {
            try {
                ws.sendText(GSON.toJson(message), true);
            } catch (Exception e) {
                dropSocket(ws);
            }
        }
    }

    private void attempt() {
        long attemptGeneration = generation.get();
        if (!inFlightGeneration.compareAndSet(-1, attemptGeneration)) return;
        lastAttempt = System.currentTimeMillis();
        String attemptUrl = url;
        String attemptToken = token;
        String attemptMinecraftServer = minecraftServer;
        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(attemptUrl), this)
                    .whenComplete((ws, err) -> {
                        inFlightGeneration.compareAndSet(attemptGeneration, -1);
                        if (attemptGeneration != generation.get() || !wantConnected) {
                            if (ws != null) ws.abort();
                            return;
                        }
                        if (err != null || ws == null) {
                            backoffMs = Math.min(backoffMs * 2, 30_000);
                            onStatus.accept("relay connect failed: "
                                    + (err == null ? "unknown" : err.getMessage()));
                            return;
                        }
                        socket.set(ws);
                        if (attemptGeneration != generation.get() || !wantConnected) {
                            if (socket.compareAndSet(ws, null)) ws.abort();
                            return;
                        }
                        backoffMs = 1000;
                        JsonObject hello = new JsonObject();
                        hello.addProperty("type", "hello");
                        hello.addProperty("token", attemptToken);
                        hello.addProperty("minecraftServer", attemptMinecraftServer);
                        ws.sendText(GSON.toJson(hello), true);
                        onStatus.accept("connected to relay");
                    });
        } catch (Exception e) {
            inFlightGeneration.compareAndSet(attemptGeneration, -1);
            if (attemptGeneration != generation.get() || !wantConnected) return;
            backoffMs = Math.min(backoffMs * 2, 30_000);
            onStatus.accept("relay error: " + e.getMessage());
        }
    }

    private void dropSocket(WebSocket ws) {
        socket.compareAndSet(ws, null);
        partials.remove(ws);
    }

    // ---- WebSocket.Listener ----

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        if (socket.get() != ws) {
            ws.request(1);
            return null;
        }
        StringBuilder partial = partials.computeIfAbsent(ws, ignored -> new StringBuilder());
        partial.append(data);
        if (last) {
            String full = partial.toString();
            partial.setLength(0);
            try {
                onMessage.accept(GSON.fromJson(full, JsonObject.class));
            } catch (Exception ignored) {
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        partials.remove(ws);
        boolean terminal = statusCode == 4001 || statusCode == 4003;
        if (terminal) {
            wantConnected = false;
            generation.incrementAndGet();
        }
        if (socket.compareAndSet(ws, null)) {
            onStatus.accept(terminal
                    ? "relay connection stopped (" + statusCode + ") " + reason
                    : "relay disconnected (" + statusCode + ") " + reason);
        }
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        partials.remove(ws);
        if (socket.compareAndSet(ws, null)) {
            onStatus.accept("relay error: " + error.getMessage());
        }
    }
}
