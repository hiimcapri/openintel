package dev.openintel.api;

import java.util.List;
import java.util.Optional;

public interface SnitchesApi {
    List<Snapshots.Snitch> list();
    default Optional<Snapshots.Snitch> find(String player) {
        return list().stream().filter(s -> s.player().equalsIgnoreCase(player)).findFirst();
    }
}
