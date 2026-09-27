package dev.openintel.render;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
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
 * (DejaVu Sans, covers the ⚑/⚠/✖ symbols our labels use).
 *
 * STBTruetype (bundled with LWJGL) rasterizes each needed codepoint
 * into a 1024px alpha atlas at 48px, registered as a texture; drawing
 * is per-glyph textured quads at ~9px logical height, tinted through
 * the drawTexture color argument. Glyph metrics carry advance/xoff/
 * yoff so layout matches real typesetting, not a monospace grid.
 *
 * Lazily baked on first use (render thread); any failure flips
 * `failed` and every caller falls back to the vanilla renderer.
 */
public final class CleanFont {

    private static final Logger LOGGER = LoggerFactory.getLogger("OpenIntel/CleanFont");

    private static final Identifier ATLAS_ID = Identifier.of("openintel", "clean_font");
    private static final int ATLAS = 1024;
    private static final int PAD = 2;
    private static final float PIXEL_H = 48f;
    /** Logical text height — same ballpark as vanilla fontHeight (9). */
    private static final float HEIGHT = 9f;
    private static final float S = HEIGHT / PIXEL_H;

    /** Line pitch matching vanilla's fontHeight + 2 convention. */
    public static final float LINE_H = HEIGHT + 2f;

    private record Glyph(int x0, int y0, int w, int h,
                         float xoff, float yoff, float adv) { }

    private static final Map<Integer, Glyph> glyphs = new HashMap<>();
    private static float ascentPx;
    private static float spaceAdv;
    private static boolean init, failed;

    private CleanFont() { }

    /** True when the config wants clean text and the atlas baked OK. */
    public static boolean active() {
        var cfg = OpenIntelClient.config();
        if (cfg == null || !cfg.cleanFont) return false;
        if (!init) bake();
        return !failed;
    }

    public static float width(String text) {
        float pen = 0;
        for (int i = 0; i < text.length(); i++) {
            Glyph g = glyphs.get((int) text.charAt(i));
            pen += (g != null ? g.adv : spaceAdv) * S;
        }
        return pen;
    }

    public static void draw(DrawContext ctx, String text, float x, float y,
                            int argb, boolean shadow) {
        if (shadow) {
            int sc = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >>> 2);
            pass(ctx, text, x + 0.8f, y + 0.8f, sc);
        }
        pass(ctx, text, x, y, argb);
    }

    public static void drawCentered(DrawContext ctx, String text, float cx,
                                    float y, int argb) {
        draw(ctx, text, cx - width(text) / 2f, y, argb, true);
    }

    private static void pass(DrawContext ctx, String text, float x, float y, int argb) {
        float pen = x;
        for (int i = 0; i < text.length(); i++) {
            Glyph g = glyphs.get((int) text.charAt(i));
            if (g == null) g = glyphs.get((int) '?');
            if (g != null && g.w > 0 && g.h > 0) {
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ATLAS_ID,
                        Math.round(pen + g.xoff * S),
                        Math.round(y + (ascentPx + g.yoff) * S),
                        g.x0, g.y0, g.w, g.h, ATLAS, ATLAS, argb);
            }
            pen += (g != null ? g.adv : spaceAdv) * S;
        }
    }

    // ------------------------------------------------------------ atlas ----

    /** Codepoints we bake: printable ASCII plus the symbols labels use. */
    private static int[] codepoints() {
        int[] extra = {0x2691, 0x26A0, 0x2716, 0x00D7, 0x2192, 0x2190, 0x2191,
                0x2193, 0x00B7, 0x2014, 0x2013, 0x2018, 0x2019, 0x201C, 0x201D,
                0x2026, 0x00B0, 0x25B2, 0x25BC, 0x25C4, 0x25BA, 0x25C6, 0x25CF,
                0x2605, 0x2726, 0x00A7, 0x2212, 0x00B1, 0x2264, 0x2265};
        int[] cps = new int[95 + extra.length];
        for (int i = 0; i < 95; i++) cps[i] = 32 + i;
        System.arraycopy(extra, 0, cps, 95, extra.length);
        return cps;
    }

    private static void bake() {
        init = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            byte[] fontBytes;
            try (InputStream in = CleanFont.class
                    .getResourceAsStream("/assets/openintel/font/clean.ttf")) {
                if (in == null) throw new IllegalStateException("clean.ttf missing");
                fontBytes = in.readAllBytes();
            }
            ByteBuffer ttf = ByteBuffer.allocateDirect(fontBytes.length)
                    .put(fontBytes).flip();
            STBTTFontinfo info = STBTTFontinfo.create();
            if (!STBTruetype.stbtt_InitFont(info, ttf)) {
                throw new IllegalStateException("stbtt_InitFont failed");
            }
            float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, PIXEL_H);

            var ia = stack.ints(0); var ib = stack.ints(0);
            var ic = stack.ints(0); var id = stack.ints(0);
            STBTruetype.stbtt_GetFontVMetrics(info, ia, ib, ic);
            ascentPx = ia.get(0) * scale;
            STBTruetype.stbtt_GetCodepointHMetrics(info, ' ', ia, ib);
            spaceAdv = ia.get(0);

            NativeImage img = new NativeImage(ATLAS, ATLAS, false);
            int pen = 0, rowY = 0, rowH = 0;
            for (int cp : codepoints()) {
                if (STBTruetype.stbtt_FindGlyphIndex(info, cp) == 0) continue;
                STBTruetype.stbtt_GetCodepointBitmapBox(info, cp, scale, scale,
                        ia, ib, ic, id);
                int x0 = ia.get(0), y0 = ib.get(0),
                        gw = ic.get(0) - x0, gh = id.get(0) - y0;
                STBTruetype.stbtt_GetCodepointHMetrics(info, cp, ia, ib);
                float adv = ia.get(0);
                if (gw > 0 && gh > 0) {
                    if (pen + gw + PAD > ATLAS) { pen = 0; rowY += rowH + PAD; rowH = 0; }
                    if (rowY + gh + PAD > ATLAS) break;   // atlas full
                    ByteBuffer glyph = ByteBuffer.allocateDirect(gw * gh);
                    STBTruetype.stbtt_MakeCodepointBitmap(info, glyph, gw, gh, gw,
                            scale, scale, cp);
                    for (int gy = 0; gy < gh; gy++) {
                        for (int gx = 0; gx < gw; gx++) {
                            img.setColorArgb(pen + gx, rowY + gy,
                                    ((glyph.get(gy * gw + gx) & 0xFF) << 24) | 0xFFFFFF);
                        }
                    }
                    glyphs.put(cp, new Glyph(pen, rowY, gw, gh, x0, y0, adv));
                    pen += gw + PAD;
                    rowH = Math.max(rowH, gh);
                } else {
                    // Whitespace — no bitmap, just advance.
                    glyphs.put(cp, new Glyph(0, 0, 0, 0, 0, 0, adv));
                }
            }

            NativeImageBackedTexture tex = new NativeImageBackedTexture(
                    () -> "openintel-clean-font", img);
            tex.upload();
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(ATLAS_ID, tex);
            LOGGER.info("clean font baked: {} glyphs", glyphs.size());
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("clean font unavailable, using vanilla text: {}", t.toString());
        }
    }
}
