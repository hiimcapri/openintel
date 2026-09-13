package dev.openintel.gui;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.IntConsumer;

/**
 * HSV color picker: saturation/value square + hue bar + alpha bar + hex field.
 * The SV square is a 64x64 NativeImage regenerated whenever the hue changes —
 * cheaper and smoother-looking than approximating with quads.
 *
 * onPick fires on every change so callers can live-preview. autoLabel/autoAction
 * provide an optional "use default" escape (e.g. allegiance-colored snitches).
 */
public class ColorPickerScreen extends Screen {

    private static final int SV = 96;         // sv square side, gui-scaled px
    private static final int HUE_W = 12;
    private static final int ALPHA_H = 8;
    private static final int TEX = 64;        // backing texture resolution

    private final Screen parent;
    private final IntConsumer onPick;
    private final String autoLabel;           // null → no auto button
    private final Runnable autoAction;

    private float hue, sat, val;
    private int alpha;

    private int sx, sy, hx, ay;               // control origins
    private int dragging;                     // 0 none / 1 sv / 2 hue / 3 alpha

    private NativeImageBackedTexture svTex, hueTex;
    private Identifier svId, hueId;
    private TextFieldWidget hexField;

    public ColorPickerScreen(Screen parent, String title, int argb,
                             IntConsumer onPick) {
        this(parent, title, argb, onPick, null, null);
    }

    public ColorPickerScreen(Screen parent, String title, int argb,
                             IntConsumer onPick, String autoLabel, Runnable autoAction) {
        super(Text.literal(title));
        this.parent = parent;
        this.onPick = onPick;
        this.autoLabel = autoLabel;
        this.autoAction = autoAction;
        setColor(argb);
    }

    private void setColor(int argb) {
        alpha = (argb >>> 24) & 0xFF;
        float[] hsv = java.awt.Color.RGBtoHSB((argb >> 16) & 0xFF,
                (argb >> 8) & 0xFF, argb & 0xFF, null);
        hue = hsv[0]; sat = hsv[1]; val = hsv[2];
    }

    private int argb() {
        int rgb = java.awt.Color.HSBtoRGB(hue, sat, val) & 0x00FFFFFF;
        return (alpha << 24) | rgb;
    }

