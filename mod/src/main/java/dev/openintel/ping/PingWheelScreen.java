package dev.openintel.ping;

import dev.openintel.mixin.DrawContextAccessor;
import dev.openintel.render.ColoredQuadsElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.lwjgl.glfw.GLFW;

/**
 * Radial ping wheel: hold the bound key, flick the mouse at a wedge,
 * release to drop the ping at whatever you're looking at.
 *
 * Two interaction modes:
 *  - hold mode (key held when opened): releasing the bound key commits the
 *    highlighted wedge; releasing with the mouse in the dead zone cancels.
 *  - click mode (opened via /oi ping, key not held): clicking a wedge
 *    commits, clicking outside the ring or ESC cancels.
 *
 * Wedges are annular sectors emitted through ColoredQuadsElement — the same
 * vector path as the radar dial, so edges are gradients, not pixels.
 */
public class PingWheelScreen extends Screen {

    private record Slot(String label, int argb) { }

    private static final Slot[] SLOTS = {
            new Slot("Fight", 0xFFFF5555),
            new Slot("Enemy", 0xFFE040FB),
            new Slot("Loot", 0xFFFFAA00),
            new Slot("Rally", 0xFF55FF55),
            new Slot("Watch", 0xFF55FFFF),
            new Slot("Danger", 0xFFFF7744),
    };

    private static final double TAU = Math.PI * 2;
    private static final float INNER = 26f;
    private static final float OUTER = 80f;
    private static final float GAP_DEG = 3f;

    private final KeyBinding key;
    private final boolean holdMode;
    private int hovered = -1;

    public PingWheelScreen(KeyBinding key, boolean holdMode) {
        super(Text.literal("Ping"));
        this.key = key;
        this.holdMode = holdMode;
    }

    /** Physical held-state of the bound key, queried straight from GLFW. */
    public static boolean physicallyHeld(KeyBinding key) {
        var window = MinecraftClient.getInstance().getWindow();
        InputUtil.Key bound = InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey());
        if (bound.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window.getHandle(), bound.getCode())
                    == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(window, bound.getCode());
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    protected void init() {
        // no widgets — the wheel is self-drawn
    }

