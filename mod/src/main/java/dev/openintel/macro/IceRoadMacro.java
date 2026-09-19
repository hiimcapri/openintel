package dev.openintel.macro;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;

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

    public IceRoadMacro(KeyBinding toggle) {
        super(toggle);
    }

    @Override
    protected void onEngage(MinecraftClient mc) {
        OIConfig cfg = OpenIntelClient.config();
        if (cfg.iceRoadSnapYaw) {
            mc.player.setYaw(Math.round(mc.player.getYaw() / 45f) * 45f);
        }
        if (cfg.iceRoadSnapPitch) {
            mc.player.setPitch(Math.round(mc.player.getPitch() / 45f) * 45f);
        }
    }

    @Override
    protected void onRelease(MinecraftClient mc) {
        mc.options.sprintKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
        if (jump) {
            jump = false;
            if (!mc.player.hasVehicle()) {
                mc.options.jumpKey.setPressed(false);
            }
        }
        mc.options.useKey.setPressed(false);
        waitingForFood = false;
        eating = null;
    }

    @Override
    protected void tickActive(MinecraftClient mc) {
        OIConfig cfg = OpenIntelClient.config();

        if (!jump) {
            if (cfg.iceRoadAutoEat) {
                if (eating != null
                        && (!mc.player.isUsingItem() || !eating.equals(mc.player.getActiveItem()))) {
                    eating = null;
                    mc.options.useKey.setPressed(false);
                }
                if (eating == null) {
                    ItemStack hand = mc.player.getMainHandStack();
                    if (tryEat(mc, hand)) {
                        eating = hand;
                        mc.options.useKey.setPressed(true);
                        return;
                    }
                }
            }

            if (cfg.iceRoadStopAtHunger
                    && mc.player.getHungerManager().getFoodLevel() <= 6) {
                waitingForFood = true;
                mc.options.forwardKey.setPressed(false);
                return;
            }
            waitingForFood = false;

            if (!mc.player.hasVehicle()) {
                mc.options.jumpKey.setPressed(true);
            }
            jump = true;
        } else {
            if (!mc.player.hasVehicle()) {
                mc.options.jumpKey.setPressed(false);
            }
            jump = false;
        }
        mc.options.sprintKey.setPressed(true);
        mc.options.forwardKey.setPressed(true);
    }

    private static boolean tryEat(MinecraftClient mc, ItemStack stack) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        return food != null && food.nutrition() > 0
                && mc.player.getHungerManager().getFoodLevel() + food.nutrition() <= 20;
    }

    @Override
    protected boolean losesInputCustody(MinecraftClient mc, int slot) {
        // CivModern parity: chat, inventory and scroll-wheel don't stop the
        // road — the macro keeps re-pressing movement inputs so the boat
        // keeps going while screens are open. Only the toggle key stops it.
        return false;
    }

    @Override
    protected String name() { return "ice road"; }
}
