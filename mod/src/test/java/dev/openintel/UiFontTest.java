package dev.openintel;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.serialization.JsonOps;
import dev.openintel.render.UiFont;
import net.minecraft.client.font.FontLoader;
import net.minecraft.client.font.GlyphBaker;
import net.minecraft.client.font.TrueTypeFontLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

public final class UiFontTest {
    public static void main(String[] args) throws Exception {
        UiFont.initialize(false);
        check(UiFont.select(StyleSpriteSource.DEFAULT) == StyleSpriteSource.DEFAULT, "Disabled clean font preserves vanilla");
        check(UiFont.setEnabled(true), "Toggle reports font layout change");
        check(UiFont.select(StyleSpriteSource.DEFAULT) == StyleSpriteSource.DEFAULT,
                "Inventory, menus, and other mods keep their default font outside the HUD scope");
        verifyHudScope();
        verifyCaptureReuse();
        verifyChatInput();
        verifyFontToggle();
        check(!UiFont.setEnabled(true), "Unchanged setting does not trigger chat reflow");
        for (String id : new String[]{"server:icons", "minecraft:alt", "minecraft:uniform", "openintel:ui"}) {
            var custom = new StyleSpriteSource.Font(Identifier.of(id));
            check(UiFont.select(custom) == custom, "Explicit font remains untouched: " + id);
        }
        Style styled = Style.EMPTY.withColor(0xFFAA00).withBold(true).withItalic(true).withUnderline(true);
        UiFont.select(styled.getFont());
        check(styled.getFont().equals(StyleSpriteSource.DEFAULT) && styled.isBold() && styled.isItalic()
                && styled.isUnderlined() && styled.getColor().getRgb() == 0xFFAA00, "Style is never mutated by font selection");
        UiFont.setEnabled(false);
        check(UiFont.select(StyleSpriteSource.DEFAULT) == StyleSpriteSource.DEFAULT, "Toggle restores vanilla font");

        try (var baker = new GlyphBaker(null, UiFont.FONT_ID)) {
            var atlasId = GlyphBaker.class.getDeclaredMethod("getAtlasId", int.class);
            atlasId.setAccessible(true);
            String label = atlasId.invoke(baker, 0).toString();
            check(UiFont.smoothAtlas(label, TextureFormat.RED8), "Native glyph atlas is recognized for smooth coverage");
            check(!UiFont.smoothAtlas(label, TextureFormat.RGBA8), "Color glyphs retain their own rendering");
        }
        check(!UiFont.smoothAtlas("minecraft:default/0", TextureFormat.RED8), "Vanilla atlas filtering unchanged");
        check(!UiFont.isAtlas("openintel:ui_other/0") && !UiFont.isAtlas(null), "Atlas matching is scoped precisely");

        try (var input = UiFontTest.class.getResourceAsStream("/assets/openintel/font/ui.json")) {
            check(input != null, "Native font definition packaged");
            var providers = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("providers");
            var loader = FontLoader.CODEC.codec().parse(JsonOps.INSTANCE, providers.get(0)).getOrThrow();
            check(loader instanceof TrueTypeFontLoader, "Uses native TrueType loader");
            var ttf = (TrueTypeFontLoader) loader;
            check(ttf.oversample() == 4f && ttf.size() == 9f, "Oversampled text fits standard GUI line height");
            check(providers.get(1).getAsJsonObject().get("id").getAsString().equals("minecraft:default"),
                    "Vanilla glyph providers remain as Unicode fallback");
            var resources = (ResourceManager) Proxy.newProxyInstance(UiFontTest.class.getClassLoader(),
                    new Class<?>[]{ResourceManager.class}, (proxy, method, values) -> {
                        if (!method.getName().equals("open")) throw new UnsupportedOperationException(method.getName());
                        Identifier id = (Identifier) values[0];
                        check(id.equals(Identifier.of("openintel", "font/clean.ttf")), "TTF resolves to bundled resource");
                        return UiFontTest.class.getResourceAsStream("/assets/" + id.getNamespace() + "/" + id.getPath());
                    });
            try (var font = loader.build().left().orElseThrow().load(resources)) {
                for (int cp : "Entering The Dog Den [STAFF OUT] Reinforcing White Rabbit's Timepiece 294 0:32".codePoints().toArray()) {
                    var glyph = font.getGlyph(cp);
                    check(glyph != null, "Sample HUD/chat/title character has a glyph: " + cp);
                    float width = glyph.getMetrics().getAdvance();
                    check(width > 0f && width < 12f, "Native font advance stays in logical pixels");
                    check(glyph.getMetrics().getAdvance(true) >= width, "Bold layout preserves glyph advances");
                }
                check(font.getGlyph(0x221E) != null, "Infinite potion duration glyph supported");
                check(font.getGlyph(0x00E9) != null, "Accented chat characters supported");
            }
        }
        verifyShadows();
        verifyMixinTargets();
        check(UiFont.PIPELINE.getVertexFormat().equals(net.minecraft.client.gl.RenderPipelines.GUI_TEXT.getVertexFormat()),
                "Native glyph vertex layout is preserved");
        System.out.println("UI font selection and native loading tests passed");
    }

