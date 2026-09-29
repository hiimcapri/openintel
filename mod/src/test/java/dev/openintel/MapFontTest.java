package dev.openintel;

import dev.openintel.render.UiFont;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Component;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

public final class MapFontTest {
    public static void main(String[] args) throws Exception {
        UiFont.initialize(true);
        var calls = new AtomicInteger();
        var original = Component.literal("White Rabbit's Timepiece").getVisualOrderText();
        var captured = new AtomicReference<FormattedCharSequence>();
        UiFont.withMapFont("openintel", () -> {
            calls.incrementAndGet();
            check(UiFont.select(FontDescription.DEFAULT).equals(UiFont.SOURCE), "Our overlay measurements use our font");
            captured.set(UiFont.capture(original));
        });
        check(calls.get() == 1, "Our overlay renderer is called exactly once");
        check(UiFont.select(FontDescription.DEFAULT) == FontDescription.DEFAULT, "Map font scope is restored");
        captured.get().accept((index, style, cp) -> {
            check(style.getFont().equals(UiFont.SOURCE), "Our deferred map labels retain our font");
            check(Integer.valueOf(0).equals(style.getShadowColor()), "Our map labels have no text shadow");
            return true;
        });
        for (String owner : new String[]{"journeymap", "xaeroworldmap", "other_mod", "openintel-extra", "", null}) {
            int previous = calls.get();
            UiFont.withMapFont(owner, () -> {
                calls.incrementAndGet();
                check(UiFont.select(FontDescription.DEFAULT) == FontDescription.DEFAULT, "Foreign map text keeps its font");
                check(UiFont.capture(original) == original, "Identical label text from another owner is not changed");
            });
            check(calls.get() == previous + 1, "Foreign renderer is called exactly once");
        }
        UiFont.withMapFont("openintel", () -> {
            UiFont.withMapFont("another_mod", () -> check(UiFont.select(FontDescription.DEFAULT) == FontDescription.DEFAULT,
                    "Nested foreign overlay does not inherit our font"));
            check(UiFont.select(FontDescription.DEFAULT).equals(UiFont.SOURCE), "Outer owned overlay scope restored");
        });
        try {
            UiFont.withMapFont("openintel", () -> { throw new IllegalStateException("scope test"); });
        } catch (IllegalStateException expected) { }
        check(UiFont.select(FontDescription.DEFAULT) == FontDescription.DEFAULT, "Failed overlay rendering cannot leak font selection");
        UiFont.setEnabled(false);
        UiFont.withMapFont("openintel", () -> check(UiFont.select(FontDescription.DEFAULT) == FontDescription.DEFAULT,
                "Disabled clean font is respected on map overlays"));
        verifyIntegrationWiring();
        if (args.length > 0) verifyJourneyMap(args[0]);
        System.out.println("Map font ownership tests passed");
    }

    private static void verifyIntegrationWiring() throws Exception {
        var jm = readClass("dev/openintel/mixin/JourneyMapTextMixin");
        var annotations = new java.util.ArrayList<org.objectweb.asm.tree.AnnotationNode>();
        if (jm.visibleAnnotations != null) annotations.addAll(jm.visibleAnnotations);
        if (jm.invisibleAnnotations != null) annotations.addAll(jm.invisibleAnnotations);
        check(annotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Pseudo;")),
                "JourneyMap hook is optional when the mod is absent");
        var hook = jm.methods.stream().filter(m -> m.name.equals("openintel$mapLabelFont")).findFirst().orElseThrow();
        check(calls(hook, "getModId") && calls(hook, "withMapFont"), "JourneyMap font scope uses actual overlay ownership");
        check(!hook.desc.contains("journeymap/"), "Optional hook has no hard-linked JourneyMap argument types");
        var xaero = readClass("dev/openintel/xaero/XaeroBridge");
        var label = xaero.methods.stream().filter(m -> m.name.equals("label")).findFirst().orElseThrow();
        check(calls(label, "withMapFont"), "Xaero scopes measurement and drawing only for our label helper");
        var render = xaero.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        check(!calls(render, "withMapFont"), "Xaero map-wide rendering is not font-scoped");
        try (var input = MapFontTest.class.getResourceAsStream("/openintel.mixins.json")) {
            var config = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            check(config.getAsJsonObject().getAsJsonArray("client").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("JourneyMapTextMixin")), "JourneyMap font hook is registered");
        }
    }

    private static org.objectweb.asm.tree.ClassNode readClass(String name) throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var input = MapFontTest.class.getResourceAsStream("/" + name + ".class")) {
            check(input != null, "Integration class is packaged: " + name);
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static boolean calls(org.objectweb.asm.tree.MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }

    private static void verifyJourneyMap(String path) throws Exception {
        try (var jar = new ZipFile(path)) {
            var entry = jar.getEntry("journeymap/client/render/draw/BaseOverlayDrawStep.class");
            check(entry != null, "Installed JourneyMap has overlay text renderer");
            boolean[] methods = new boolean[2];
            try (var input = jar.getInputStream(entry)) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (name.equals("getModId") && descriptor.equals("()Ljava/lang/String;")) methods[0] = true;
                        if (name.equals("drawText") && descriptor.equals("(Lnet/minecraft/client/gui/GuiGraphicsExtractor;DDLjourneymap/client/render/map/Renderer;DD)V")) methods[1] = true;
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            check(methods[0] && methods[1], "Installed JourneyMap owner and text-render hooks match");
        }
        System.out.println("Installed JourneyMap text hook verified");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
