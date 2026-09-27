package dev.openintel.tracker;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;

public final class LocalRelayStore<K, V> {
    private final Map<K, V> local = new ConcurrentHashMap<>();
    private final Map<K, V> relay = new ConcurrentHashMap<>();
    private final BinaryOperator<V> preferred;

    public LocalRelayStore(BinaryOperator<V> preferred) {
        this.preferred = preferred;
    }

    public void putLocal(K key, V value) {
        local.put(key, value);
    }

    public void putRelay(K key, V value) {
        relay.put(key, value);
    }

    public boolean containsKey(K key) {
        return local.containsKey(key) || relay.containsKey(key);
    }

    public Collection<V> values() {
        var visible = new HashMap<>(relay);
        local.forEach((key, value) -> visible.merge(key, value, (remote, own) -> preferred.apply(own, remote)));
        return List.copyOf(visible.values());
    }

    public boolean removeIf(Predicate<V> expired) {
        boolean changed = local.values().removeIf(expired);
        return relay.values().removeIf(expired) || changed;
    }

    public void clearRelay() {
        relay.clear();
    }

    public void clear() {
        local.clear();
        relay.clear();
    }
}
