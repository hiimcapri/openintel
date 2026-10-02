package dev.openintel.mixin;

import dev.openintel.junk.JunkRejector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
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
@Mixin(ClientPacketListener.class)
public abstract class PickupAnimMixin {

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"), cancellable = true)
    private void openintel$skipJunkPickupAnim(ClientboundTakeItemEntityPacket packet,
                                            CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || packet.getPlayerId() != client.player.getId()) return;
        Entity entity = client.level.getEntity(packet.getItemId());
        if (entity instanceof ItemEntity item && JunkRejector.isJunk(item.getItem()))
            ci.cancel();
    }
}
