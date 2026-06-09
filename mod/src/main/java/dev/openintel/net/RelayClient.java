package dev.openintel.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal WebSocket client (JDK built-in, no extra deps) with auto-reconnect.
 * Sends JSON text frames; hands complete inbound messages to a consumer.
 */
public class RelayClient implements WebSocket.Listener {
    private static final Gson GSON = new Gson();

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final StringBuilder partial = new StringBuilder();
    private final Consumer<JsonObject> onMessage;
    private final Consumer<String> onStatus;

    private volatile String url;
    private volatile String token;
    private volatile boolean wantConnected = false;
    private volatile long lastAttempt = 0;
    private int backoffMs = 1000;

    public RelayClient(Consumer<JsonObject> onMessage, Consumer<String> onStatus) {
        this.onMessage = onMessage;
        this.onStatus = onStatus;
    }

    public void connect(String url, String token) {
        this.url = url;
        this.token = token;
        this.wantConnected = true;
        attempt();
    }

    public void disconnect() {
        wantConnected = false;
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
                dropSocket();
            }
        }
    }

    private void attempt() {
        lastAttempt = System.currentTimeMillis();
        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(url), this)
                    .whenComplete((ws, err) -> {
                        if (err != null || ws == null) {
                            backoffMs = Math.min(backoffMs * 2, 30_000);
                            onStatus.accept("relay connect failed: "
                                    + (err == null ? "unknown" : err.getMessage()));
                            return;
                        }
                        socket.set(ws);
                        backoffMs = 1000;
                        JsonObject hello = new JsonObject();
                        hello.addProperty("type", "hello");
                        hello.addProperty("token", token);
                        send(hello);
                        onStatus.accept("connected to relay");
                    });
        } catch (Exception e) {
            backoffMs = Math.min(backoffMs * 2, 30_000);
            onStatus.accept("relay error: " + e.getMessage());
        }
    }

    private void dropSocket() {
        socket.set(null);
    }

    // ---- WebSocket.Listener ----

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
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
        dropSocket();
        onStatus.accept("relay disconnected (" + statusCode + ") " + reason);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        dropSocket();
        onStatus.accept("relay error: " + error.getMessage());
    }
}
