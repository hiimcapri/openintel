package dev.openintel.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.font.EffectGlyph;
import net.minecraft.client.font.GlyphProvider;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Identifier;

public final class UiFont {
    public static final Identifier FONT_ID = Identifier.of("openintel", "ui");
    private static final String ATLAS_PREFIX = FONT_ID + "/";
    public static final StyleSpriteSource.Font SOURCE = new StyleSpriteSource.Font(FONT_ID);
    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
            .withLocation(Identifier.of("openintel", "pipeline/ui_font"))
            .withVertexFormat(RenderPipelines.GUI_TEXT.getVertexFormat(), RenderPipelines.GUI_TEXT.getVertexFormatMode())
            .withFragmentShader(Identifier.of("openintel", "core/clean_font"))
            .withShaderDefine("FONT_INTENSITY")
            .withDepthWrite(false)
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

    public static OrderedText capture(OrderedText text) {
        if (!enabled || HUD_DEPTH.get() == null) return text;
        return visitor -> {
            net.minecraft.text.Style[] cached = new net.minecraft.text.Style[2];
            return text.accept((index, style, codePoint) -> {
                if (style != cached[0]) {
                    cached[0] = style;
                    cached[1] = StyleSpriteSource.DEFAULT.equals(style.getFont()) ? style.withFont(SOURCE).withoutShadow() : style;
                }
                return visitor.accept(index, cached[1], codePoint);
            });
        };
    }

    public static StyleSpriteSource select(StyleSpriteSource source) {
        return enabled && HUD_DEPTH.get() != null && StyleSpriteSource.DEFAULT.equals(source) ? SOURCE : source;
    }

    public static TextRenderer chatInputRenderer(TextRenderer.GlyphsProvider delegate) {
        return new TextRenderer(new TextRenderer.GlyphsProvider() {
            @Override
            public GlyphProvider getGlyphs(StyleSpriteSource source) {
                return delegate.getGlyphs(enabled && StyleSpriteSource.DEFAULT.equals(source) ? SOURCE : source);
            }

            @Override
            public EffectGlyph getRectangleGlyph() {
                return delegate.getRectangleGlyph();
            }
        });
    }

    public static int width(TextRenderer renderer, String text) {
        return enabled ? renderer.getWidth(net.minecraft.text.Text.literal(text)
                .styled(style -> style.withFont(SOURCE))) : renderer.getWidth(text);
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

    public static boolean smoothAtlas(String label, TextureFormat format) {
        return isAtlas(label) && format == TextureFormat.RED8;
    }
}
