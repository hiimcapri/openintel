package dev.openintel;

import dev.openintel.render.HudLayout;

public final class HudLayoutTest {
    public static void main(String[] args) throws Exception {
        check(HudLayout.snap(-20, 166, 480, 10) == 0, "Radar can snap to the actual top edge, not a 34px editor margin");
        check(HudLayout.snap(9999, 166, 480, 10) == 314, "Bottom edge has no editor toolbar exclusion");
        check(HudLayout.snap(155, 166, 480, 10) == 157, "Center snap uses available travel");
        check(HudLayout.snap(10, 500, 200, 10) == 0, "Oversized editor element cannot produce negative bounds");
        check(HudLayout.snap(10, 90, 100, 10) == 10, "Right edge remains reachable when available travel is smaller than snap distance");
        var topLeft = HudLayout.capture(0, 0, 166, 166, 640, 360);
        var right = HudLayout.capture(-1, 20, 140, 48, 640, 360);
        var center = HudLayout.capture(270, 155, 100, 50, 640, 360);
        var bottomRight = HudLayout.capture(540, 310, 100, 50, 640, 360);
        check(HudLayout.place(topLeft, 166, 166, 640, 360, 640, 360).y() == 0, "Runtime and editor share the zero top edge");
        check(HudLayout.place(right, 140, 48, 640, 360, 640, 360).right() == 639, "Legacy right-edge anchor preserved");
        var grown = HudLayout.place(center, 100, 50, 640, 360, 1280, 720);
        check(grown.width() == 200 && grown.height() == 100 && grown.x() == 540 && grown.y() == 310,
                "Elements and their placement scale together with the window");
        var docked = HudLayout.place(bottomRight, 100, 50, 640, 360, 800, 400);
        check(docked.right() == 800 && docked.bottom() == 400, "Edge docking survives aspect ratio changes");
        var centered = HudLayout.place(center, 100, 50, 640, 360, 800, 400);
        check(Math.abs(centered.x() + centered.width() / 2.0 - 400) <= 0.5, "Centered anchor survives aspect ratio changes");
        for (int[] viewport : new int[][]{{1920, 1080}, {960, 540}, {640, 360}, {480, 270}, {320, 240},
                {180, 120}, {80, 40}, {1, 1}, {3440, 1440}, {400, 900}, {0, 0}}) {
            for (var anchor : new HudLayout.Anchor[]{topLeft, right, center, bottomRight}) {
                for (int[] size : new int[][]{{166, 166}, {132, 58}, {600, 96}, {84, 80}, {140, 300}, {32768, 32768}}) {
                    var frame = HudLayout.place(anchor, size[0], size[1], 640, 360, viewport[0], viewport[1]);
                    inside(frame, viewport[0], viewport[1]);
                    check(Float.isFinite(frame.scale()) && frame.scale() >= 0, "Scale is finite at every viewport size");
                    if (viewport[0] > 0 && viewport[1] > 0) {
                        check(size[0] * frame.scale() <= viewport[0] + 0.001f
                                && size[1] * frame.scale() <= viewport[1] + 0.001f, "Complete element fits without stretching");
                    }
                }
            }
        }
        var random = new java.util.Random(42);
        for (int i = 0; i < 2500; i++) {
            int width = random.nextInt(4000) + 1, height = random.nextInt(2200) + 1;
            int elementWidth = random.nextInt(2000) + 1, elementHeight = random.nextInt(1500) + 1;
            var anchor = HudLayout.capture(random.nextInt(1600) - 10, random.nextInt(900) - 10,
                    elementWidth, elementHeight, 1280, 720);
            inside(HudLayout.place(anchor, elementWidth, elementHeight, 1280, 720, width, height), width, height);
        }
        var initial = HudLayout.place(center, 100, 50, 640, 360, 640, 360);
        for (int i = 0; i < 50; i++) {
            HudLayout.place(center, 100, 50, 640, 360, 180, 120);
            check(HudLayout.place(center, 100, 50, 640, 360, 640, 360).equals(initial), "Resize cycles do not drift saved positions");
        }
        var external = HudLayout.pixels(900, 900, 120, 20, 50, 100);
        inside(external, 50, 100);
        check(external.width() == 50 && external.height() <= 9, "Oversized API element fits while preserving aspect ratio");
        var json = new com.google.gson.Gson().toJson(bottomRight);
        check(bottomRight.equals(new com.google.gson.Gson().fromJson(json, HudLayout.Anchor.class)), "Responsive anchors persist exactly");
        check(HudLayout.ellipsize("abcdef", 4, String::length).equals("abc\u2026"), "Long labels fit without running off-screen");
        check(HudLayout.ellipsize("abcdef", 0, String::length).isEmpty(), "No room for text is safe");
        String supplementary = "\uD801\uDC00\uD801\uDC01\uD801\uDC02";
        String trimmed = HudLayout.ellipsize(supplementary, 2, s -> s.codePointCount(0, s.length()));
        check(trimmed.equals("\uD801\uDC00\u2026"), "Trimming preserves complete Unicode codepoints");
        verifyEdgeOverflow();
        verifyEditorIntegration();
        System.out.println("Responsive HUD layout tests passed");
    }

    private static void verifyEdgeOverflow() throws Exception {
        var entry = Class.forName("dev.openintel.render.MarkerHud$EdgeEntry");
        var constructor = entry.getDeclaredConstructor(String.class, int.class, double.class, int.class);
        constructor.setAccessible(true);
        var entries = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) entries.add(constructor.newInstance("player" + i, -1, (double) i, 200));
        var rows = dev.openintel.render.MarkerHud.class.getDeclaredMethod("edgeRows", java.util.List.class, int.class);
        rows.setAccessible(true);
        var visible = (java.util.List<?>) rows.invoke(null, entries, 2);
        var label = entry.getDeclaredMethod("label");
        label.setAccessible(true);
        check(visible.size() == 2 && label.invoke(visible.get(0)).equals("player0")
                && label.invoke(visible.get(1)).equals("+4 more"), "Edge stacks retain nearest contacts and count overflow");
    }

    private static void verifyEditorIntegration() throws Exception {
        boolean[] shared = new boolean[3];
        try (var input = dev.openintel.gui.HudEditorScreen.class.getResourceAsStream("HudEditorScreen.class")) {
            new org.objectweb.asm.ClassReader(input).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                   String signature, String[] exceptions) {
                    check(!name.equals("clampY") && !name.equals("snappedY"), "Editor no longer has a separate toolbar-offset clamp");
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            if (owner.equals("dev/openintel/render/HudLayouts") && method.equals("bounds")) shared[0] = true;
                            if (owner.equals("dev/openintel/render/HudLayouts") && method.equals("move")) shared[1] = true;
                            if (owner.equals("dev/openintel/render/HudLayout") && method.equals("pixels")) shared[2] = true;
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        check(shared[0] && shared[1] && shared[2], "Editor uses shared builtin and external HUD bounds");
    }

    private static void inside(HudLayout.Frame frame, int width, int height) {
        check(frame.x() >= 0 && frame.y() >= 0 && frame.right() <= width && frame.bottom() <= height,
                "HUD frame fits viewport " + width + "x" + height + ": " + frame);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
