package dev.openintel.api;

@FunctionalInterface
public interface Subscription extends AutoCloseable {
    void unsubscribe();
    @Override
    default void close() { unsubscribe(); }
}
