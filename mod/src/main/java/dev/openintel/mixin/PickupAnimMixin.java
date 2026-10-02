package dev.openintel.mixin;

import dev.openintel.junk.JunkRejector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kills the fly-to-player animation and pickup sound for junk items the
 * local player collects. The server still delivers the stack (pickup is
 * server-authoritative), but JunkRejector throws it back out next tick —
 * net effect is the item vanishing and reappearing dropped beside you,
 * like bouncing off a full inventory.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class PickupAnimMixin {

    @Inject(method = "onItemPickupAnimation", at = @At("HEAD"), cancellable = true)
    private void openintel$skipJunkPickupAnim(ItemPickupAnimationS2CPacket packet,
                                            CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || packet.getCollectorEntityId() != client.player.getId()) return;
        Entity entity = client.world.getEntityById(packet.getEntityId());
        if (entity instanceof ItemEntity item && JunkRejector.isJunk(item.getStack()))
            ci.cancel();
    }
}
