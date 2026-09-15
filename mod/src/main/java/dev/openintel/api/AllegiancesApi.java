package dev.openintel.api;

import java.util.concurrent.CompletableFuture;

public interface AllegiancesApi {
    Snapshots.Allegiances snapshot();
    default Snapshots.Allegiance of(String name) { return snapshot().of(name); }
    default boolean isEnemy(String name) {
        var value = of(name);
        return value == Snapshots.Allegiance.ENEMY || value == Snapshots.Allegiance.FOCUS;
    }
    CompletableFuture<ActionResult> requestFocus(String player);
    CompletableFuture<ActionResult> requestUnfocus(String player);
    CompletableFuture<ActionResult> requestClearFocus();
}
