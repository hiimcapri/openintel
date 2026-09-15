package dev.openintel;

import dev.openintel.api.ActionResult;
import dev.openintel.api.ApiEvent;
import dev.openintel.api.OpenIntelApi;
import dev.openintel.api.Snapshots;
import dev.openintel.api.Subscription;
import dev.openintel.api.internal.ApiBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ApiContractTest {
    public static void main(String[] args) throws Exception {
        check(OpenIntelApi.API_VERSION == 1, "API version");
        check(!OpenIntelApi.isReady(), "No client initialized in contract test");
        check(OpenIntelApi.settings().snapshot().isEmpty(), "Safe pre-init settings read");
        check(Snapshots.Allegiance.NEUTRAL.argb() == 0xFFAAAAAA, "Public neutral ARGB color");
        check(OpenIntelApi.players().list().isEmpty(), "Safe pre-init player reads");
        check(OpenIntelApi.snitches().list().isEmpty(), "Safe pre-init snitch reads");
        check(OpenIntelApi.pings().list().isEmpty(), "Safe pre-init ping reads");
        check(!OpenIntelApi.relay().snapshot().authenticated(), "No authenticated relay before initialization");
        check(OpenIntelApi.notifications().show("Example", 0xFFFFFFFF).join().status()
                == ActionResult.Status.NOT_READY, "Pre-init actions return NOT_READY");
        Map<String, Snapshots.Allegiance> source = new HashMap<>();
        source.put("example", Snapshots.Allegiance.ALLY);
        Snapshots.Allegiances snapshot = new Snapshots.Allegiances(source);
        source.clear();
        check(snapshot.of("EXAMPLE") == Snapshots.Allegiance.ALLY, "Immutable allegiance snapshot and case-insensitive lookup");
        check(snapshot.of(null) == Snapshots.Allegiance.NEUTRAL, "Null lookup is neutral");
        try {
            snapshot.entries().put("other", Snapshots.Allegiance.ENEMY);
            throw new AssertionError("Mutable allegiance snapshot");
        } catch (UnsupportedOperationException expected) { }
        var mutable = new ArrayList<Snapshots.Player>();
        var player = new Snapshots.Player("Example", new Snapshots.Position(1, 2, 3, "minecraft:the_end"),
                42, "Reporter", Snapshots.Allegiance.ALLY);
        mutable.add(player);
        var event = new ApiEvent.PlayersChanged(List.of(), mutable, ApiEvent.Cause.UPDATE);
        mutable.clear();
        check(event.current().size() == 1, "Event lists are defensive copies");
        try {
            event.current().clear();
            throw new AssertionError("Mutable event payload");
        } catch (UnsupportedOperationException expected) { }
        check(new ActionResult(ActionResult.Status.SUBMITTED, "queued").accepted(), "SUBMITTED accepted locally");
        check(!new ActionResult(ActionResult.Status.NOT_AUTHENTICATED, "denied").accepted(), "Rejection is not accepted");
        AtomicInteger delivered = new AtomicInteger();
        Method publish = ApiBridge.class.getDeclaredMethod("publish", ApiEvent.class);
        publish.setAccessible(true);
        Subscription listener = OpenIntelApi.events().listen(ApiEvent.PlayersChanged.class, e -> delivered.incrementAndGet());
        publish.invoke(null, event);
        check(delivered.get() == 1, "Typed subscription delivered");
        publish.invoke(null, new ApiEvent.NotificationsCleared());
        check(delivered.get() == 1, "Unrelated event not delivered");
        listener.close();
        listener.close();
        publish.invoke(null, event);
        check(delivered.get() == 1, "Unsubscribe is idempotent and stops delivery");
        Subscription failing = OpenIntelApi.events().listen(ApiEvent.PlayersChanged.class, e -> {
            throw new IllegalStateException("Intentional listener isolation test");
        });
        Subscription healthy = OpenIntelApi.events().listen(ApiEvent.PlayersChanged.class, e -> delivered.incrementAndGet());
        publish.invoke(null, event);
        check(delivered.get() == 2, "Throwing listener cannot stop other listeners");
        publish.invoke(null, event);
        check(delivered.get() == 3, "Failed subscriber stays disabled while healthy subscriber continues");
        failing.close();
        healthy.close();
        AtomicInteger recursiveCount = new AtomicInteger();
        Subscription recursive = OpenIntelApi.events().listen(ApiEvent.NotificationsCleared.class, e -> {
            recursiveCount.incrementAndGet();
            try {
                publish.invoke(null, new ApiEvent.NotificationsCleared());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        });
        publish.invoke(null, new ApiEvent.NotificationsCleared());
        check(recursiveCount.get() == 256, "Recursive events bounded without hanging client thread");
        recursive.close();
        var hud = OpenIntelApi.hud();
        var id = net.minecraft.util.Identifier.of("contract_test", "example");
        var size = new dev.openintel.api.hud.HudSize(120, 20);
        var position = new dev.openintel.api.hud.HudPosition(100, 100);
        var handle = hud.register(id, "Contract example", size, position, (context, bounds, delta) -> { });
        check(hud.element(id).isPresent(), "Custom HUD registers before initialization");
        check(hud.getPosition(id).equals(position), "Default position readable before initialization");
        try {
            hud.register(id, "Duplicate", size, position, (context, bounds, delta) -> { });
            throw new AssertionError("Duplicate HUD accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            hud.register(net.minecraft.util.Identifier.of("openintel", "reserved"), "Reserved", size, position,
                    (context, bounds, delta) -> { });
            throw new AssertionError("Reserved namespace accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            hud.elements().clear();
            throw new AssertionError("Mutable HUD descriptor list");
        } catch (UnsupportedOperationException expected) { }
        check(position.clamp(size, 160, 90).equals(new dev.openintel.api.hud.HudPosition(40, 70)), "Viewport clamp");
        check(position.clamp(size, 50, 10).equals(new dev.openintel.api.hud.HudPosition(0, 0)), "Oversized element clamp");
        try {
            hud.setPosition(id, position);
            throw new AssertionError("HUD mutation accepted without client thread");
        } catch (IllegalStateException expected) { }
        handle.close();
        var replacement = hud.register(id, "Replacement", size, position, (context, bounds, delta) -> { });
        handle.close();
        check(hud.element(id).isPresent(), "Old handle cannot unregister a replacement");
        replacement.close();
        check(hud.element(id).isEmpty(), "Unregistered HUD removed");
        System.out.println("OpenIntel API contract tests passed");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
