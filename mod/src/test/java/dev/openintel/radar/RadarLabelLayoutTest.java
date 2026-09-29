package dev.openintel.radar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RadarLabelLayoutTest {
    public static void main(String[] args) throws Exception {
        verifyCompassRendering();
        verifyLocalPlayersOnly();
        verifyRotationStability();
        verifyIndependentAnchors();
        var cluster = List.of(label("player:ExampleA", 40, 34, 90, 13),
                label("player:ExampleC", 40, 34, 95, 13), label("player:ExampleB", 42, 35, 75, 13));
        var result = RadarLabelLayout.place(cluster, 320, 200);
        check(result.placed().size() == 3 && result.hidden() == 0, "Cluster retains all names");
        verifyReadable(result, 320, 200);
        var reversed = new ArrayList<>(cluster);
        Collections.reverse(reversed);
        check(result.equals(RadarLabelLayout.place(reversed, 320, 200)), "Stable identities make iteration order irrelevant");

        var corners = List.of(label("a", -40, -30, 100, 13), label("b", -40, -30, 100, 26),
                label("c", 280, 190, 80, 20), label("d", 280, 190, 80, 13));
        result = RadarLabelLayout.place(corners, 320, 200);
        check(result.hidden() == 0, "Screen-edge stacks fit without dropping labels");
        verifyReadable(result, 320, 200);

        var overflow = new RadarLabelLayout.Label("~overflow", 80, 20, 12, 13, true);
        result = RadarLabelLayout.place(List.of(overflow, label("player:test", 50, 20, 90, 13)), 320, 200);
        var reserved = result.placed().stream().filter(p -> p.label().fixed()).findFirst().orElseThrow();
        check(reserved.x() == 80 && reserved.y() == 20, "Overflow indicator keeps its reserved position");
        check(result.hidden() == 0, "Contact avoids the overflow indicator");
        verifyReadable(result, 320, 200);

        var dense = new ArrayList<RadarLabelLayout.Label>();
        for (int i = 0; i < 200; i++) dense.add(label(String.format("player:%03d", i), 40, 20, 90, 13));
        result = RadarLabelLayout.place(dense, 200, 100);
        check(result.hidden() > 0 && result.hidden() + result.placed().size() == dense.size(),
                "Overflow is counted explicitly rather than drawn on top of other names");
        verifyReadable(result, 200, 100);
        check(RadarLabelLayout.place(List.of(label("wide", 0, 0, 400, 13)), 200, 100).hidden() == 1,
                "Impossible label is reported as overflow");
        check(RadarLabelLayout.place(cluster, 0, 0).hidden() == cluster.size(), "Tiny viewport is bounded");
        System.out.println("Radar label layout tests passed");
    }

    private static void verifyRotationStability() {
        var session = new RadarLabelLayout.Session();
        var first = session.place(List.of(label("a", 100, 80, 90, 13), label("b", 100, 80.01f, 90, 13)), 500, 300);
        var second = session.place(List.of(label("a", 100, 80, 90, 13), label("b", 100, 79.99f, 90, 13)), 500, 300);
        float y1 = first.placed().stream().filter(p -> p.label().key().equals("b")).findFirst().orElseThrow().y();
        float y2 = second.placed().stream().filter(p -> p.label().key().equals("b")).findFirst().orElseThrow().y();
        check(Math.abs(y2 - y1) < 1f, "Tiny rotation movement must not flip a contact between opposite label rows: " + y1 + " -> " + y2);
        var onlyB = List.of(label("b", 100, 80, 90, 13));
        var released = session.place(onlyB, 500, 300);
        check(released.placed().get(0).y() == 80, "Label returns to its anchor after the collision disappears");
        check(session.place(onlyB, 120, 40).equals(RadarLabelLayout.place(onlyB, 120, 40)), "Resize discards stale placement offsets");
        session.place(List.of(), 500, 300);
        check(session.place(onlyB, 500, 300).placed().get(0).y() == 80, "Removed contacts leave no layout history");
        session = new RadarLabelLayout.Session();
        RadarLabelLayout.Placed previous = null;
        for (int i = 0; i < 3600; i++) {
            double angle = i * Math.PI * 2 / 3600;
            float x = 300 + (float) Math.cos(angle) * 60;
            float y = 200 + (float) Math.sin(angle) * 60;
            var result = session.place(List.of(label("a", x, y, 90, 13),
                    label("b", x + 0.2f, y + (float) Math.sin(angle) * 0.2f, 90, 13)), 800, 500);
            check(result.hidden() == 0, "Rotating pair stays visible");
            verifyReadable(result, 800, 500);
            var current = result.placed().stream().filter(p -> p.label().key().equals("b")).findFirst().orElseThrow();
            if (previous != null) check(Math.hypot(current.x() - previous.x(), current.y() - previous.y()) < 2,
                    "Labels follow rotation without lane jumping");
            previous = current;
        }
        session = new RadarLabelLayout.Session();
        var last = new java.util.HashMap<String, RadarLabelLayout.Placed>();
        for (int frame = 0; frame < 1800; frame++) {
            double angle = frame * Math.PI * 2 / 1800;
            var labels = new ArrayList<RadarLabelLayout.Label>();
            for (int i = 0; i < 8; i++) labels.add(label("player:" + i,
                    300 + (float) Math.cos(angle) * 60 + i * 0.2f,
                    250 + (float) Math.sin(angle) * 60 + (float) Math.sin(angle + i * 0.2) * 0.15f,
                    90 + i % 3 * 6, 14.8f));
            var packed = session.place(labels, 800, 600);
            check(packed.placed().size() >= 3 && packed.hidden() + packed.placed().size() == labels.size(),
                    "Rotating crowded contacts retain nearby names and count excess without long stacks");
            verifyReadable(packed, 800, 600);
            for (var placed : packed.placed()) {
                var old = last.put(placed.label().key(), placed);
                if (old != null) check(Math.hypot(placed.x() - old.x(), placed.y() - old.y()) < 3,
                        "Fractional-height clustered labels do not swap rows during rotation: " + placed.label().key());
            }
        }
    }

    private static void verifyIndependentAnchors() {
        var session = new RadarLabelLayout.Session();
        float originalY = Float.NaN;
        for (int frame = 0; frame <= 6; frame++) {
            var result = session.place(List.of(label("a", 100, 100 + frame, 90, 13),
                    label("b", 100, 100, 90, 13)), 500, 300);
            verifyReadable(result, 500, 300);
            var own = result.placed().stream().filter(p -> p.label().key().equals("b")).findFirst().orElseThrow();
            if (frame == 0) originalY = own.y();
            else check(Math.abs(own.y() - originalY) < 0.01f, "Moving one contact cannot drag a stationary neighbor: " + originalY + " -> " + own.y());
        }
        var crowd = new ArrayList<RadarLabelLayout.Label>();
        for (int i = 0; i < 20; i++) crowd.add(label("contact:" + i, 100, 100, 90, 13));
        var result = session.place(crowd, 500, 500);
        check(result.hidden() > 0, "Dense contacts use overflow rather than distant name stacks");
        for (var placed : result.placed()) check(Math.abs(placed.y() - placed.label().y()) <= placed.label().height() + 6.01f,
                "Collision movement is bounded to one nearby row");
    }

    private static void verifyLocalPlayersOnly() throws Exception {
        try (var input = RadarHud.class.getResourceAsStream("RadarHud.class")) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
            var render = node.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
            boolean local = false, pings = false;
            for (var instruction : render.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                    check(!call.name.equals("renderRelayBlips"), "Relay-only players must not be rendered on the radar");
                    if (call.name.equals("renderPlayers")) local = true;
                    if (call.name.equals("renderPings")) pings = true;
                }
            }
            check(local && pings, "Local players and shared pings remain wired to the radar");
            var players = node.methods.stream().filter(m -> m.name.equals("renderPlayers")).findFirst().orElseThrow();
            boolean worldPlayers = false, range = false;
            for (var instruction : players.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                    if (call.name.equals("players")) worldPlayers = true;
                    check(!call.owner.equals("dev/openintel/tracker/Tracker"), "Player blips use Minecraft entities, not relay snapshots");
                }
                if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.equals("radarRange")) range = true;
            }
            check(worldPlayers && range, "Radar players must be loaded locally and checked against configured range");
        }
    }

    private static void verifyCompassRendering() throws Exception {
        boolean[] drawsPlainText = {false};
        try (var input = RadarHud.class.getResourceAsStream("RadarHud.class")) {
            new org.objectweb.asm.ClassReader(input).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                   String signature, String[] exceptions) {
                    if (name.equals("drawLabels")) return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        private boolean layoutFirst;
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            if (owner.equals("dev/openintel/radar/RadarLabelLayout$Session") && method.equals("place")) layoutFirst = true;
                            if (method.equals("queueLabel")) check(layoutFirst, "No invisible overflow box is reserved before contacts are laid out");
                            check(!method.equals("fill"), "Contact label backgrounds remain transparent");
                        }
                    };
                    if (!name.equals("cardinal")) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            check(!method.equals("queueLabel") && !method.equals("fill"),
                                    "Compass letters must not have backings or reserve collision-layout space");
                            if (method.equals("lbl")) drawsPlainText[0] = true;
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        check(drawsPlainText[0], "Compass letters draw directly below contact labels");
    }

    private static RadarLabelLayout.Label label(String key, float x, float y, float width, float height) {
        return new RadarLabelLayout.Label(key, x, y, width, height, false);
    }

    private static void verifyReadable(RadarLabelLayout.Result result, int width, int height) {
        for (int i = 0; i < result.placed().size(); i++) {
            var a = result.placed().get(i);
            check(a.x() >= 2 && a.y() >= 2 && a.right() <= width - 2 && a.bottom() <= height - 2,
                    "Label stays within the viewport");
            for (int j = 0; j < i; j++) {
                var b = result.placed().get(j);
                check(a.right() + 2 <= b.x() || b.right() + 2 <= a.x()
                                || a.bottom() + 2 <= b.y() || b.bottom() + 2 <= a.y(),
                        "Label rectangles never overlap: " + a.label().key() + " / " + b.label().key());
            }
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
