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
        UiFont.withHudFont(() -> renderHud(ctx));
    }

    private static void renderHud(DrawContext ctx) {
        OIConfig cfg = OpenIntelClient.config();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.potionHudEnabled || mc.player == null || mc.options.hudHidden) return;

        List<StatusEffectInstance> effects = new ArrayList<>(mc.player.getStatusEffects());
        effects.sort(Comparator.comparing(e -> e.getEffectType().value().getName().getString()));
        var size = size(mc);
        var frame = HudLayouts.place(HudLayouts.Element.POTIONS, cfg, size,
                ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
        if (frame.scale() <= 0) return;
        try (var ignored = HudLayouts.apply(ctx, frame)) {
            int width = size.width();
            int y = 0;
            for (StatusEffectInstance effect : effects) {
                String name = effect.getEffectType().value().getName().getString();
                if (effect.getAmplifier() > 0) name += " " + roman(effect.getAmplifier() + 1);
                String duration = effect.isInfinite() ? "∞" : formatDuration(effect.getDuration());
                int durationWidth = mc.textRenderer.getWidth(duration);
                name = mc.textRenderer.trimToWidth(name, Math.max(0, width - durationWidth - 12));
                int color = 0xFF000000 | effect.getEffectType().value().getColor();
                ctx.fill(0, y + 2, 3, y + mc.textRenderer.fontHeight, color);
                ctx.drawTextWithShadow(mc.textRenderer, name, 6, y, 0xFFFFFFFF);
                ctx.drawTextWithShadow(mc.textRenderer, duration, width - durationWidth, y, 0xFFAAAAAA);
                y += mc.textRenderer.fontHeight + 3;
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

    public static dev.openintel.api.hud.HudSize size(MinecraftClient mc) {
        int rows = mc.player == null ? 0 : mc.player.getStatusEffects().size();
        int height = rows == 0 ? 48 : rows * (mc.textRenderer.fontHeight + 3);
        return new dev.openintel.api.hud.HudSize(140, Math.clamp(height, 1, 32768));
    }
}
