package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PotionHud {
    private PotionHud() { }

    public static void render(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.potionHudEnabled || mc.player == null || mc.options.hudHidden) return;

        List<StatusEffectInstance> effects = new ArrayList<>(mc.player.getStatusEffects());
        effects.sort(Comparator.comparing(e -> e.getEffectType().value().getName().getString()));
        int width = 140;
        int x = resolveX(cfg.potionHudX, ctx.getScaledWindowWidth(), width);
        int y = cfg.potionHudY;
        for (StatusEffectInstance effect : effects) {
            String name = effect.getEffectType().value().getName().getString();
            if (effect.getAmplifier() > 0) name += " " + roman(effect.getAmplifier() + 1);
            String duration = effect.isInfinite() ? "∞" : formatDuration(effect.getDuration());
            int color = 0xFF000000 | effect.getEffectType().value().getColor();
            ctx.fill(x, y + 2, x + 3, y + mc.textRenderer.fontHeight, color);
            ctx.drawTextWithShadow(mc.textRenderer, name, x + 6, y, 0xFFFFFFFF);
            ctx.drawTextWithShadow(mc.textRenderer, duration,
                    x + width - mc.textRenderer.getWidth(duration), y, 0xFFAAAAAA);
            y += mc.textRenderer.fontHeight + 3;
        }
    }

    private static String formatDuration(int ticks) {
        int seconds = Math.max(0, ticks / 20);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String roman(int value) {
        return switch (value) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }

    private static int resolveX(int x, int screenWidth, int width) {
        return x >= 0 ? x : screenWidth + x - width;
    }
}
