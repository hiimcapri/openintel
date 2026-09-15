package dev.openintel.api;

import java.util.concurrent.CompletableFuture;

public interface NotificationsApi {
    CompletableFuture<ActionResult> show(String text, int color);
}
