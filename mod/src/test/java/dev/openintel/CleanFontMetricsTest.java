package dev.openintel;

import dev.openintel.render.CleanFont;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

public final class CleanFontMetricsTest {
    public static void main(String[] args) throws Exception {
        var createAtlas = CleanFont.class.getDeclaredMethod("createAtlas");
        createAtlas.setAccessible(true);
        var faces = new java.util.ArrayList<STBTTFontinfo>();
        var buffers = new java.util.ArrayList<ByteBuffer>();
        for (String name : new String[]{"noto_sans_medium.ttf", "noto_sans_math.ttf",
                "noto_sans_symbols.ttf", "noto_sans_symbols2.ttf"}) {
            try (var input = CleanFont.class.getResourceAsStream("/assets/openintel/font/" + name)) {
                check(input != null, "Bundled font face packaged: " + name);
                byte[] bytes = input.readAllBytes();
                var data = ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
                var info = STBTTFontinfo.create();
                check(STBTruetype.stbtt_InitFont(info, data), "Bundled font loads: " + name);
                faces.add(info);
                buffers.add(data);
            }
        }
        try (var atlas = (AutoCloseable) createAtlas.invoke(null);
             var stack = MemoryStack.stackPush()) {
            float scale = STBTruetype.stbtt_ScaleForPixelHeight(faces.get(0), 9f);
            var advance = stack.ints(0);
            var bearing = stack.ints(0);
            for (String text : new String[]{"", " ", "N", "S", "E", "W", "NSEW",
                    "Relic -120, 340 | 120m", "ExamplePlayer", "A  B", "\u2691 \u26a0 \u2716", "\u0378", "\uD83D\uDCE1", "A\uD83D\uDCE1B"}) {
                float expected = 0;
                for (int cp : text.codePoints().toArray()) {
                    STBTTFontinfo face = null;
                    for (var f : faces) if (STBTruetype.stbtt_FindGlyphIndex(f, cp) != 0) { face = f; break; }
                    if (face == null) { face = faces.get(0); cp = '?'; }
                    STBTruetype.stbtt_GetCodepointHMetrics(face, cp, advance, bearing);
                    expected += advance.get(0) * STBTruetype.stbtt_ScaleForPixelHeight(face, 9f);
                }
                float actual = CleanFont.width(text);
                check(Math.abs(actual - expected) < 0.001f,
                        "Logical width for '" + text + "': expected " + expected + ", got " + actual);
                float centeredStart = 100f - actual / 2f;
                check(Math.abs(centeredStart + expected / 2f - 100f) < 0.001f,
                        "Centered advance bounds for '" + text + "'");
            }
            check(CleanFont.width(" ") > 1f && CleanFont.width(" ") < 3f, "Space is logical pixels, not font units");
            check(CleanFont.width("W") > CleanFont.width("i"), "Proportional advances preserved");
            check(CleanFont.width("\u0378") == CleanFont.width("?"), "Fallback measurement matches drawing");
            verifyAtlas((net.minecraft.client.texture.NativeImage) atlas);
            verifyMipmaps((net.minecraft.client.texture.NativeImage) atlas, 0);
            verifyFractionalPlacement();
            verifyBatchedGlyphs();
            verifyMarkerStability();
            verifyRadarTextScale();
            verifyLocalLabelScale();
            verifyFontPipeline();
        }
        System.out.println("Clean font metrics tests passed");
    }

    private static void verifyAtlas(net.minecraft.client.texture.NativeImage atlas) throws Exception {
        for (int y = 0; y < atlas.getHeight(); y++) {
            for (int x = 0; x < atlas.getWidth(); x++) {
                check((atlas.getColorArgb(x, y) & 0xFFFFFF) == 0xFFFFFF,
                        "Atlas RGB must be initialized white, including transparent gutters");
            }
        }
        check(atlas.getColorArgb(atlas.getWidth() - 1, atlas.getHeight() - 1) == 0x00FFFFFF,
                "Unused atlas space is transparent");
    }

