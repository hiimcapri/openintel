package dev.openintel.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.resources.Identifier;

public final class UiFont {
    public static final Identifier FONT_ID = Identifier.fromNamespaceAndPath("openintel", "ui");
    private static final String ATLAS_PREFIX = FONT_ID + "/";
    public static final FontDescription.Resource SOURCE = new FontDescription.Resource(FONT_ID);
    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("openintel", "pipeline/ui_font"))
            .withVertexBinding(0, RenderPipelines.GUI_TEXT.getVertexFormatBinding(0))
            .withPrimitiveTopology(RenderPipelines.GUI_TEXT.getPrimitiveTopology())
            .withFragmentShader(Identifier.fromNamespaceAndPath("openintel", "core/clean_font"))
            .withShaderDefine("FONT_INTENSITY")
            .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                    com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS, false))
            .build();

    private static volatile boolean enabled;
    private static final ThreadLocal<Integer> HUD_DEPTH = new ThreadLocal<>();

    private UiFont() { }

    public static void initialize(boolean value) {
        RenderPipelines.register(PIPELINE);
        setEnabled(value);
    }

    public static boolean setEnabled(boolean value) {
        boolean changed = enabled != value;
        enabled = value;
        return changed;
    }

    public static void withHudFont(Runnable action) {
        Integer previous = HUD_DEPTH.get();
        HUD_DEPTH.set(previous == null ? 1 : previous + 1);
        try {
            action.run();
        } finally {
            if (previous == null) HUD_DEPTH.remove();
            else HUD_DEPTH.set(previous);
        }
    }

    public static void withMapFont(String modId, Runnable action) {
        if ("openintel".equals(modId)) {
            withHudFont(action);
            return;
        }
        Integer previous = HUD_DEPTH.get();
        HUD_DEPTH.remove();
        try {
            action.run();
        } finally {
            if (previous != null) HUD_DEPTH.set(previous);
        }
    }

    public static FormattedCharSequence capture(FormattedCharSequence text) {
        if (!enabled || HUD_DEPTH.get() == null) return text;
        return visitor -> {
            net.minecraft.network.chat.Style[] cached = new net.minecraft.network.chat.Style[2];
            return text.accept((index, style, codePoint) -> {
                if (style != cached[0]) {
                    cached[0] = style;
                    cached[1] = FontDescription.DEFAULT.equals(style.getFont()) ? style.withFont(SOURCE).withoutShadow() : style;
                }
                return visitor.accept(index, cached[1], codePoint);
            });
        };
    }

    public static FontDescription select(FontDescription source) {
        return enabled && HUD_DEPTH.get() != null && FontDescription.DEFAULT.equals(source) ? SOURCE : source;
    }

    public static Font chatInputRenderer(Font.Provider delegate) {
        return new Font(new Font.Provider() {
            @Override
            public net.minecraft.client.gui.GlyphSource glyphs(FontDescription source) {
                return delegate.glyphs(enabled && FontDescription.DEFAULT.equals(source) ? SOURCE : source);
            }

            @Override
            public EffectGlyph effect() {
                return delegate.effect();
            }
        });
    }

    public static int width(Font renderer, String text) {
        return enabled ? renderer.width(net.minecraft.network.chat.Component.literal(text)
                .withStyle(style -> style.withFont(SOURCE))) : renderer.width(text);
    }

    public static int shadowColor(int argb) {
        return 0;
    }

    public static float shadowOffset(float offset) {
        return 0f;
    }

    public static boolean isAtlas(String label) {
        return label != null && label.startsWith(ATLAS_PREFIX);
    }

    public static boolean smoothAtlas(String label, GpuFormat format) {
        return isAtlas(label) && format == GpuFormat.R8_UNORM;
    }
}
