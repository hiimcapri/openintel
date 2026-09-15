package dev.openintel.api;

import java.util.concurrent.CompletableFuture;

public interface ScreensApi {
    CompletableFuture<ActionResult> openSettings();
    CompletableFuture<ActionResult> openHudEditor();
}
