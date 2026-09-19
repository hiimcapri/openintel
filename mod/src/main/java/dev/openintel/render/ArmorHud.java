package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

public final class ArmorHud {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private ArmorHud() { }

    /** Footprint of the HUD element for the current layout (editor box + anchoring). */
    public static int boxW(OIConfig cfg) {
        return cfg.armorHudLayout == OIConfig.ArmorHudLayout.VERTICAL ? 40 : 84;
    }

    public static int boxH(OIConfig cfg) {
        return cfg.armorHudLayout == OIConfig.ArmorHudLayout.VERTICAL ? 80 : 26;
    }

    public static void render(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.armorHudEnabled || mc.player == null || mc.options.hudHidden) return;

        boolean vertical = cfg.armorHudLayout == OIConfig.ArmorHudLayout.VERTICAL;
        int x = resolveX(cfg.armorHudX, ctx.getScaledWindowWidth(), boxW(cfg));
        int y = cfg.armorHudY;
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = mc.player.getEquippedStack(SLOTS[i]);
            int itemX = vertical ? x : x + i * 22;
            int itemY = vertical ? y + i * 20 : y;
            if (stack.isEmpty()) continue;
            ctx.drawItem(stack, itemX, itemY);
            if (!stack.isDamageable()) continue;
            int remaining = stack.getMaxDamage() - stack.getDamage();
            int percent = Math.round(remaining * 100f / stack.getMaxDamage());
            switch (cfg.armorHudMode) {
                case PERCENT -> drawLabel(ctx, mc, vertical, itemX, itemY,
                        percent + "%", textColor(percent));
                case POINTS -> drawLabel(ctx, mc, vertical, itemX, itemY,
                        String.valueOf(remaining), textColor(percent));
                case BAR -> drawBar(ctx, stack, vertical, itemX, itemY);
            }
        }
    }

    private static void drawLabel(DrawContext ctx, MinecraftClient mc, boolean vertical,
                                  int itemX, int itemY, String text, int color) {
        if (vertical) {
            ctx.drawTextWithShadow(mc.textRenderer, text, itemX + 18, itemY + 4, color);
        } else {
            // "100%" is wider than the 16px icon; shrink it so neighbors don't touch.
            var pose = ctx.getMatrices();
            pose.pushMatrix();
            pose.translate(itemX + 8, itemY + 18);
            pose.scale(0.8f, 0.8f);
            ctx.drawCenteredTextWithShadow(mc.textRenderer, text, 0, 0, color);
            pose.popMatrix();
        }
    }

    private static void drawBar(DrawContext ctx, ItemStack stack, boolean vertical,
                                int itemX, int itemY) {
        // Draw for every damageable piece — a HUD readout wants a full bar on
        // undamaged items, unlike the vanilla overlay that hides it.
        int step = stack.getItemBarStep();
        int color = stack.getItemBarColor() | 0xFF000000;
        int fill = Math.round(step * 16f / 13f);
        if (vertical) {
            ctx.fill(itemX + 17, itemY, itemX + 21, itemY + 16, 0xFF000000);
            ctx.fill(itemX + 18, itemY + 16 - fill, itemX + 20, itemY + 16, color);
        } else {
            ctx.fill(itemX, itemY + 17, itemX + 16, itemY + 20, 0xFF000000);
            ctx.fill(itemX, itemY + 17, itemX + fill, itemY + 19, color);
        }
    }

    private static int textColor(int percent) {
        return percent > 50 ? 0xFF55FF55 : percent > 20 ? 0xFFFFFF55 : 0xFFFF5555;
    }

    private static int resolveX(int x, int screenWidth, int width) {
        return x >= 0 ? x : screenWidth + x - width;
    }
}
