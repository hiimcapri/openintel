package dev.openintel;

import dev.openintel.render.LogoHud;

public final class LogoHudTest {
    public static void main(String[] args) throws Exception {
        for (int[] pixels : new int[][]{{1, 1}, {32, 24}, {320, 200}, {1280, 720}, {1920, 1080},
                {1921, 1081}, {2560, 1440}, {3840, 2160}, {3440, 1440}, {720, 1280}}) {
            Float previousSize = null;
            for (int scale : new int[]{1, 2, 3, 4, 6, 8}) {
                int width = (int) Math.ceil(pixels[0] / (double) scale);
                int height = (int) Math.ceil(pixels[1] / (double) scale);
                var bounds = LogoHud.layout(width, height, pixels[0], pixels[1]);
                check(bounds.x() >= 0 && bounds.y() >= 0 && bounds.width() > 0 && bounds.height() > 0, "Logo stays visible in a valid viewport");
                check(bounds.x() + bounds.width() <= width + 0.001f && bounds.y() + bounds.height() <= height + 0.001f, "Logo stays within the bottom-right viewport bounds");
                float physicalWidth = bounds.width() * pixels[0] / width;
                float physicalHeight = bounds.height() * pixels[1] / height;
                check(Math.abs(physicalWidth - physicalHeight) < 0.001f, "Logo remains square in physical pixels");
                if (previousSize != null) check(Math.abs(previousSize - physicalWidth) < 0.001f, "GUI scale does not inflate or shrink the physical logo");
                previousSize = physicalWidth;
                if (Math.min(pixels[0], pixels[1]) >= 100) check(physicalWidth >= 28 && physicalWidth <= 72, "Watermark remains small but visible");
            }
        }
        check(LogoHud.layout(0, 0, 0, 0).width() == 0, "Minimized viewport is safe");
        try (var resource = LogoHudTest.class.getResourceAsStream("/assets/openintel/icon.png")) {
            var image = javax.imageio.ImageIO.read(resource);
            check(image.getWidth() == 512 && image.getHeight() == 512, "High-resolution logo asset is packaged");
            check((image.getRGB(0, 0) >>> 24) == 0, "Logo background is transparent");
            check((image.getRGB(256, 256) >>> 24) > 240, "Eye pupil is visible");
            boolean[][] visited = new boolean[512][512];
            int components = 0;
            for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
                int color = image.getRGB(x, y);
                if ((color >>> 24) > 0) check((color & 0xFFFFFF) == 0, "Every visible logo pixel is pure black");
                if ((color >>> 24) < 128 || visited[y][x]) continue;
                components++;
                var queue = new java.util.ArrayDeque<Integer>();
                queue.add(y * 512 + x);
                visited[y][x] = true;
                while (!queue.isEmpty()) {
                    int pixel = queue.removeFirst(), px = pixel % 512, py = pixel / 512;
                    for (int[] delta : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                        int nx = px + delta[0], ny = py + delta[1];
                        if (nx < 0 || ny < 0 || nx >= 512 || ny >= 512 || visited[ny][nx]
                                || (image.getRGB(nx, ny) >>> 24) < 128) continue;
                        visited[ny][nx] = true;
                        queue.add(ny * 512 + nx);
                    }
                }
            }
            check(components == 2, "Eye outline joins the O in one shape, with only the pupil separate");
        }
        try (var resource = LogoHudTest.class.getResourceAsStream("/assets/openintel/textures/gui/white.png")) {
            var white = javax.imageio.ImageIO.read(resource);
            check(white.getWidth() == 1 && white.getHeight() == 1 && white.getRGB(0, 0) == -1,
                    "Analytic logo uses an opaque neutral sampler texture");
        }
        try (var resource = LogoHudTest.class.getResourceAsStream("/fabric.mod.json")) {
            var metadata = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            check(metadata.get("icon").getAsString().equals("assets/openintel/icon.png"), "Fabric uses the new logo asset");
        }
        try (var resource = LogoHud.class.getResourceAsStream("LogoHud.class")) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(resource).accept(node, 0);
            var render = node.methods.stream().filter(method -> method.name.equals("render")).findFirst().orElseThrow();
            var guards = new java.util.HashSet<String>();
            for (var instruction : render.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field) guards.add(field.name);
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                    check(!call.name.equals("isAuthenticated") && !call.name.equals("isConnected"), "Logo has no relay dependency");
            }
            check(guards.containsAll(java.util.List.of("logoHudEnabled", "hudHidden", "currentScreen")), "Logo respects visibility and open-screen guards");
        }
        LogoHud.registerPipeline();
        System.out.println("OpenIntel logo layout and asset tests passed");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
