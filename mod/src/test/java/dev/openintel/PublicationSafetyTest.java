package dev.openintel;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class PublicationSafetyTest {
    public static void main(String[] args) throws Exception {
        check(resource("/openintel_token.txt").strip().equals("CHANGE_ME"), "Base artifact must contain only the placeholder token");
        String license = resource("/assets/openintel/font/LICENSE-DejaVu.txt");
        check(license.contains("Copyright (c) 2003 by Bitstream") && license.contains("Tavmjong Bah"),
                "Redistributed font includes its copyright and license notices");
        var fields = new HashSet<>(Set.of("relayUrl", "minecraftServer"));
        var node = new ClassNode();
        try (var stream = PublicationSafetyTest.class.getResourceAsStream("/dev/openintel/config/OIConfig.class")) {
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var constructor = node.methods.stream().filter(method -> method.name.equals("<init>")).findFirst().orElseThrow();
        for (var instruction : constructor.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && fields.contains(field.name)) {
                check(field.getPrevious() instanceof LdcInsnNode value && "".equals(value.cst),
                        "Public client default must not configure a deployment endpoint: " + field.name);
                fields.remove(field.name);
            }
        }
        check(fields.isEmpty(), "Both endpoint defaults are verified");
        System.out.println("Public artifact neutrality tests passed");
    }

    private static String resource(String name) throws Exception {
        try (var stream = PublicationSafetyTest.class.getResourceAsStream(name)) {
            check(stream != null, "Required public resource is packaged: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
