package dev.openintel.api;

import java.util.List;
import java.util.Optional;

public interface PlayersApi {
    List<Snapshots.Player> list();
    default Optional<Snapshots.Player> find(String name) {
        return list().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }
}
