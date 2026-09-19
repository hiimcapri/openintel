package dev.openintel.macro;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Ice road runner (CivModern parity): engages sprint + forward and
 * alternates jump every tick — the rhythm that makes ice roads fast.
 * Optionally snaps your view to a 45° cardinal on engage, auto-eats
 * whatever's in your main hand when there's hunger headroom, and can park
 * itself at <=6 hunger until you can eat again.
 */
public final class IceRoadMacro extends InputMacro {
    private boolean jump;
    private boolean waitingForFood;
    private ItemStack eating;

    public IceRoadMacro(KeyMapping toggle) {
        super(toggle);
    }

    @Override
    protected void onEngage(Minecraft mc) {
        OIConfig cfg = OpenIntelClient.config();
        if (cfg.iceRoadSnapYaw) {
            mc.player.setYRot(Math.round(mc.player.getYRot() / 45f) * 45f);
        }
        if (cfg.iceRoadSnapPitch) {
            mc.player.setXRot(Math.round(mc.player.getXRot() / 45f) * 45f);
        }
    }

    @Override
    protected void onRelease(Minecraft mc) {
        mc.options.keySprint.setDown(false);
        mc.options.keyUp.setDown(false);
        if (jump) {
            jump = false;
            if (!mc.player.isPassenger()) {
                mc.options.keyJump.setDown(false);
            }
        }
        mc.options.keyUse.setDown(false);
        waitingForFood = false;
        eating = null;
    }

    @Override
    protected void tickActive(Minecraft mc) {
        OIConfig cfg = OpenIntelClient.config();

        if (!jump) {
            if (cfg.iceRoadAutoEat) {
                if (eating != null
                        && (!mc.player.isUsingItem() || !eating.equals(mc.player.getActiveItem()))) {
                    eating = null;
                    mc.options.keyUse.setDown(false);
                }
                if (eating == null) {
                    ItemStack hand = mc.player.getMainHandItem();
                    if (tryEat(mc, hand)) {
                        eating = hand;
                        mc.options.keyUse.setDown(true);
                        return;
                    }
                }
            }

            if (cfg.iceRoadStopAtHunger
                    && mc.player.getFoodData().getFoodLevel() <= 6) {
                waitingForFood = true;
                mc.options.keyUp.setDown(false);
                return;
            }
            waitingForFood = false;

            if (!mc.player.isPassenger()) {
                mc.options.keyJump.setDown(true);
            }
            jump = true;
        } else {
            if (!mc.player.isPassenger()) {
                mc.options.keyJump.setDown(false);
            }
            jump = false;
        }
        mc.options.keySprint.setDown(true);
        mc.options.keyUp.setDown(true);
    }

    private static boolean tryEat(Minecraft mc, ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food != null && food.nutrition() > 0
                && mc.player.getFoodData().getFoodLevel() + food.nutrition() <= 20;
    }

    @Override
    protected boolean losesInputCustody(Minecraft mc, int slot) {
        // CivModern parity: chat, inventory and scroll-wheel don't stop the
        // road — the macro keeps re-pressing movement inputs so the boat
        // keeps going while screens are open. Only the toggle key stops it.
        return false;
    }

    @Override
    protected String name() { return "ice road"; }
}
