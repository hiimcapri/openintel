package dev.openintel.api;

import java.util.List;

public sealed interface ApiEvent {
    enum Cause { UPDATE, EXPIRED, CLEAR }
    record Ready(String modVersion) implements ApiEvent { }
    record PlayersChanged(List<Snapshots.Player> previous, List<Snapshots.Player> current, Cause cause) implements ApiEvent {
        public PlayersChanged { previous = List.copyOf(previous); current = List.copyOf(current); }
    }
    record SnitchesChanged(List<Snapshots.Snitch> previous, List<Snapshots.Snitch> current, Cause cause) implements ApiEvent {
        public SnitchesChanged { previous = List.copyOf(previous); current = List.copyOf(current); }
    }
    record PingsChanged(List<Snapshots.Ping> previous, List<Snapshots.Ping> current, Cause cause) implements ApiEvent {
        public PingsChanged { previous = List.copyOf(previous); current = List.copyOf(current); }
    }
    record AllegiancesChanged(Snapshots.Allegiances previous, Snapshots.Allegiances current) implements ApiEvent { }
    record ConnectionChanged(Snapshots.Connection previous, Snapshots.Connection current) implements ApiEvent { }
    record SnitchArrived(Snapshots.SnitchArrival arrival) implements ApiEvent { }
    record NotificationReceived(Snapshots.Notification notification) implements ApiEvent { }
    record NotificationsCleared() implements ApiEvent { }
    record SettingsChanged(SettingsApi.View settings) implements ApiEvent { }
}
