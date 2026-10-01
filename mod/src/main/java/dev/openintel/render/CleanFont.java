package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.GpuFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;
import net.minecraft.client.renderer.texture.AbstractTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Clean text for OpenIntel surfaces — the vanilla bitmap font stays
 * for chat/commands, but marker/HUD text goes through a bundled TTF
 * (Noto Sans Medium plus Noto symbol/math companions, covering the
 * ⚑/⚠/✖/✝ symbols our labels use).
 *
 * STBTruetype (bundled with LWJGL) rasterizes each needed codepoint
 * into a 1024px alpha atlas at 48px, registered as a texture; drawing
 * is per-glyph textured quads at ~9px logical height, tinted through
 * the blit color argument. Glyph metrics carry advance/xoff/
 * yoff so layout matches real typesetting, not a monospace grid.
 *
 * Lazily baked on first use (render thread); any failure flips
 * `failed` and every caller falls back to the vanilla renderer.
 */
public final class CleanFont {

    private static final Logger LOGGER = LoggerFactory.getLogger("OpenIntel/CleanFont");

    private static final Identifier ATLAS_ID = Identifier.fromNamespaceAndPath("openintel", "clean_font");
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("openintel", "pipeline/clean_font"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("openintel", "core/clean_font"))
            .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                    com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS, false))
            .build();
    private static final int ATLAS = 1024;
    private static final int MIP_LEVELS = 4;
    private static final int PAD = 1 << (MIP_LEVELS - 1);
    private static final float PIXEL_H = 48f;
    /** Logical text height — same ballpark as vanilla fontHeight (9). */
    private static final float HEIGHT = 9f;
    private static final float S = HEIGHT / PIXEL_H;

    /** Line pitch matching vanilla's fontHeight + 2 convention. */
    public static final float LINE_H = HEIGHT + 2f;

    private record Glyph(int x0, int y0, int w, int h,
                         float xoff, float yoff, float adv, float ascent) { }

    private record Face(STBTTFontinfo info, ByteBuffer data, float scale, float ascent) { }

    /** First face containing a codepoint wins: normal text from Medium, math/symbol fallbacks after. */
    private static final String[] FONT_RESOURCES = {
            "noto_sans_medium.ttf", "noto_sans_math.ttf",
            "noto_sans_symbols.ttf", "noto_sans_symbols2.ttf"};

    private static final Map<Integer, Glyph> glyphs = new HashMap<>();
    private static float spaceAdv;
    private static boolean init, failed;

    private CleanFont() { }

    public static void registerPipeline() {
        RenderPipelines.register(PIPELINE);
    }

    /** True when the config wants clean text and the atlas baked OK. */
    public static boolean active() {
        var cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.cleanFont) return false;
        if (!init) bake();
        return !failed;
    }

    /** True when every char is present in the atlas (after lazy bake). */
    public static boolean supports(String text) {
        if (!active()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!glyphs.containsKey((int) text.charAt(i))) return false;
        }
        return true;
    }

    public static float width(String text) {
        float pen = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            Glyph g = glyphs.get(codePoint);
            if (g == null) g = glyphs.get((int) '?');
            pen += (g != null ? g.adv : spaceAdv) * S;
        }
        return pen;
    }

    public static void draw(GuiGraphicsExtractor ctx, String text, float x, float y,
                            int argb, boolean shadow) {
        if (shadow) {
            int sc = UiFont.shadowColor((argb & 0xFF000000) | ((argb & 0xFCFCFC) >>> 2));
            float offset = UiFont.shadowOffset(0.8f);
            if (sc != 0) pass(ctx, text, x + offset, y + offset, sc);
        }
        pass(ctx, text, x, y, argb);
    }

    public static void drawCentered(GuiGraphicsExtractor ctx, String text, float cx,
                                    float y, int argb) {
        draw(ctx, text, cx - width(text) / 2f, y, argb, true);
    }

    private static void pass(GuiGraphicsExtractor ctx, String text, float x, float y, int argb) {
        if ((argb >>> 24) == 0 || text.isEmpty()) return;
        var pose = new Matrix3x2f(ctx.pose());
        ScreenRectangle bounds = textBounds(text, x, y, pose);
        if (bounds == null) return;
        ScreenRectangle scissor = ctx.scissorStack.peek();
        if (scissor != null) bounds = bounds.intersection(scissor);
        if (bounds == null) return;
        var texture = Minecraft.getInstance().getTextureManager().getTexture(ATLAS_ID);
        ctx.guiRenderState.addGuiElement(new TextRun(pose,
                TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()),
                text, x, y, argb, scissor, bounds));
    }

    private static ScreenRectangle textBounds(String text, float x, float y, Matrix3x2f pose) {
        float pen = x, left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            Glyph g = glyphs.get(codePoint);
            if (g == null) g = glyphs.get((int) '?');
            if (g != null && g.w > 0 && g.h > 0) {
                float gx = pen + g.xoff * S, gy = y + (g.ascent + g.yoff) * S;
                left = Math.min(left, gx);
                top = Math.min(top, gy);
                right = Math.max(right, gx + g.w * S);
                bottom = Math.max(bottom, gy + g.h * S);
            }
            pen += (g != null ? g.adv : spaceAdv) * S;
        }
        if (!Float.isFinite(left)) return null;
        int x0 = (int) Math.floor(left), y0 = (int) Math.floor(top);
        return new ScreenRectangle(x0, y0, (int) Math.ceil(right) - x0, (int) Math.ceil(bottom) - y0).transformMaxBounds(pose);
    }

    private record TextRun(Matrix3x2f pose, TextureSetup textureSetup, String text, float x, float y, int color,
                           ScreenRectangle scissorArea, ScreenRectangle bounds) implements GuiElementRenderState {
        @Override
        public RenderPipeline pipeline() { return PIPELINE; }

        @Override
        public void buildVertices(VertexConsumer vertices) {
            float pen = x;
            for (int i = 0; i < text.length();) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                Glyph g = glyphs.get(codePoint);
                if (g == null) g = glyphs.get((int) '?');
                if (g != null && g.w > 0 && g.h > 0) {
                    float x0 = pen + g.xoff * S, y0 = y + (g.ascent + g.yoff) * S;
                    float x1 = x0 + g.w * S, y1 = y0 + g.h * S;
                    float u0 = g.x0 / (float) ATLAS, u1 = (g.x0 + g.w) / (float) ATLAS;
                    float v0 = g.y0 / (float) ATLAS, v1 = (g.y0 + g.h) / (float) ATLAS;
                    vert(vertices, x0, y0, u0, v0);
                    vert(vertices, x0, y1, u0, v1);
                    vert(vertices, x1, y1, u1, v1);
                    vert(vertices, x1, y0, u1, v0);
                }
                pen += (g != null ? g.adv : spaceAdv) * S;
            }
        }

        private void vert(VertexConsumer vc, float x, float y, float u, float v) {
            org.joml.Vector2f p = pose.transformPosition(x, y, new org.joml.Vector2f());
            vc.addVertex(p.x, p.y, 0).setUv(u, v).setColor(color);
        }
    }

    // ------------------------------------------------------------ atlas ----

    /** Codepoints we bake: printable ASCII plus the symbols labels use. */
    private static int[] codepoints() {
        int[] extra = {0x2691, 0x26A0, 0x2716, 0x00D7, 0x2192, 0x2190, 0x2191,
                0x2193, 0x00B7, 0x2014, 0x2013, 0x2018, 0x2019, 0x201C, 0x201D,
                0x2026, 0x00B0, 0x25B2, 0x25BC, 0x25C4, 0x25BA, 0x25C6, 0x25CF,
                0x2605, 0x2726, 0x00A7, 0x2212, 0x00B1, 0x2264, 0x2265, 0x271D};
        int[] cps = new int[95 + extra.length];
        for (int i = 0; i < 95; i++) cps[i] = 32 + i;
        System.arraycopy(extra, 0, cps, 95, extra.length);
        return cps;
    }

    private static NativeImage createAtlas() throws java.io.IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var faces = new java.util.ArrayList<Face>();
            for (String name : FONT_RESOURCES) {
                byte[] fontBytes;
                try (InputStream in = CleanFont.class
                        .getResourceAsStream("/assets/openintel/font/" + name)) {
                    if (in == null) throw new IllegalStateException(name + " missing");
                    fontBytes = in.readAllBytes();
                }
                ByteBuffer ttf = ByteBuffer.allocateDirect(fontBytes.length)
                        .put(fontBytes).flip();
                STBTTFontinfo info = STBTTFontinfo.create();
                if (!STBTruetype.stbtt_InitFont(info, ttf)) {
                    throw new IllegalStateException("stbtt_InitFont failed: " + name);
                }
                float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, PIXEL_H);
                var ia = stack.ints(0); var ib = stack.ints(0);
                STBTruetype.stbtt_GetFontVMetrics(info, ia, ib, stack.ints(0));
                faces.add(new Face(info, ttf, scale, ia.get(0) * scale));
            }

            var ia = stack.ints(0); var ib = stack.ints(0);
            var ic = stack.ints(0); var id = stack.ints(0);
            STBTruetype.stbtt_GetCodepointHMetrics(faces.get(0).info(), ' ', ia, ib);
            spaceAdv = ia.get(0) * faces.get(0).scale();

            NativeImage img = new NativeImage(ATLAS, ATLAS, false);
            img.fillRect(0, 0, ATLAS, ATLAS, 0x00FFFFFF);
            int pen = 0, rowY = 0, rowH = 0;
            for (int cp : codepoints()) {
                Face face = null;
                for (Face f : faces) {
                    if (STBTruetype.stbtt_FindGlyphIndex(f.info(), cp) != 0) { face = f; break; }
                }
                if (face == null) continue;
                float scale = face.scale();
                STBTruetype.stbtt_GetCodepointBitmapBox(face.info(), cp, scale, scale,
                        ia, ib, ic, id);
                int x0 = ia.get(0), y0 = ib.get(0),
                        gw = ic.get(0) - x0, gh = id.get(0) - y0;
                STBTruetype.stbtt_GetCodepointHMetrics(face.info(), cp, ia, ib);
                float adv = ia.get(0) * scale;
                if (gw > 0 && gh > 0) {
                    int cellW = ((gw + 2 * PAD + PAD - 1) / PAD) * PAD;
                    int cellH = ((gh + 2 * PAD + PAD - 1) / PAD) * PAD;
                    if (pen + cellW > ATLAS) { pen = 0; rowY += rowH; rowH = 0; }
                    if (rowY + cellH > ATLAS) {
                        img.close();
                        throw new IllegalStateException("Font atlas full");   // atlas full
                    }
                    ByteBuffer glyph = ByteBuffer.allocateDirect(gw * gh);
                    STBTruetype.stbtt_MakeCodepointBitmap(face.info(), glyph, gw, gh, gw,
                            scale, scale, cp);
                    for (int gy = 0; gy < gh; gy++) {
                        for (int gx = 0; gx < gw; gx++) {
                            img.setPixel(pen + PAD + gx, rowY + PAD + gy,
                                    ((glyph.get(gy * gw + gx) & 0xFF) << 24) | 0xFFFFFF);
                        }
                    }
                    glyphs.put(cp, new Glyph(pen, rowY, cellW, cellH, x0 - PAD, y0 - PAD,
                            adv, face.ascent()));
                    pen += cellW;
                    rowH = Math.max(rowH, cellH);
                } else {
                    // Whitespace — no bitmap, just advance.
                    glyphs.put(cp, new Glyph(0, 0, 0, 0, 0, 0, adv, face.ascent()));
                }
            }
            return img;
        }
    }

    private static NativeImage downsample(NativeImage source) {
        NativeImage result = new NativeImage(source.getWidth() / 2, source.getHeight() / 2, false);
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int sx = x * 2, sy = y * 2;
                int alpha = ((source.getPixel(sx, sy) >>> 24)
                        + (source.getPixel(sx + 1, sy) >>> 24)
                        + (source.getPixel(sx, sy + 1) >>> 24)
                        + (source.getPixel(sx + 1, sy + 1) >>> 24) + 2) / 4;
                result.setPixel(x, y, (alpha << 24) | 0xFFFFFF);
            }
        }
        return result;
    }

    private static final class FontTexture extends AbstractTexture {
        private FontTexture(NativeImage image) {   // Default sampler minifies with NEAREST — far too crunchy
            // for 48px glyphs drawn at ~9px; use linear both ways.
            sampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, true);
            var device = RenderSystem.getDevice();
            texture = device.createTexture("openintel-clean-font",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, ATLAS, ATLAS, 1, MIP_LEVELS);
            try {
                textureView = device.createTextureView(texture);
                uploadMip(image, 0);
            } catch (RuntimeException | Error error) {
                close();
                throw error;
            }
        }

        private void uploadMip(NativeImage image, int level) {
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                    texture, image, level, 0, 0, 0);
            if (level + 1 < MIP_LEVELS) {
                try (NativeImage next = downsample(image)) {
                    uploadMip(next, level + 1);
                }
            }
        }
    }

    private static void bake() {
        init = true;
        try (NativeImage img = createAtlas()) {
            FontTexture tex = new FontTexture(img);
            try {
                Minecraft.getInstance().getTextureManager().register(ATLAS_ID, tex);
            } catch (RuntimeException | Error error) {
                tex.close();
                throw error;
            }
            LOGGER.info("clean font baked: {} glyphs, {} mip levels", glyphs.size(), MIP_LEVELS);
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("clean font unavailable, using vanilla text: {}", t.toString());
        }
    }
}