    @Override
    protected void init() {
        sx = (width - (SV + 10 + HUE_W)) / 2;
        sy = (height - (SV + 12 + ALPHA_H + 12 + 20 + 24)) / 2;
        hx = sx + SV + 10;
        ay = sy + SV + 12;

        var tm = MinecraftClient.getInstance().getTextureManager();
        svId = Identifier.of("openintel", "colorpicker_sv");
        hueId = Identifier.of("openintel", "colorpicker_hue");
        svTex = new NativeImageBackedTexture(() -> "sv", TEX, TEX, false);
        hueTex = new NativeImageBackedTexture(() -> "hue", HUE_W, TEX, false);
        tm.registerTexture(svId, svTex);
        tm.registerTexture(hueId, hueTex);
        rebuildHue();
        rebuildSv();

        hexField = new TextFieldWidget(client.textRenderer, sx, ay + ALPHA_H + 12,
                SV + 10 + HUE_W, 20, Text.literal("hex"));
        hexField.setMaxLength(10);
        hexField.setText(hexText());
        hexField.setChangedListener(s -> {
            try {
                String t = s.replace("#", "").trim();
                if (t.length() == 6) {
                    int rgb = Integer.parseInt(t, 16);
                    setColor((alpha << 24) | rgb);
                    rebuildSv();
                } else if (t.length() == 8) {
                    setColor((int) Long.parseLong(t, 16));
                    rebuildSv();
                }
            } catch (NumberFormatException ignored) { }
        });
        addDrawableChild(hexField);

        int bw = (SV + 10 + HUE_W - 8) / 2;
        int by = ay + ALPHA_H + 12 + 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(sx, by, autoLabel != null ? bw : SV + 10 + HUE_W, 20).build());
        if (autoLabel != null) {
            addDrawableChild(ButtonWidget.builder(Text.literal(autoLabel), b -> {
                        if (autoAction != null) autoAction.run();
                        close();
                    }).dimensions(sx + bw + 8, by, bw, 20).build());
        }
    }

    private String hexText() {
        return String.format("#%08X", argb());
    }

    /** Rebuild the SV gradient for the current hue. */
    private void rebuildSv() {
        NativeImage img = svTex.getImage();
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                float s = x / (float) (TEX - 1);
                float v = 1f - y / (float) (TEX - 1);
                img.setColorArgb(x, y, 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, s, v) & 0xFFFFFF));
            }
        }
        svTex.upload();
    }

    /** One-time rainbow strip, hue 1 (top) → 0 (bottom). */
    private void rebuildHue() {
        NativeImage img = hueTex.getImage();
        for (int y = 0; y < TEX; y++) {
            int rgb = java.awt.Color.HSBtoRGB(1f - y / (float) (TEX - 1), 1f, 1f);
            for (int x = 0; x < HUE_W; x++) img.setColorArgb(x, y, 0xFF000000 | (rgb & 0xFFFFFF));
        }
        hueTex.upload();
    }

    private void pick() {
        onPick.accept(argb());
    }

    private void applyMouse(double mx, double my) {
        switch (dragging) {
            case 1 -> {
                sat = (float) Math.min(1, Math.max(0, (mx - sx) / SV));
                val = (float) Math.min(1, Math.max(0, 1 - (my - sy) / SV));
            }
            case 2 -> {
                hue = (float) Math.min(1, Math.max(0, 1 - (my - sy) / SV));
                rebuildSv();
            }
            case 3 -> alpha = (int) Math.min(255, Math.max(0, Math.round((mx - sx) / SV * 255)));
            default -> { return; }
        }
        pick();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (mx >= sx && mx < sx + SV && my >= sy && my < sy + SV) dragging = 1;
        else if (mx >= hx && mx < hx + HUE_W && my >= sy && my < sy + SV) dragging = 2;
        else if (mx >= sx && mx < sx + SV && my >= ay && my < ay + ALPHA_H) dragging = 3;
        if (dragging != 0) { applyMouse(mx, my); return true; }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (dragging != 0) { applyMouse(click.x(), click.y()); return true; }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = 0;
        return super.mouseReleased(click);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // In-world screens must not call renderBackground — its blur pass can
        // only run once per frame. renderInGameBackground darkens without it.
        renderInGameBackground(ctx);
        ctx.fill(sx - 8, sy - 24, hx + HUE_W + 8, ay + ALPHA_H + 12 + 24 + 24, 0xC0101015);
        ctx.drawCenteredTextWithShadow(client.textRenderer, title, width / 2, sy - 16, 0xFFFFFFFF);

        // SV square + hue bar.
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, svId, sx, sy, 0, 0, SV, SV, SV, SV);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, hueId, hx, sy, 0, 0, HUE_W, SV, HUE_W, SV);

        // Selection markers: ring on the SV square, bars on the strips.
        int px = sx + Math.round(sat * SV);
        int py = sy + Math.round((1f - val) * SV);
        ctx.drawStrokedRectangle(px - 3, py - 3, 6, 6, 0xFFFFFFFF);
        int hy = sy + Math.round((1f - hue) * SV);
        ctx.fill(hx - 2, hy, hx + HUE_W + 2, hy + 1, 0xFFFFFFFF);

        // Alpha bar: checkerboard, then a vertical alpha gradient over it.
        for (int i = 0; i < SV; i += 4) {
            for (int j = 0; j < ALPHA_H; j += 4) {
                ctx.fill(sx + i, ay + j, sx + i + 4, ay + j + 4,
                        ((i + j) / 4) % 2 == 0 ? 0xFF555555 : 0xFF333333);
            }
        }
        int rgb = java.awt.Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF;
        for (int i = 0; i < SV; i++) {
            int a = Math.min(255, i * 255 / SV);
            ctx.fill(sx + i, ay, sx + i + 1, ay + ALPHA_H, (a << 24) | rgb);
        }
        int axx = sx + Math.round(alpha / 255f * SV);
        ctx.fill(axx, ay - 2, axx + 1, ay + ALPHA_H + 2, 0xFFFFFFFF);

        // Preview swatch next to the hex field.
        int pw = 24;
        ctx.fill(hx + HUE_W - pw, ay + ALPHA_H + 14, hx + HUE_W, ay + ALPHA_H + 14 + 16, argb());
        ctx.drawStrokedRectangle(hx + HUE_W - pw, ay + ALPHA_H + 14, pw, 16, 0xFFFFFFFF);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void removed() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        tm.destroyTexture(svId);
        tm.destroyTexture(hueId);
    }
}