    @Override
    public void tick() {
        // Poll physical state: covers releases that don't reach the screen
        // (e.g. OS-level mouse capture edge cases).
        if (holdMode && !physicallyHeld(key)) {
            commit(hovered);
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.getKeycode() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
        if (holdMode && key.matchesKey(input)) {
            commit(hovered);
            return true;
        }
        return super.keyReleased(input);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (holdMode && key.matchesMouse(click)) {
            commit(hovered);
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (!holdMode) {
            float cx = width / 2f, cy = height / 2f;
            double dx = click.x() - cx, dy = click.y() - cy;
            double len = Math.hypot(dx, dy);
            if (len > OUTER + 6) {          // clicked outside the ring: cancel
                close();
                return true;
            }
            if (hovered >= 0) {             // clicked a wedge: send it
                commit(hovered);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    /** Send the selected ping (or just close, when slot < 0). */
    private void commit(int slot) {
        if (client == null) { close(); return; }
        if (slot >= 0 && client.player != null && client.world != null) {
            Slot s = SLOTS[slot];
            HitResult hit = client.player.raycast(96, 1.0f, false);
            Vec3d pos = hit.getType() == HitResult.Type.BLOCK
                    ? Vec3d.ofCenter(((BlockHitResult) hit).getBlockPos())
                    : client.player.getEyePos()
                        .add(client.player.getRotationVector().multiply(24));
            String dim = client.world.getRegistryKey().getValue().toString();
            PingManager.send(s.label, s.argb, pos.x, pos.y, pos.z, dim);
        }
        close();
    }

    // ------------------------------------------------------------ render ---

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // No dim/blur — the wheel floats over the live world.
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        float cx = width / 2f, cy = height / 2f;
        hovered = slotAt(mouseX - cx, mouseY - cy);

        Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices()).translate(cx, cy);
        ((DrawContextAccessor) ctx).openintel$state().addSimpleElement(new ColoredQuadsElement(
                pose, vc -> paint(vc, pose),
                new ScreenRect(-(int) (OUTER + 4), -(int) (OUTER + 4),
                        (int) (OUTER * 2 + 8), (int) (OUTER * 2 + 8))
                        .transformEachVertex(pose)));

        // Wedge labels, then the center readout.
        for (int i = 0; i < SLOTS.length; i++) {
            double mid = Math.toRadians(-90 + i * 360.0 / SLOTS.length + 180.0 / SLOTS.length);
            float r = (INNER + OUTER) / 2f;
            int lx = Math.round(cx + (float) Math.cos(mid) * r);
            int ly = Math.round(cy + (float) Math.sin(mid) * r);
            int color = i == hovered ? 0xFFFFFFFF
                    : (SLOTS[i].argb & 0x00FFFFFF) | 0xDD000000;
            ctx.drawCenteredTextWithShadow(textRenderer, SLOTS[i].label, lx, ly - 4, color);
        }

        String center = hovered >= 0 ? SLOTS[hovered].label : "—";
        ctx.drawCenteredTextWithShadow(textRenderer, center,
                Math.round(cx), Math.round(cy) - 4, 0xFFDDDDDD);
    }

    /** Which wedge the mouse points at; -1 inside the dead zone. */
    private static int slotAt(float dx, float dy) {
        double len = Math.hypot(dx, dy);
        if (len < INNER) return -1;
        double deg = Math.toDegrees(Math.atan2(dy, dx));   // -90 = up
        double rel = ((deg + 90) % 360 + 360) % 360;
        return Math.min(SLOTS.length - 1, (int) (rel / (360.0 / SLOTS.length)));
    }

    // ------------------------------------------------------ geometry ----

    private void paint(VertexConsumer vc, Matrix3x2fc pose) {
        int seg = SLOTS.length;
        double span = TAU / seg;
        double gap = Math.toRadians(GAP_DEG);

        // Center hub.
        fan(vc, pose, INNER - 6, 24, 0x66101018, 0x66101018);
        ringBand(vc, pose, INNER - 7, INNER - 6, 24, 0x99FFFFFF, 0x00FFFFFF);

        for (int i = 0; i < seg; i++) {
            double a0 = -Math.PI / 2 + i * span + gap;
            double a1 = -Math.PI / 2 + (i + 1) * span - gap;
            boolean hot = i == hovered;
            int base = SLOTS[i].argb;
            int fill = scaleAlpha(base, hot ? 0.62f : 0.22f);
            int fillIn = scaleAlpha(base, hot ? 0.34f : 0.10f);
            int edge = scaleAlpha(base, hot ? 1.0f : 0.55f);

            arcBand(vc, pose, a0, a1, INNER, OUTER, 10, fillIn, fill);
            // Bright outer lip.
            arcBand(vc, pose, a0, a1, OUTER - 1.2, OUTER + 0.6, 10, edge, edge);
            // Soft outer glow.
            arcBand(vc, pose, a0, a1, OUTER + 0.6, OUTER + 2.2, 10,
                    edge, edge & 0x00FFFFFF);
        }
    }

    /** Annular sector between two angles — ringBand limited to an arc. */
    private static void arcBand(VertexConsumer vc, Matrix3x2fc pose,
                                double a0, double a1, double r0, double r1,
                                int steps, int cIn, int cOut) {
        for (int i = 0; i < steps; i++) {
            double t0 = a0 + (a1 - a0) * i / steps;
            double t1 = a0 + (a1 - a0) * (i + 1) / steps;
            float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0);
            float c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
            quad(vc, pose,
                    c0 * (float) r0, s0 * (float) r0, cIn,
                    c1 * (float) r0, s1 * (float) r0, cIn,
                    c1 * (float) r1, s1 * (float) r1, cOut,
                    c0 * (float) r1, s0 * (float) r1, cOut);
        }
    }

    private static void fan(VertexConsumer vc, Matrix3x2fc pose, double r,
                            int seg, int cIn, int cOut) {
        for (int i = 0; i < seg; i++) {
            double a0 = i * TAU / seg, a1 = (i + 1) * TAU / seg;
            quad(vc, pose,
                    0, 0, cIn,
                    (float) (Math.cos(a1) * r), (float) (Math.sin(a1) * r), cOut,
                    (float) (Math.cos(a0) * r), (float) (Math.sin(a0) * r), cOut,
                    (float) (Math.cos(a0) * r), (float) (Math.sin(a0) * r), cOut);
        }
    }

    private static void ringBand(VertexConsumer vc, Matrix3x2fc pose,
                                 double r0, double r1, int seg, int cIn, int cOut) {
        arcBand(vc, pose, 0, TAU, r0, r1, seg, cIn, cOut);
    }

    private static void quad(VertexConsumer vc, Matrix3x2fc pose,
                             float x0, float y0, int c0,
                             float x1, float y1, int c1,
                             float x2, float y2, int c2,
                             float x3, float y3, int c3) {
        vc.vertex(pose, x0, y0).color(c0);
        vc.vertex(pose, x1, y1).color(c1);
        vc.vertex(pose, x2, y2).color(c2);
        vc.vertex(pose, x3, y3).color(c3);
    }

    private static int scaleAlpha(int argb, float f) {
        int a = Math.min(255, Math.max(0, Math.round(((argb >>> 24) & 0xFF) * f)));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
