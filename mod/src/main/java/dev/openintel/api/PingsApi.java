package dev.openintel.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface PingsApi {
    List<Snapshots.Ping> list();
    default Optional<Snapshots.Ping> find(String id) {
        return list().stream().filter(p -> p.id().equals(id)).findFirst();
    }
    CompletableFuture<ActionResult> send(String label, int color, double x, double y, double z, String dimension);
}