    private static void verifyHudScope() {
        var iconFont = new StyleSpriteSource.Font(Identifier.of("server", "icons"));
        var click = new net.minecraft.text.ClickEvent.SuggestCommand("/oi");
        var style = Style.EMPTY.withColor(0xFFAA00).withBold(true).withClickEvent(click);
        var text = net.minecraft.text.Text.literal("A").setStyle(style)
                .append(net.minecraft.text.Text.literal("B").styled(s -> s.withFont(iconFont)));
        var original = text.asOrderedText();
        var captured = new java.util.concurrent.atomic.AtomicReference<net.minecraft.text.OrderedText>();
        check(UiFont.capture(original) == original, "Unrelated UI text is not wrapped");
        UiFont.withHudFont(() -> {
            check(UiFont.select(StyleSpriteSource.DEFAULT).equals(UiFont.SOURCE), "HUD measurements use clean font");
            check(UiFont.select(iconFont) == iconFont, "HUD icon fonts remain untouched");
            captured.set(UiFont.capture(original));
            try {
                UiFont.withHudFont(() -> { throw new IllegalStateException("scope test"); });
            } catch (IllegalStateException expected) { }
            check(UiFont.select(StyleSpriteSource.DEFAULT).equals(UiFont.SOURCE), "Nested failure restores outer HUD scope");
            check(java.util.concurrent.CompletableFuture.supplyAsync(() -> UiFont.select(StyleSpriteSource.DEFAULT)).join()
                    == StyleSpriteSource.DEFAULT, "HUD scope does not leak to other threads");
        });
        check(UiFont.select(StyleSpriteSource.DEFAULT) == StyleSpriteSource.DEFAULT, "HUD scope does not leak to other renderers");
        captured.get().accept((index, effective, cp) -> {
            if (cp == 'A') {
                check(effective.getFont().equals(UiFont.SOURCE), "Deferred HUD rendering retains its selected font");
                check(Integer.valueOf(0).equals(effective.getShadowColor()), "Deferred clean text explicitly disables shadows");
                check(effective.isBold() && effective.getColor().getRgb() == 0xFFAA00
                        && click.equals(effective.getClickEvent()), "Deferred rendering preserves formatting and click actions");
            } else {
                check(effective.getFont().equals(iconFont), "Deferred rendering preserves server icon fonts");
            }
            return true;
        });
        check(text.getStyle().getFont().equals(StyleSpriteSource.DEFAULT), "Original chat message is not mutated");
        try {
            UiFont.withHudFont(() -> { throw new IllegalStateException("scope test"); });
        } catch (IllegalStateException expected) { }
        check(UiFont.select(StyleSpriteSource.DEFAULT) == StyleSpriteSource.DEFAULT, "Failed HUD render cannot take over menu fonts");
    }

