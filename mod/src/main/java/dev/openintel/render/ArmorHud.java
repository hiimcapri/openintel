package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

public final class ArmorHud {
    private ArmorHud() { }

    public static void render(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.armorHudEnabled || mc.player == null || mc.options.hudHidden) return;

        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int x = resolveX(cfg.armorHudX, ctx.getScaledWindowWidth(), 80);
        int y = cfg.armorHudY;
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = mc.player.getEquippedStack(slots[i]);
            if (stack.isEmpty()) continue;
            int itemX = x + i * 20;
            ctx.drawItem(stack, itemX, y);
            if (stack.isDamageable()) {
                int remaining = stack.getMaxDamage() - stack.getDamage();
                int percent = Math.round(remaining * 100f / stack.getMaxDamage());
                int color = percent > 50 ? 0xFF55FF55 : percent > 20 ? 0xFFFFFF55 : 0xFFFF5555;
                ctx.drawCenteredTextWithShadow(mc.textRenderer, percent + "%", itemX + 8, y + 17, color);
            }
        }
    }

    private static int resolveX(int x, int screenWidth, int width) {
        return x >= 0 ? x : screenWidth + x - width;
    }
}