    private static void verifyMipmaps(net.minecraft.client.texture.NativeImage image, int level) throws Exception {
        var glyphsField = CleanFont.class.getDeclaredField("glyphs");
        glyphsField.setAccessible(true);
        var glyphs = (java.util.Map<?, ?>) glyphsField.get(null);
        check(glyphs.size() == 126 && glyphs.containsKey(0x271D), "Bundled glyphs include the event-feed death marker");
        for (Object glyph : glyphs.values()) {
            int x = glyphInt(glyph, "x0") >> level, y = glyphInt(glyph, "y0") >> level;
            int w = glyphInt(glyph, "w") >> level, h = glyphInt(glyph, "h") >> level;
            for (int dx = 0; dx < w; dx++) {
                check((image.getColorArgb(x + dx, y) >>> 24) == 0, "Top gutter at mip " + level);
                check((image.getColorArgb(x + dx, y + h - 1) >>> 24) == 0, "Bottom gutter at mip " + level);
            }
            for (int dy = 0; dy < h; dy++) {
                check((image.getColorArgb(x, y + dy) >>> 24) == 0, "Left gutter at mip " + level);
                check((image.getColorArgb(x + w - 1, y + dy) >>> 24) == 0, "Right gutter at mip " + level);
            }
        }
        var levelsField = CleanFont.class.getDeclaredField("MIP_LEVELS");
        levelsField.setAccessible(true);
        if (level + 1 == levelsField.getInt(null)) return;
        var downsample = CleanFont.class.getDeclaredMethod("downsample", net.minecraft.client.texture.NativeImage.class);
        downsample.setAccessible(true);
        try (var next = (net.minecraft.client.texture.NativeImage) downsample.invoke(null, image)) {
            check(next.getWidth() * 2 == image.getWidth() && next.getHeight() * 2 == image.getHeight(),
                    "Mip dimensions halve");
            for (int y = 0; y < next.getHeight(); y++) {
                for (int x = 0; x < next.getWidth(); x++) {
                    int sum = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            sum += image.getColorArgb(x * 2 + dx, y * 2 + dy) >>> 24;
                        }
                    }
                    int color = next.getColorArgb(x, y);
                    check(Math.abs((color >>> 24) - sum / 4f) <= 0.5f, "Mip preserves average coverage");
                    check((color & 0xFFFFFF) == 0xFFFFFF, "Mip RGB stays white at transparent edges");
                }
            }
            verifyMipmaps(next, level + 1);
        }
    }

    private static int glyphInt(Object glyph, String name) throws Exception {
        var accessor = glyph.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return (int) accessor.invoke(glyph);
    }

    private static void verifyFractionalPlacement() throws Exception {
        try (var input = CleanFont.class.getResourceAsStream("CleanFont.class")) {
            new org.objectweb.asm.ClassReader(input).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                   String signature, String[] exceptions) {
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            check(!(owner.equals("java/lang/Math") && method.equals("round")),
                                    "Font geometry must not independently round glyph positions or dimensions");
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
    }

    private static void verifyBatchedGlyphs() throws Exception {
        var type = Class.forName("dev.openintel.render.CleanFont$TextRun");
        var constructor = type.getDeclaredConstructor(org.joml.Matrix3x2f.class,
                net.minecraft.client.texture.TextureSetup.class, String.class, float.class, float.class,
                int.class, net.minecraft.client.gui.ScreenRect.class, net.minecraft.client.gui.ScreenRect.class);
        constructor.setAccessible(true);
        var boundsMethod = CleanFont.class.getDeclaredMethod("textBounds", String.class, float.class, float.class, org.joml.Matrix3x2f.class);
        boundsMethod.setAccessible(true);
        var pose = new org.joml.Matrix3x2f().translate(10.25f, 20.5f).scale(1.2f, 1.2f);
        var bounds = (net.minecraft.client.gui.ScreenRect) boundsMethod.invoke(null, "A A", 0.25f, 0.5f, pose);
        var state = (net.minecraft.client.gui.render.state.SimpleGuiElementRenderState) constructor.newInstance(
                pose, net.minecraft.client.texture.TextureSetup.empty(), "A A", 0.25f, 0.5f, -1, null, bounds);
        var vertices = new java.util.ArrayList<org.joml.Vector2f>();
        int[] uvCount = {0};
        var consumer = (net.minecraft.client.render.VertexConsumer) java.lang.reflect.Proxy.newProxyInstance(
                CleanFontMetricsTest.class.getClassLoader(), new Class<?>[]{net.minecraft.client.render.VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("vertex")) {
                        vertices.add(((org.joml.Matrix3x2fc) args[0]).transformPosition((float) args[1], (float) args[2], new org.joml.Vector2f()));
                    } else if (method.getName().equals("texture")) {
                        check((float) args[0] >= 0 && (float) args[0] <= 1 && (float) args[1] >= 0 && (float) args[1] <= 1,
                                "Batched glyph UVs stay inside the atlas");
                        uvCount[0]++;
                    }
                    return method.getReturnType() == net.minecraft.client.render.VertexConsumer.class ? proxy : null;
                });
        state.setupVertices(consumer);
        check(vertices.size() == 8 && uvCount[0] == 8, "One batch emits one quad per visible glyph, not for spaces");
        check(Math.abs(vertices.get(4).x - vertices.get(0).x - CleanFont.width("A ") * 1.2f) < 0.001f,
                "Batched glyph placement retains proportional advances and spaces");
        for (var vertex : vertices) check(vertex.x >= bounds.getLeft() && vertex.x <= bounds.getRight()
                && vertex.y >= bounds.getTop() && vertex.y <= bounds.getBottom(), "Run bounds contain all transformed glyph vertices");
        check(boundsMethod.invoke(null, "   ", 0f, 0f, pose) == null, "Blank text queues no render element");
        vertices.clear();
        uvCount[0] = 0;
        var fallbackBounds = (net.minecraft.client.gui.ScreenRect) boundsMethod.invoke(null, "\uD83D\uDCE1", 0f, 0f, pose);
        var fallback = (net.minecraft.client.gui.render.state.SimpleGuiElementRenderState) constructor.newInstance(
                pose, net.minecraft.client.texture.TextureSetup.empty(), "\uD83D\uDCE1", 0f, 0f, -1, null, fallbackBounds);
        fallback.setupVertices(consumer);
        check(vertices.size() == 4 && uvCount[0] == 4, "Unsupported supplementary character draws one fallback, not two");
        check(fallbackBounds.equals(boundsMethod.invoke(null, "?", 0f, 0f, pose)), "Fallback drawing and bounds agree on codepoints");
        for (String source : new String[]{"/dev/openintel/SnitchRelay.class", "/dev/openintel/tracker/Tracker.class"}) {
            try (var input = CleanFontMetricsTest.class.getResourceAsStream(source)) {
                var node = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(input).accept(node, 0);
                for (var method : node.methods) for (var instruction : method.instructions) {
                    if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode constant && constant.cst instanceof String text)
                        check(!text.contains("\uD83D\uDCE1"), "Snitch feed prefixes do not depend on unsupported pictographs");
                }
            }
        }
        try (var input = CleanFont.class.getResourceAsStream("CleanFont.class")) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
            var pass = node.methods.stream().filter(m -> m.name.equals("pass")).findFirst().orElseThrow();
            int submissions = 0;
            for (var instruction : pass.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                    check(!call.name.equals("drawTexture"), "Text batching does not submit a GUI state per glyph");
                    if (call.name.equals("addSimpleElement")) submissions++;
                }
            }
            check(submissions == 1, "Each text run has a single GUI-state submission");
        }
    }

    private static void verifyMarkerStability() throws Exception {
        var labelClass = Class.forName("dev.openintel.render.MarkerHud$Label");
        var constructor = labelClass.getDeclaredConstructor(float.class, float.class, String.class,
                int.class, float.class, String.class);
        constructor.setAccessible(true);
        var hudClass = dev.openintel.render.MarkerHud.class;
        var smooth = hudClass.getDeclaredMethod("smoothY", labelClass, float.class);
        smooth.setAccessible(true);
        var stateField = hudClass.getDeclaredField("renderY");
        stateField.setAccessible(true);
        var state = (java.util.Map<?, ?>) stateField.get(null);
        state.clear();
        try {
            Object first = constructor.newInstance(0f, 0f, "ExamplePlayer 3224m", -1, 1f, "player|ExamplePlayer");
            Object updated = constructor.newInstance(0f, 0f, "ExamplePlayer 3223m", -1, 1f, "player|ExamplePlayer");
            Object other = constructor.newInstance(0f, 0f, "ExamplePlayer 3223m", -1, 1f, "other-marker");
            check((float) smooth.invoke(null, first, 100f) == 100f, "Initial marker height");
            check((float) smooth.invoke(null, updated, 200f) == 140f,
                    "Distance changes must preserve smoothing rather than snap to a new height");
            check((float) smooth.invoke(null, other, 200f) == 200f,
                    "Separate marker identities do not share smoothing");
        } finally {
            state.clear();
        }
    }

    private static void verifyRadarTextScale() throws Exception {
        var scaleText = dev.openintel.radar.RadarHud.class.getDeclaredMethod("scaleText",
                org.joml.Matrix3x2fStack.class, float.class);
        scaleText.setAccessible(true);
        for (float base : new float[]{0.5f, 0.6f}) {
            for (float setting : new float[]{0.5f, 1f, 1.7f, 2f}) {
                var pose = new org.joml.Matrix3x2fStack(4);
                pose.translate(40f, 25f);
                pose.pushMatrix();
                scaleText.invoke(null, pose, base * setting);
                check(Math.abs(pose.m00() - 2f * base * setting) < 0.0001f
                                && Math.abs(pose.m11() - 2f * base * setting) < 0.0001f,
                        "Radar labels and cardinals are twice their previous scale");
                check(pose.m20() == 40f && pose.m21() == 25f, "Text scaling preserves marker anchor");
                pose.popMatrix();
                check(pose.m00() == 1f && pose.m11() == 1f, "Radar text scaling does not leak into icons");
            }
        }
    }

    private static void verifyLocalLabelScale() throws Exception {
        var transform = dev.openintel.radar.RadarHud.class.getDeclaredMethod("localLabelPose",
                org.joml.Matrix3x2fStack.class, float.class, float.class);
        transform.setAccessible(true);
        for (float iconSize : new float[]{0.3f, 0.67f, 1f, 2f}) {
            var pose = new org.joml.Matrix3x2fStack(4);
            transform.invoke(null, pose, iconSize, 1f);
            check(Math.abs(pose.m00() - 1.2f) < 0.0001f && Math.abs(pose.m11() - 1.2f) < 0.0001f,
                    "Player text size is independent of the icon-size setting");
            check(Math.abs(pose.m21() - (5f * iconSize + 1f)) < 0.0001f,
                    "Text retains clearance below the player icon");
        }
    }

    private static void verifyFontPipeline() throws Exception {
        CleanFont.registerPipeline();
        var field = CleanFont.class.getDeclaredField("PIPELINE");
        field.setAccessible(true);
        var pipeline = (com.mojang.blaze3d.pipeline.RenderPipeline) field.get(null);
        var vanilla = net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED;
        check(net.minecraft.client.gl.RenderPipelines.getAll().contains(pipeline), "Font pipeline registered for resource reload");
        check(pipeline.getBlendFunction().equals(vanilla.getBlendFunction()), "Font coverage uses standard alpha blending");
        check(pipeline.getVertexShader().equals(vanilla.getVertexShader()), "Font uses compatible GUI vertex shader");
        check(pipeline.getVertexFormat().equals(vanilla.getVertexFormat()), "Font uses compatible GUI vertices");
        check(!pipeline.isWriteDepth(), "Transparent padding does not write depth");
        check(pipeline.getFragmentShader().toString().equals("openintel:core/clean_font"), "Font uses coverage shader");
        try (var input = CleanFont.class.getResourceAsStream("/assets/openintel/shaders/core/clean_font.fsh")) {
            check(input != null && input.readAllBytes().length > 0, "Font shader is packaged");
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
