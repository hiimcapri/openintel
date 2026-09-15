package dev.openintel.api;

import java.util.function.Consumer;

public interface EventsApi {
    <E extends ApiEvent> Subscription listen(Class<E> eventType, Consumer<? super E> listener);
}