    private static void verifyCaptureReuse() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<net.minecraft.text.OrderedText>();
        UiFont.withHudFont(() -> captured.set(UiFont.capture(net.minecraft.text.Text.literal("a".repeat(64)).asOrderedText())));
        var styles = new java.util.ArrayList<Style>();
        captured.get().accept((index, style, cp) -> { styles.add(style); return true; });
        check(styles.size() == 64 && styles.stream().allMatch(style -> style == styles.get(0)),
                "A plain text run reuses its transformed style instead of allocating per character");
        try (var input = UiFont.class.getResourceAsStream("UiFont.class")) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
            var match = node.methods.stream().filter(m -> m.name.equals("isAtlas")).findFirst().orElseThrow();
            for (var instruction : match.instructions) check(!(instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode),
                    "Atlas matching reuses its prefix instead of concatenating strings per glyph");
        }
    }

    private static void verifyChatInput() throws Exception {
        var vanillaGlyphs = fixedGlyphs(6);
        var cleanGlyphs = fixedGlyphs(4);
        var selected = new java.util.concurrent.atomic.AtomicReference<StyleSpriteSource>();
        var delegate = new net.minecraft.client.font.TextRenderer.GlyphsProvider() {
            @Override
            public net.minecraft.client.font.GlyphProvider getGlyphs(StyleSpriteSource source) {
                selected.set(source);
                return source.equals(UiFont.SOURCE) ? cleanGlyphs : vanillaGlyphs;
            }

            @Override
            public net.minecraft.client.font.EffectGlyph getRectangleGlyph() {
                return null;
            }
        };
        var original = new net.minecraft.client.font.TextRenderer(delegate);
        var input = UiFont.chatInputRenderer(delegate);
        check(original.getWidth("abcd") == 24, "Unrelated text renderer keeps vanilla metrics");
        check(input.getWidth("abcd") == 16, "Chat input uses clean glyph metrics outside HUD render scope");
        check(input.trimToWidth("abcd", 8).equals("ab"), "Input selection and scrolling use clean text widths");
        var custom = new StyleSpriteSource.Font(Identifier.of("server", "icons"));
        check(input.getWidth(net.minecraft.text.Text.literal("ab").styled(s -> s.withFont(custom))) == 12
                && selected.get().equals(custom), "Input preserves explicit custom fonts");
        var field = new net.minecraft.client.gui.widget.TextFieldWidget(input, 4, 0, 100, 12,
                net.minecraft.text.Text.literal("Chat input"));
        field.setDrawsBackground(false);
        field.setText("abcd");
        check(field.getCharacterX(2) == 12, "Chat input cursor positions use clean text advances");
        field.setSelectionStart(1);
        field.setSelectionEnd(3);
        check(field.getSelectedText().equals("bc"), "Selection preserves the typed character range");
        field.setWidth(12);
        field.setCursorToEnd(false);
        var firstCharacter = net.minecraft.client.gui.widget.TextFieldWidget.class.getDeclaredField("firstCharacterIndex");
        firstCharacter.setAccessible(true);
        int first = firstCharacter.getInt(field);
        check(first > 0 && input.getWidth(field.getText().substring(first)) <= field.getInnerWidth(),
                "Long input scrolls using the same clean-font measurements");
        check(original.getWidth("abcd") == 24 && UiFont.select(StyleSpriteSource.DEFAULT) == StyleSpriteSource.DEFAULT,
                "Input rendering cannot change menu fonts or global selection");
        UiFont.setEnabled(false);
        check(input.getWidth("abcd") == 24, "Input respects the clean-font toggle");
        UiFont.setEnabled(true);
        check(input.getWidth("abcd") == 16, "Input restores clean metrics without rebuilding the field");
    }

    private static net.minecraft.client.font.GlyphProvider fixedGlyphs(float advance) {
        var glyph = new net.minecraft.client.font.BakedGlyph() {
            @Override
            public net.minecraft.client.font.GlyphMetrics getMetrics() {
                return net.minecraft.client.font.GlyphMetrics.empty(advance);
            }

            @Override
            public net.minecraft.client.font.TextDrawable.DrawnGlyphRect create(float x, float y, int color,
                    int shadow, Style style, float boldOffset, float shadowOffset) {
                throw new UnsupportedOperationException("Measurement-only test glyph");
            }
        };
        return new net.minecraft.client.font.GlyphProvider() {
            @Override
            public net.minecraft.client.font.BakedGlyph get(int codePoint) {
                return glyph;
            }

            @Override
            public net.minecraft.client.font.BakedGlyph getObfuscated(net.minecraft.util.math.random.Random random, int width) {
                return glyph;
            }
        };
    }

    private static void verifyFontToggle() throws Exception {
        var factory = dev.openintel.gui.ClickGui.class.getDeclaredMethod("fontToggle",
                java.util.function.BooleanSupplier.class, java.util.function.Consumer.class);
        factory.setAccessible(true);
        var value = new java.util.concurrent.atomic.AtomicBoolean(true);
        var changes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<Boolean> apply = next -> {
            value.set(next);
            UiFont.setEnabled(next);
            changes.incrementAndGet();
        };
        var button = (net.minecraft.client.gui.widget.ButtonWidget) factory.invoke(null,
                (java.util.function.BooleanSupplier) value::get, apply);
        check(button.getMessage().equals(net.minecraft.text.Text.translatable("options.openintel.hud.font.clean")),
                "Font toggle displays the loaded clean-font preference");
        button.onPress(null);
        check(!value.get() && button.getMessage().equals(net.minecraft.text.Text.translatable("options.openintel.hud.font.minecraft")),
                "First click selects and displays Minecraft font");
        UiFont.withHudFont(() -> check(UiFont.select(StyleSpriteSource.DEFAULT) == StyleSpriteSource.DEFAULT,
                "Minecraft font selection takes effect at runtime"));
        button.onPress(null);
        check(value.get() && changes.get() == 2
                && button.getMessage().equals(net.minecraft.text.Text.translatable("options.openintel.hud.font.clean")),
                "Second click restores clean font and each click applies once");
        UiFont.withHudFont(() -> check(UiFont.select(StyleSpriteSource.DEFAULT).equals(UiFont.SOURCE),
                "Clean font selection takes effect at runtime"));
        try (var input = UiFontTest.class.getResourceAsStream("/assets/openintel/lang/en_us.json")) {
            var lang = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            check(lang.get("options.openintel.hud.font").getAsString().equals("Font"), "HUD font option is localized");
            check(lang.get("options.openintel.hud.font.clean").getAsString().equals("Clean font"), "Clean choice is localized");
            check(lang.get("options.openintel.hud.font.minecraft").getAsString().equals("Minecraft font"), "Minecraft choice is localized");
        }
    }

    private static void verifyShadows() {
        for (int alpha = 0; alpha <= 255; alpha++) {
            for (int rgb : new int[]{0, 0x123456, 0xFFFFFF}) {
                check(UiFont.shadowColor((alpha << 24) | rgb) == 0,
                        "Clean text has no shadow regardless of source tint or opacity");
            }
        }
        for (float offset : new float[]{0f, 0.8f, 1f, 4f}) {
            check(UiFont.shadowOffset(offset) == 0f, "Clean text has no offset shadow geometry");
        }
    }

    private static void verifyMixinTargets() throws Exception {
        net.minecraft.client.font.BakedGlyphImpl.class.getDeclaredMethod("create", float.class, float.class,
                int.class, int.class, Style.class, float.class, float.class);
        net.minecraft.client.font.BakedGlyphImpl.class.getDeclaredMethod("create", float.class, float.class,
                float.class, float.class, float.class, int.class, int.class, float.class);
        check(net.minecraft.client.font.BakedGlyphImpl.class.getDeclaredField("textureView").getType()
                == com.mojang.blaze3d.textures.GpuTextureView.class, "Shadow hook can identify the clean-font atlas");
        var chat = net.minecraft.client.gui.hud.ChatHud.class;
        chat.getDeclaredMethod("addVisibleMessage", net.minecraft.client.gui.hud.ChatHudLine.class);
        chat.getDeclaredMethod("render", net.minecraft.client.gui.DrawContext.class,
                net.minecraft.client.font.TextRenderer.class, int.class, int.class, int.class, boolean.class, boolean.class);
        chat.getDeclaredMethod("render", net.minecraft.client.font.DrawnTextConsumer.class,
                int.class, int.class, boolean.class);
        for (String name : new String[]{"renderOverlayMessage", "renderTitleAndSubtitle"}) {
            net.minecraft.client.gui.hud.InGameHud.class.getDeclaredMethod(name,
                    net.minecraft.client.gui.DrawContext.class, net.minecraft.client.render.RenderTickCounter.class);
        }
        net.minecraft.client.gui.render.state.TextGuiElementRenderState.class.getDeclaredConstructor(
                net.minecraft.client.font.TextRenderer.class, net.minecraft.text.OrderedText.class,
                org.joml.Matrix3x2fc.class, int.class, int.class, int.class, int.class,
                boolean.class, boolean.class, net.minecraft.client.gui.ScreenRect.class);
        check(net.minecraft.client.font.TextRenderer.class.getDeclaredField("fonts").getType()
                == net.minecraft.client.font.TextRenderer.GlyphsProvider.class, "Chat input can reuse the original validated glyph provider");
        verifyChatInputHook();
        verifyFontSettingWiring();
        var selector = net.minecraft.client.font.TextRenderer.class.getDeclaredMethod("getGlyphs", StyleSpriteSource.class);
        check(selector.getReturnType() == net.minecraft.client.font.GlyphProvider.class, "Text selection hook matches this Minecraft version");
        var element = net.minecraft.client.gui.render.state.GlyphGuiElementRenderState.class;
        check(element.getDeclaredMethod("pipeline").getReturnType() == com.mojang.blaze3d.pipeline.RenderPipeline.class,
                "GUI font pipeline hook matches");
        check(element.getDeclaredMethod("textureSetup").getReturnType() == net.minecraft.client.texture.TextureSetup.class,
                "GUI font sampler hook matches");
        check(element.getDeclaredField("renderable").getType() == net.minecraft.client.font.TextDrawable.class,
                "GUI glyph shadow field matches");
        net.minecraft.client.font.GlyphAtlasTexture.class.getDeclaredConstructor(java.util.function.Supplier.class,
                net.minecraft.client.font.TextRenderLayerSet.class, boolean.class);
        try (var input = UiFontTest.class.getResourceAsStream("/openintel.mixins.json")) {
            var mixins = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("client");
            for (String name : new String[]{"TextRendererMixin", "GlyphGuiElementMixin", "GlyphAtlasTextureMixin", "BakedGlyphShadowMixin",
                    "HudTextFontMixin", "ChatHudFontMixin", "TextGuiFontMixin", "ChatInputFontMixin", "TextRendererAccessor"}) {
                check(mixins.asList().stream().anyMatch(value -> value.getAsString().equals(name)), "Font mixin registered: " + name);
            }
        }
    }

    private static void verifyFontSettingWiring() throws Exception {
        boolean[] wired = new boolean[4];
        try (var input = dev.openintel.gui.ClickGui.class.getResourceAsStream("ClickGui.class")) {
            new org.objectweb.asm.ClassReader(input).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                   String signature, String[] exceptions) {
                    if (!name.equals("applyFont")) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (opcode == org.objectweb.asm.Opcodes.PUTFIELD && name.equals("cleanFont")) wired[0] = true;
                        }
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (owner.equals("dev/openintel/render/UiFont") && name.equals("setEnabled")) wired[1] = true;
                            if (owner.equals("net/minecraft/client/gui/hud/ChatHud") && name.equals("reset")) wired[2] = true;
                            if (owner.equals("dev/openintel/config/OIConfig") && name.equals("save")) wired[3] = true;
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        check(wired[0] && wired[1] && wired[2] && wired[3], "HUD font toggle updates config, applies live, reflows chat, and persists");
    }

    private static void verifyChatInputHook() throws Exception {
        int[] references = {0};
        try (var stream = net.minecraft.client.gui.screen.ChatScreen.class.getResourceAsStream("ChatScreen.class")) {
            new org.objectweb.asm.ClassReader(stream).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                   String signature, String[] exceptions) {
                    if (!name.equals("init") || !descriptor.equals("()V")) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (owner.equals("net/minecraft/client/MinecraftClient") && name.equals("advanceValidatingTextRenderer")) references[0]++;
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        check(references[0] == 1, "Chat input renderer hook matches exactly one field construction, not the suggestion popup");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
