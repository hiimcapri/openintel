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
        UiFont.withHudFont(() -> renderHud(ctx));
    }

    private static void renderHud(GuiGraphicsExtractor ctx) {
        OIConfig cfg = OpenIntelClient.config();
        Minecraft mc = Minecraft.getInstance();
        if (!cfg.potionHudEnabled || mc.player == null || mc.gui.hud.isHidden()) return;

        List<MobEffectInstance> effects = new ArrayList<>(mc.player.getActiveEffects());
        effects.sort(Comparator.comparing(e -> e.getEffect().value().getDisplayName().getString()));
        var size = size(mc);
        var frame = HudLayouts.place(HudLayouts.Element.POTIONS, cfg, size,
                ctx.guiWidth(), ctx.guiHeight());
        if (frame.scale() <= 0) return;
        try (var ignored = HudLayouts.apply(ctx, frame)) {
            int width = size.width();
            int y = 0;
            for (MobEffectInstance effect : effects) {
                String name = effect.getEffect().value().getDisplayName().getString();
                if (effect.getAmplifier() > 0) name += " " + roman(effect.getAmplifier() + 1);
                String duration = effect.isInfiniteDuration() ? "∞" : formatDuration(effect.getDuration());
                int durationWidth = mc.font.width(duration);
                name = mc.font.plainSubstrByWidth(name, Math.max(0, width - durationWidth - 12));
                int color = 0xFF000000 | effect.getEffect().value().getColor();
                ctx.fill(0, y + 2, 3, y + mc.font.lineHeight, color);
                ctx.text(mc.font, name, 6, y, 0xFFFFFFFF);
                ctx.text(mc.font, duration, width - durationWidth, y, 0xFFAAAAAA);
                y += mc.font.lineHeight + 3;
            }
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

    public static dev.openintel.api.hud.HudSize size(Minecraft mc) {
        int rows = mc.player == null ? 0 : mc.player.getActiveEffects().size();
        int height = rows == 0 ? 48 : rows * (mc.font.lineHeight + 3);
        return new dev.openintel.api.hud.HudSize(140, Math.clamp(height, 1, 32768));
    }
}
