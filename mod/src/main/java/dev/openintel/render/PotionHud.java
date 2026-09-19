package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PotionHud {
    private PotionHud() { }

    public static void render(GuiGraphicsExtractor ctx) {
        OIConfig cfg = OpenIntelClient.config();
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.potionHudEnabled || mc.player == null || mc.options.hideGui) return;

        List<MobEffectInstance> effects = new ArrayList<>(mc.player.getActiveEffects());
        effects.sort(Comparator.comparing(e -> e.getEffect().value().getDisplayName().getString()));
        int width = 140;
        int x = resolveX(cfg.potionHudX, ctx.guiWidth(), width);
        int y = cfg.potionHudY;
        for (MobEffectInstance effect : effects) {
            String name = effect.getEffect().value().getDisplayName().getString();
            if (effect.getAmplifier() > 0) name += " " + roman(effect.getAmplifier() + 1);
            String duration = effect.isInfiniteDuration() ? "∞" : formatDuration(effect.getDuration());
            int color = 0xFF000000 | effect.getEffect().value().getColor();
            ctx.fill(x, y + 2, x + 3, y + mc.font.lineHeight, color);
            ctx.text(mc.font, name, x + 6, y, 0xFFFFFFFF, true);
            ctx.text(mc.font, duration,
                    x + width - mc.font.width(duration), y, 0xFFAAAAAA);
            y += mc.font.lineHeight + 3;
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
