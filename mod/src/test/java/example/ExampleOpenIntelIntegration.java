package example;

import dev.openintel.api.ApiEvent;
import dev.openintel.api.OpenIntelApi;
import dev.openintel.api.OpenIntelIntegration;
import dev.openintel.api.Subscription;
import dev.openintel.api.hud.HudPosition;
import dev.openintel.api.hud.HudRegistration;
import dev.openintel.api.hud.HudRenderer;
import dev.openintel.api.hud.HudSize;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

public final class ExampleOpenIntelIntegration implements OpenIntelIntegration, AutoCloseable {
    private Subscription playerUpdates;
    private HudRegistration contacts;
    private int contactCount;

    @Override
    public void onOpenIntelInitialize() {
        contactCount = OpenIntelApi.players().list().size();
        playerUpdates = OpenIntelApi.events().listen(ApiEvent.PlayersChanged.class,
                event -> contactCount = event.current().size());
        HudRenderer renderer = (context, size, tickDelta) -> {
            context.fill(0, 0, size.width(), size.height(), 0x990C1420);
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer,
                    "Contacts: " + contactCount, 4, 4, 0xFFFFFFFF);
        };
        contacts = OpenIntelApi.hud().register(Identifier.of("examplemod", "contacts"),
                "Example contacts", new HudSize(120, 20), new HudPosition(12, 180), renderer, renderer);
    }

    public void regroup() {
        OpenIntelApi.pings().send("Regroup", 0xFF55FFFF, 1200, 64, -500, "minecraft:overworld")
                .thenAccept(result -> {
                    if (!result.accepted()) System.out.println(result.status() + ": " + result.message());
                });
    }

    @Override
    public void close() {
        if (playerUpdates != null) playerUpdates.close();
        if (contacts != null) contacts.close();
    }
}
