package dev.openintel.wartable;

import dev.openintel.OpenIntelClient;
import dev.openintel.ping.PingManager;
import dev.openintel.render.CleanFont;
import dev.openintel.render.UiFont;
import dev.openintel.tracker.Tracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The War Table: the server's rendered live map as a pan/zoom canvas with a
 * shared annotation layer on top. Admins draw strokes that sync through the
 * relay; everyone else gets a live view-only copy. Relay players and pings
 * are plotted on the map while the screen is open.
 *
 * Strokes live in world coordinates — the map image is just a backdrop, so
 * hourly re-renders never invalidate the overlay.
 */
public class WarTableScreen extends Screen {

    // ------------------------------------------------------------ tools ----
    private enum UiTool {
        PAN("Pan", null), PEN("Pen", Stroke.Tool.PEN), LINE("Line", Stroke.Tool.LINE),
        ARROW("Arrow", Stroke.Tool.ARROW),
        LABEL("Label", Stroke.Tool.LABEL), PIN("Pin", Stroke.Tool.MARKER),
        ERASE("Erase", null);

        final String label;
        final Stroke.Tool strokeTool;
        UiTool(String label, Stroke.Tool strokeTool) {
            this.label = label;
            this.strokeTool = strokeTool;
        }
    }

    private static final int[] PALETTE = {
            0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55,
            0xFF55FFFF, 0xFF5555FF, 0xFFE040FB, 0xFFFFFFFF,
    };
    private static final int TOOLBAR_H = 44;
    private static final int STATUS_H = 16;
    private static final int TOOL_ROW_Y = 24;
    private static final int MAX_TEXTURES = 192;
    private static final double MAX_ZOOM = 16.0;

    // ------------------------------------------------------------- view ----
    private double centerPx, centerPy;   // map-px coordinate at screen center
    private double zoom = 1.0;           // screen px per map px
    private double minZoom = 0.05;
    private boolean viewInitialized;
    private boolean pendingWorldCenter;
    private double pendingWorldX, pendingWorldZ;

    // ------------------------------------------------------------ tools ----
    private UiTool tool = UiTool.PAN;
    private int color = PALETTE[0];
    private float strokeWidth = 4f;
    private boolean admin;
    private boolean toolbarAdmin;
    private String toolbarRenderer;

    // draft in world coords (flat x,z list)
    private final List<Float> draft = new ArrayList<>();
    private boolean dragging;            // pen/line drag in progress
    private boolean panning;
    private double panLastX, panLastY;
    private double labelAnchorX, labelAnchorZ;
    private boolean labeling;
    private UiTool labelTool;
    private TextFieldWidget labelField;
    private String eraseHoverId;
    private Sanctuaries.Sanctuary hoveredSanctuary;

    // tile texture LRU (access-ordered: eldest entry evicts first)
    private final LinkedHashMap<String, Identifier> tileTextures =
            new LinkedHashMap<>(64, 0.75f, true);

    // Stroke overlay: rasterized to a screen-size texture, uploaded on
    // view/data change — the same drawTexture path as the map tiles, which
    // the deferred GUI renderer orders reliably.
    private NativeImageBackedTexture strokeTex;
    private Identifier strokeTexId;
    private double lastCx = Double.NaN, lastCy = Double.NaN, lastZoom = Double.NaN;
    private int lastRev = -1, lastW = -1, lastH = -1, lastErase = -1;
    private int lastSanctuaryKey = -1;
    private List<Float> lastDraft = List.of();
    private UiTool lastTool;
    private int lastColor;
    private float lastStrokeWidth;

    // active is a supplier so the gold highlight follows `tool` live instead
    // of freezing at whatever was selected when the bar was last rebuilt.
    private record Tb(String label, int x, int y, int w, int h,
                      Runnable action, BooleanSupplier active) { }
    private final List<Tb> buttons = new ArrayList<>();
    private final List<int[]> swatches = new ArrayList<>();   // [x,y,color]

    public WarTableScreen() {
        super(Text.literal("War Table"));
    }

    // ------------------------------------------------------------ setup ----

    @Override
    protected void init() {
        admin = WarTable.canDraw();
        toolbarAdmin = admin;
        LiveMap.ensureLoaded();
        Sanctuaries.ensureLoaded();
        MapProjection p = LiveMap.projection();
        if (!viewInitialized && p != null) initializeView(p);

        // Label input is always present; it only shows while LABEL or PIN is
        // armed and an anchor point has been clicked.
        labelField = new TextFieldWidget(textRenderer,
                (int) (width / 2) - 120, (int) height - STATUS_H - 24, 240, 16, Text.literal("label"));
        labelField.setMaxLength(Stroke.MAX_LABEL);
        labelField.setVisible(labeling);
        labelField.setPlaceholder(Text.literal(labelTool == UiTool.PIN
                ? "pin label…" : "label text…"));
        addDrawableChild(labelField);
        if (labeling) setFocused(labelField);
        rebuildToolbar();
    }

    private void initializeView(MapProjection p) {
        double requestedZoom = pendingWorldCenter ? zoom : minZoom;
        if (pendingWorldCenter) {
            centerPx = p.worldToPxX(pendingWorldX);
            centerPy = p.worldToPxZ(pendingWorldZ);
            pendingWorldCenter = false;
        } else {
            centerOnSelf(p);
        }
        minZoom = Math.min(width / (double) p.width(), height / (double) p.height()) * 0.92;
        zoom = Math.max(minZoom, Math.min(MAX_ZOOM, requestedZoom));
        viewInitialized = true;
    }

    private void centerOnSelf(MapProjection p) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.world != null
                && client.world.getRegistryKey().getValue().toString().equals("minecraft:overworld")) {
            centerPx = p.worldToPxX(client.player.getX());
            centerPy = p.worldToPxZ(client.player.getZ());
        } else {
            centerPx = p.width() / 2.0;
            centerPy = p.height() / 2.0;
        }
    }

    private void rebuildToolbar() {
        buttons.clear();
        swatches.clear();
        toolbarRenderer = LiveMap.renderer();
        int y = TOOL_ROW_Y;

        // Left edge: drawing tools + palette (admins only). Button widths use
        // the same clean-text metrics as the chrome renderer.
        int x = 6;
        if (admin) {
            for (UiTool t : UiTool.values()) {
                if (t == UiTool.ERASE) continue;      // Erase lives in the utility row
                int w = (int) Math.ceil(textWidth(t.label)) + 12;
                buttons.add(new Tb(t.label, x, y, w, 16,
                        () -> selectTool(t), () -> tool == t));
                x += w + 3;
            }
            x += 4;
            for (int c : PALETTE) {
                swatches.add(new int[]{x, y + 2, c});
                x += 14;
            }
        }

        // Second-row right edge: stable utility cluster under the status text.
        int rx = (int) width - 8;
        rx = addUtility(rx, y, "Center", () -> {
            MapProjection p = LiveMap.projection();
            if (p != null) { centerOnSelf(p); if (zoom < minZoom * 8) zoom = minZoom * 16; }
        }, () -> false);
        if (admin) {
            rx = addUtility(rx, y, "Clear", () -> WarTable.clearAll(), () -> false);
            rx = addUtility(rx, y, "Undo", () -> WarTable.undoOwn(), () -> false);
            rx = addUtility(rx, y, UiTool.ERASE.label, () -> selectTool(UiTool.ERASE),
                    () -> tool == UiTool.ERASE);
        }
        rx = addUtility(rx, y, "Waypoints", () -> {
            var cfg = OpenIntelClient.config();
            if (cfg != null) {
                cfg.warTablePinMarkers = !cfg.warTablePinMarkers;
                cfg.save();
            }
        }, () -> OpenIntelClient.config() != null
                && OpenIntelClient.config().warTablePinMarkers);
        rx = addUtility(rx, y, "Sanct", () -> {
            var cfg = OpenIntelClient.config();
            if (cfg != null) {
                cfg.warTableSanctuaries = !cfg.warTableSanctuaries;
                cfg.save();
            }
        }, () -> OpenIntelClient.config() != null
                && OpenIntelClient.config().warTableSanctuaries);
        addUtility(rx, y, "Map: " + LiveMap.rendererLabel(LiveMap.renderer()),
                () -> selectNextRenderer(1), () -> false);
    }

    private void selectTool(UiTool next) {
        tool = next;
        if (labeling && next != labelTool) endLabeling();
    }

    /** Adds a right-anchored toolbar button; returns the next slot's right edge. */
    private int addUtility(int rightEdge, int y, String label,
                           Runnable action, BooleanSupplier active) {
        int w = (int) Math.ceil(textWidth(label)) + 12;
        int bx = rightEdge - w;
        buttons.add(new Tb(label, bx, y, w, 16, action, active));
        return bx - 3;
    }

    private void selectNextRenderer(int direction) {
        MapProjection old = LiveMap.projection();
        if (old != null) {
            pendingWorldX = old.pxToWorldX(centerPx);
            pendingWorldZ = old.pxToWorldZ(centerPy);
            pendingWorldCenter = true;
        }
        LiveMap.cycleRenderer(direction);
        clearTileTextures();
        viewInitialized = false;
        rebuildToolbar();
    }

    private void clearTileTextures() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (Identifier id : tileTextures.values()) tm.destroyTexture(id);
        tileTextures.clear();
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Full-dark backdrop; no blur pass (in-world screens must not blur).
        ctx.fill(0, 0, (int) width, (int) height, 0xFF0A0E14);
    }

    // ----------------------------------------------------------- render ----

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        LiveMap.ensureLoaded();
        Sanctuaries.ensureLoaded();
        MapProjection proj = LiveMap.projection();
        admin = WarTable.canDraw();
        if (buttons.isEmpty() || admin != toolbarAdmin
                || !LiveMap.renderer().equals(toolbarRenderer)) {
            toolbarAdmin = admin;
            rebuildToolbar();
        }

        if (proj != null && !viewInitialized) initializeView(proj);

        if (LiveMap.state() == LiveMap.State.READY && proj != null) {
            // Eraser + sanctuary hover highlights track the cursor each frame.
            eraseHoverId = (tool == UiTool.ERASE && admin)
                    ? (WarTable.nearStroke(worldX(proj, mouseX), worldZ(proj, mouseY),
                            14 / zoom) instanceof Stroke s ? s.id : null)
                    : null;
            hoveredSanctuary = mouseY > TOOLBAR_H && mouseY < height - STATUS_H
                    ? sanctuaryAt(proj, mouseX, mouseY) : null;
            renderTiles(ctx, proj);
            renderStrokes(ctx, proj, mouseX, mouseY);
            renderOverlays(ctx, proj);
            renderSanctuaryTooltip(ctx, mouseX, mouseY);
        } else {
            drawCentered(ctx, switch (LiveMap.state()) {
                case LOADING -> "downloading map image…";
                case FAILED -> "map unavailable: " + LiveMap.error();
                case EMPTY -> "preparing map…";
                default -> "";
            }, width / 2f, height / 2f - 4, 0xFFAAAAAA);
        }

        // Chrome text uses the same clean HUD text path as the other OpenIntel
        // surfaces; map labels use the explicit CleanFont fallback helpers.
        UiFont.withHudFont(() -> renderChrome(ctx, mouseX, mouseY, proj));
        UiFont.withHudFont(() -> super.render(ctx, mouseX, mouseY, delta));
    }

    /** Visible tile grid at the chosen pyramid level. */
    private void renderTiles(DrawContext ctx, MapProjection proj) {
        int level = pickLevel();
        double tileSpan = LiveMap.TILE * LiveMap.levelScale(level);   // map px per tile
        double tileScreen = tileSpan * zoom;                          // screen px per tile

        int tx0 = (int) Math.floor((centerPx - width / (2 * zoom)) / tileSpan) - 1;
        int tx1 = (int) Math.floor((centerPx + width / (2 * zoom)) / tileSpan) + 1;
        int ty0 = (int) Math.floor((centerPy - height / (2 * zoom)) / tileSpan) - 1;
        int ty1 = (int) Math.floor((centerPy + height / (2 * zoom)) / tileSpan) + 1;
        int cols = LiveMap.tileCount(level, true), rows = LiveMap.tileCount(level, false);
        int levelW = LiveMap.levelSize(level, true), levelH = LiveMap.levelSize(level, false);

        for (int ty = Math.max(0, ty0); ty <= Math.min(rows - 1, ty1); ty++) {
            for (int tx = Math.max(0, tx0); tx <= Math.min(cols - 1, tx1); tx++) {
                // Tile origins are in map px, not world coords — go straight
                // through the view transform (screenX would add minX back in).
                double sx = (tx * tileSpan - centerPx) * zoom + width / 2.0;
                double sy = (ty * tileSpan - centerPy) * zoom + height / 2.0;
                // Edge tiles hold less than TILE px of content; only sample and
                // draw the real extent so the map edge isn't stretched.
                int rw = Math.min(LiveMap.TILE, levelW - tx * LiveMap.TILE);
                int rh = Math.min(LiveMap.TILE, levelH - ty * LiveMap.TILE);
                if (rw <= 0 || rh <= 0) continue;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                int x1 = (int) Math.floor(sx + tileScreen * rw / LiveMap.TILE);
                int y1 = (int) Math.floor(sy + tileScreen * rh / LiveMap.TILE);
                Identifier id = tileTexture(level, tx, ty);
                if (id != null) {
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id,
                            x0, y0, 0f, 0f, x1 - x0, y1 - y0,
                            rw, rh, LiveMap.TILE, LiveMap.TILE);
                } else {
                    ctx.fill(x0, y0, x1, y1, 0xFF141A22);
                }
            }
        }
    }

    private int pickLevel() {
        if (zoom >= 1.4) return 0;
        int level = (int) Math.ceil(-Math.log(zoom) / Math.log(2) - 0.35);
        return Math.max(0, Math.min(LiveMap.maxLevel(), level));
    }

    /** Lazily upload a tile PNG to a GPU texture; LRU-evicts old tiles. */
    private Identifier tileTexture(int level, int tx, int ty) {
        String key = LiveMap.renderer() + "/" + level + "/" + tx + "_" + ty;
        Identifier existing = tileTextures.get(key);
        if (existing != null) return existing;
        if (!LiveMap.tileExists(level, tx, ty)) return null;

        Identifier id = Identifier.of("openintel", "livemap/" + LiveMap.renderer()
                + "/l" + level + "/" + tx + "_" + ty);
        try (InputStream in = Files.newInputStream(LiveMap.tileFile(level, tx, ty))) {
            NativeImage image = NativeImage.read(in);
            NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "wartable-tile", image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
            tileTextures.put(key, id);
            while (tileTextures.size() > MAX_TEXTURES) {
                Iterator<Map.Entry<String, Identifier>> it = tileTextures.entrySet().iterator();
                if (!it.hasNext()) break;
                Identifier evict = it.next().getValue();
                it.remove();
                MinecraftClient.getInstance().getTextureManager().destroyTexture(evict);
            }
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------- strokes ----

    private void renderStrokes(DrawContext ctx, MapProjection proj,
                               int mouseX, int mouseY) {
        rebuildStrokeOverlay(proj);
        if (strokeTexId != null) {
            int w = (int) width, h = (int) height;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, strokeTexId,
                    0, 0, 0f, 0f, w, h, w, h, w, h);
        }

        renderSanctuaryLabels(ctx, proj);

        // Point labels draw through the clean text path on top of the texture.
        for (Stroke s : WarTable.strokes()) {
            if (s.tool != Stroke.Tool.LABEL && s.tool != Stroke.Tool.MARKER) continue;
            double sx = screenX(proj, s.points[0]);
            double sy = screenY(proj, s.points[1]);
            if (sx < -80 || sy < -20 || sx > width + 80 || sy > height + 20) continue;
            if (s.tool == Stroke.Tool.LABEL) {
                double sc = Math.min(2.0, Math.max(0.75, zoom));
                ctx.getMatrices().pushMatrix();
                ctx.getMatrices().translate((float) sx, (float) sy);
                ctx.getMatrices().scale((float) sc, (float) sc);
                drawCentered(ctx, s.label, 0, -12, s.color | 0xFF000000);
                if (s.author != null) {
                    drawCentered(ctx, "— " + s.author, 0, -3, 0x99FFFFFF);
                }
                ctx.getMatrices().popMatrix();
            } else if (s.label != null) {
                drawCentered(ctx, s.label, (float) sx, (float) sy - 14,
                        s.color | 0xFF000000);
            }
        }
        renderPointDraft(ctx, proj);
    }

    private void renderSanctuaryLabels(DrawContext ctx, MapProjection proj) {
        if (!showSanctuaries()) return;
        for (Sanctuaries.Sanctuary s : Sanctuaries.all()) {
            if (!s.overworld()) continue;
            double sx = screenX(proj, s.x()), sy = screenY(proj, s.z());
            double r = s.radius() / proj.scale() * zoom;
            if (r < 14 || sx < -r || sy < -r || sx > width + r || sy > height + r) continue;
            String label = s.name();
            float maxW = (float) (r * 1.6);
            float scale = sanctuaryLabelScale(label, maxW, r);
            if (scale <= 0) {
                label = Sanctuaries.acronym(label);
                scale = sanctuaryLabelScale(label, maxW, r);
            }
            if (scale <= 0) continue;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate((float) sx, (float) sy);
            ctx.getMatrices().scale(scale, scale);
            drawCentered(ctx, label, 0, -textHeight() / 2f, 0xE0101010);
            ctx.getMatrices().popMatrix();
        }
    }

    private float sanctuaryLabelScale(String label, float maxW, double radius) {
        float tw = textWidth(label);
        float byWidth = maxW / Math.max(1f, tw);
        float byHeight = (float) (radius * 0.7 / textHeight());
        float scale = Math.min(1.6f, Math.min(byWidth, byHeight));
        return scale >= 0.45f ? scale : 0;
    }

    /** Pending label/pin input shows at the clicked point instead of the bottom. */
    private void renderPointDraft(DrawContext ctx, MapProjection proj) {
        if (!labeling || labelTool == null) return;
        double sx = screenX(proj, labelAnchorX), sy = screenY(proj, labelAnchorZ);
        String text = labelField.getText().trim();
        if (labelTool == UiTool.PIN) {
            ctx.fill((int) sx - 4, (int) sy - 4, (int) sx + 5, (int) sy + 5, 0xAA000000);
            ctx.fill((int) sx - 3, (int) sy - 3, (int) sx + 4, (int) sy + 4, color);
            drawCentered(ctx, text.isEmpty() ? "Pin" : text,
                    (float) sx, (float) sy - 14, color | 0xFF000000);
        } else if (!text.isEmpty()) {
            drawCentered(ctx, text, (float) sx, (float) sy - 12, color | 0xFF000000);
        }
    }

    private boolean cleanText(String text) {
        return CleanFont.active() && CleanFont.supports(text);
    }

    private float textWidth(String text) {
        return cleanText(text) ? CleanFont.width(text) : textRenderer.getWidth(text);
    }

    private float textHeight() {
        return CleanFont.active() ? CleanFont.LINE_H - 2f : textRenderer.fontHeight;
    }

    private void drawText(DrawContext ctx, String text, float x, float y, int color) {
        if (cleanText(text)) CleanFont.draw(ctx, text, x, y, color, false);
        else ctx.drawText(textRenderer, text, Math.round(x), Math.round(y), color, true);
    }

    private void drawCentered(DrawContext ctx, String text, float cx, float y, int color) {
        if (cleanText(text)) CleanFont.drawCentered(ctx, text, cx, y, color);
        else ctx.drawCenteredTextWithShadow(textRenderer, text, Math.round(cx), Math.round(y), color);
    }

    /** Filled diamond at a map point — same marker language as JourneyMap waypoints. */
    private void drawDiamond(DrawContext ctx, int cx, int cy, int r, int argb) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            ctx.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, argb);
        }
    }

    /** Dark translucent plate under the label — readable over any terrain, no outline halo. */
    private void drawPlatedCentered(DrawContext ctx, String text, float cx, float y, int color) {
        float w = textWidth(text);
        int alpha = (color >>> 24) & 0xFF;
        int plate = Math.clamp(Math.round(alpha * 0.75f), 0, 0xD0) << 24 | 0x0A0C12;
        int x0 = Math.round(cx - w / 2f) - 3, x1 = Math.round(cx + w / 2f) + 3;
        int y0 = Math.round(y) - 2, y1 = Math.round(y + textHeight()) + 1;
        ctx.fill(x0, y0, x1, y1, plate);
        drawCentered(ctx, text, cx, y, color);
    }

    /**
     * Rasterize every stroke into a screen-size overlay texture. Rebuilt only
     * when the view, stroke set, or draft changes — panning/zooming and
     * drawing trigger rebuilds, idle frames reuse the texture.
     */
    private void rebuildStrokeOverlay(MapProjection proj) {
        int w = (int) width, h = (int) height;
        if (w <= 0 || h <= 0) return;
        int eraseKey = eraseHoverId == null ? -1 : eraseHoverId.hashCode();
        int sanctuaryKey = showSanctuaries()
                ? Sanctuaries.revision() * 31 + System.identityHashCode(hoveredSanctuary)
                : -1;
        if (strokeTexId != null && w == lastW && h == lastH
                && centerPx == lastCx && centerPy == lastCy && zoom == lastZoom
                && WarTable.revision() == lastRev && !draftChanged()
                && eraseKey == lastErase && sanctuaryKey == lastSanctuaryKey) return;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            if (showSanctuaries()) paintSanctuariesG(g, proj);
            for (Stroke s : WarTable.strokes()) paintStrokeG(g, proj, s, false);
            if (!draft.isEmpty()) {
                float[] pts = toArray(draft);
                Stroke.Tool t = tool == UiTool.ARROW ? Stroke.Tool.ARROW
                        : tool == UiTool.LINE ? Stroke.Tool.LINE : Stroke.Tool.PEN;
                paintStrokeG(g, proj, new Stroke("draft", t, color, strokeWidth,
                        pts, null, null, 0), true);
            }
        } finally {
            g.dispose();
        }

        NativeImage nimg = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) nimg.setColorArgb(x, y, img.getRGB(x, y));
        }
        if (strokeTex == null || strokeTex.getImage().getWidth() != w
                || strokeTex.getImage().getHeight() != h) {
            if (strokeTexId != null) {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(strokeTexId);
            }
            strokeTex = new NativeImageBackedTexture(() -> "wartable-strokes", nimg);
            strokeTexId = Identifier.of("openintel", "wartable/strokes");
            MinecraftClient.getInstance().getTextureManager().registerTexture(strokeTexId, strokeTex);
        } else {
            strokeTex.setImage(nimg);
            strokeTex.upload();
        }
        lastCx = centerPx; lastCy = centerPy; lastZoom = zoom;
        lastRev = WarTable.revision(); lastW = w; lastH = h;
        lastDraft = List.copyOf(draft); lastErase = eraseKey;
        lastSanctuaryKey = sanctuaryKey;
        lastTool = tool; lastColor = color; lastStrokeWidth = strokeWidth;
    }

    private boolean showSanctuaries() {
        var cfg = OpenIntelClient.config();
        return cfg == null || cfg.warTableSanctuaries;
    }

    private void paintSanctuariesG(Graphics2D g, MapProjection proj) {
        for (Sanctuaries.Sanctuary s : Sanctuaries.all()) {
            if (!s.overworld()) continue;
            double cx = screenX(proj, s.x()), cy = screenY(proj, s.z());
            double r = s.radius() / proj.scale() * zoom;
            if (r < 1 || cx + r < -50 || cx - r > width + 50
                    || cy + r < -50 || cy - r > height + 50) continue;
            boolean hot = s == hoveredSanctuary;
            g.setColor(new Color(hot ? 0xB3B8860B : 0x80B8860B, true));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            g.setStroke(new BasicStroke(hot ? 2f : 1.5f));
            g.setColor(new Color(hot ? 0xCCB8860B : 0x8CB8860B, true));
            g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        }
    }

    private Sanctuaries.Sanctuary sanctuaryAt(MapProjection proj,
                                               double sx, double sy) {
        if (!showSanctuaries()) return null;
        List<Sanctuaries.Sanctuary> all = Sanctuaries.all();
        for (int i = all.size() - 1; i >= 0; i--) {
            Sanctuaries.Sanctuary s = all.get(i);
            if (!s.overworld()) continue;
            double cx = screenX(proj, s.x()), cy = screenY(proj, s.z());
            double r = s.radius() / proj.scale() * zoom;
            double dx = sx - cx, dy = sy - cy;
            if (dx * dx + dy * dy <= r * r) return s;
        }
        return null;
    }

    private boolean draftChanged() {
        return !lastDraft.equals(draft) || lastTool != tool
                || lastColor != color || lastStrokeWidth != strokeWidth;
    }

    /** Paint one stroke into the overlay in screen space. */
    private void paintStrokeG(Graphics2D g, MapProjection proj, Stroke s, boolean preview) {
        int pairs = s.points.length / 2;
        if (pairs == 0) return;
        int c = preview ? withAlpha(s.color, 190) : s.color;
        if (eraseHoverId != null && eraseHoverId.equals(s.id)) c = 0xFFFFFFFF;

        float wpx = Math.max(2.4f, (float) (s.width * zoom / proj.scale()));
        Path2D.Double path = null;
        if (s.tool == Stroke.Tool.PEN || s.tool == Stroke.Tool.LINE
                || s.tool == Stroke.Tool.ARROW || s.tool == Stroke.Tool.AREA) {
            path = new Path2D.Double();
            path.moveTo(screenX(proj, s.points[0]), screenY(proj, s.points[1]));
            for (int i = 1; i < pairs; i++) {
                path.lineTo(screenX(proj, s.points[i * 2]), screenY(proj, s.points[i * 2 + 1]));
            }
            if (s.tool == Stroke.Tool.AREA) path.closePath();
        }

        switch (s.tool) {
            case PEN, LINE -> drawPath(g, path, wpx, c);
            case ARROW -> {
                drawPath(g, path, wpx, c);
                arrowHeadG(g, proj, s.points, pairs, wpx, c);
            }
            case AREA -> {
                g.setColor(new Color(0x44 << 24 | (c & 0x00FFFFFF), true));
                g.fill(path);
                drawPath(g, path, Math.max(2f, wpx * 0.5f), c);
            }
            case LABEL, MARKER -> {
                double cx = screenX(proj, s.points[0]), cy = screenY(proj, s.points[1]);
                g.setColor(new Color(0x88000000, true));
                g.fill(new Ellipse2D.Double(cx - 6, cy - 6, 12, 12));
                g.setColor(new Color(c, true));
                g.fill(new Ellipse2D.Double(cx - 4.5, cy - 4.5, 9, 9));
            }
        }
    }

    /** Halo + core — keeps strokes readable over the light hillshade. */
    private void drawPath(Graphics2D g, Path2D path, float wpx, int argb) {
        g.setStroke(new BasicStroke(wpx + 2.6f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x66000000, true));
        g.draw(path);
        g.setStroke(new BasicStroke(wpx, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.setColor(new Color(argb, true));
        g.draw(path);
    }

    private void arrowHeadG(Graphics2D g, MapProjection proj, float[] pts,
                            int pairs, float wpx, int argb) {
        if (pairs < 2) return;
        int a = (pairs - 2) * 2, b = (pairs - 1) * 2;
        while (a > 0 && pts[a] == pts[b] && pts[a + 1] == pts[b + 1]) a -= 2;
        double ax = screenX(proj, pts[a]), ay = screenY(proj, pts[a + 1]);
        double bx = screenX(proj, pts[b]), by = screenY(proj, pts[b + 1]);
        double dx = bx - ax, dy = by - ay;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        double ux = dx / len, uy = dy / len;
        double head = Math.max(8, wpx * 2.6);
        double wx = -uy * head * 0.45, wy = ux * head * 0.45;
        Path2D.Double tri = new Path2D.Double();
        tri.moveTo(bx, by);
        tri.lineTo(bx - ux * head + wx, by - uy * head + wy);
        tri.lineTo(bx - ux * head - wx, by - uy * head - wy);
        tri.closePath();
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(0x66000000, true));
        g.draw(tri);
        g.setColor(new Color(argb, true));
        g.fill(tri);
    }

    private void renderSanctuaryTooltip(DrawContext ctx, int mouseX, int mouseY) {
        Sanctuaries.Sanctuary s = hoveredSanctuary;
        if (s == null) return;
        String title = s.name();
        String sub = "Radius: " + Math.round(s.radius()) + " blocks";
        int boxW = (int) Math.ceil(Math.max(textWidth(title), textWidth(sub))) + 10;
        int boxH = (int) (textHeight() * 2 + 9);
        int x = Math.max(4, Math.min((int) width - boxW - 4, mouseX + 14));
        int y = Math.max(TOOLBAR_H + 4,
                Math.min((int) height - STATUS_H - boxH - 4, mouseY + 10));
        ctx.fill(x - 4, y - 4, x + boxW - 4, y + boxH - 4, 0xE0080C12);
        ctx.drawStrokedRectangle(x - 4, y - 4, boxW, boxH, 0xAAFFAA00);
        drawText(ctx, title, x, y, 0xFFFFD27A);
        drawText(ctx, sub, x, y + textHeight() + 2, 0xFFDDDDDD);
    }

    // ----------------------------------------------------- intel overlay ---

    private void renderOverlays(DrawContext ctx, MapProjection proj) {
        if (client == null) return;
        // Shared pings first (under player markers).
        for (PingManager.Ping p : PingManager.active()) {
            if (p.dimension != null && !p.dimension.equals("minecraft:overworld")) continue;
            double sx = screenX(proj, p.x), sy = screenY(proj, p.z);
            if (sx < -20 || sy < -20 || sx > width + 20 || sy > height + 20) continue;
            int d = 4;
            ctx.fill((int) sx - 1, (int) sy - d, (int) sx + 2, (int) sy - d + 3, p.color);
            ctx.fill((int) sx - 1, (int) sy + d - 2, (int) sx + 2, (int) sy + d + 1, p.color);
            ctx.fill((int) sx - d, (int) sy - 1, (int) sx - d + 3, (int) sy + 2, p.color);
            ctx.fill((int) sx + d - 2, (int) sy - 1, (int) sx + d + 1, (int) sy + 2, p.color);
            ctx.fill((int) sx - 1, (int) sy - 1, (int) sx + 2, (int) sy + 2, p.color | 0xFF000000);
            drawCentered(ctx, p.label, (float) sx, (float) sy - 14,
                    p.color | 0xFF000000);
        }
        // Snitch hits use the same warning-marker language as the world HUD:
        // ⚠ over a fading "snitch | tripper | age" line.
        var cfg = OpenIntelClient.config();
        if (cfg != null) {
            long now = System.currentTimeMillis();
            long snitchLife = cfg.snitchMarkerSeconds * 1000L;
            float baseAlpha = 1f;
            for (Tracker.SnitchHit hit : OpenIntelClient.tracker().snitchHits()) {
                if (!"minecraft:overworld".equals(hit.dimension)) continue;
                float fade = 1f - Math.max(0, now - hit.t) / (float) snitchLife;
                if (fade <= 0.03f) continue;
                double sx = screenX(proj, hit.x), sy = screenY(proj, hit.z);
                if (sx < -80 || sy < -24 || sx > width + 80 || sy > height + 24) continue;
                int rgb = (cfg.snitchMarkerColorAuto || cfg.snitchMarkerColor == -1)
                        ? OpenIntelClient.allegiances().of(hit.player).argb
                        : cfg.snitchMarkerColor;
                int argb = scaleAlpha(rgb, baseAlpha * fade);
                int ix = (int) Math.round(sx), iy = (int) Math.round(sy);
                drawDiamond(ctx, ix, iy, 4, argb);
                drawPlatedCentered(ctx, hit.snitch + " | " + hit.player + " | " + ago(now - hit.t),
                        (float) sx, iy + 6, argb);
            }
        }
        // Relay-tracked players in the overworld.
        String self = client.player != null ? client.player.getGameProfile().name() : "";
        for (Tracker.RemotePlayer p : OpenIntelClient.tracker().all()) {
            if (p.dimension == null || !p.dimension.equals("minecraft:overworld")) continue;
            double sx = screenX(proj, p.x), sy = screenY(proj, p.z);
            if (sx < -40 || sy < -20 || sx > width + 40 || sy > height + 20) continue;
            int c = p.allegiance.argb;
            ctx.fill((int) sx - 3, (int) sy - 3, (int) sx + 4, (int) sy + 4, 0xCC000000);
            ctx.fill((int) sx - 2, (int) sy - 2, (int) sx + 3, (int) sy + 3, c);
            drawCentered(ctx, p.name, (float) sx, (float) sy + 6, c);
        }
        // Own position — bright marker.
        if (client.player != null && client.world != null
                && client.world.getRegistryKey().getValue().toString().equals("minecraft:overworld")) {
            double sx = screenX(proj, client.player.getX());
            double sy = screenY(proj, client.player.getZ());
            ctx.fill((int) sx - 3, (int) sy - 3, (int) sx + 4, (int) sy + 4, 0xFF101010);
            ctx.fill((int) sx - 2, (int) sy - 2, (int) sx + 3, (int) sy + 3, 0xFFFFFFFF);
            drawCentered(ctx, self, (float) sx, (float) sy + 6, 0xFFFFFFFF);
        }
    }

    // ----------------------------------------------------------- chrome ----

    private void renderChrome(DrawContext ctx, int mouseX, int mouseY, MapProjection proj) {
        // Top bar.
        ctx.fill(0, 0, (int) width, TOOLBAR_H + 2, 0xC0080C12);
        boolean live = WarTable.live();
        String status = live ? (admin ? "ADMIN" : "LIVE") : "LOCAL";
        int statusColor = live ? 0xFF55FF55 : 0xFFFFAA00;
        drawText(ctx, status, width - textWidth(status) - 8, 8, statusColor);

        for (Tb b : buttons) {
            boolean active = b.active().getAsBoolean();
            int bg = active ? 0xFF3A4A5E : 0xFF1C2530;
            boolean hot = mouseX >= b.x() && mouseX < b.x() + b.w()
                    && mouseY >= b.y() && mouseY < b.y() + b.h();
            if (hot) bg = 0xFF506880;
            ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), bg);
            ctx.drawStrokedRectangle(b.x(), b.y(), b.w(), b.h(),
                    active ? 0xFFFFAA00 : (hot ? 0xFF9FB4C8 : 0xFF3A4652));
            drawText(ctx, b.label(), b.x() + 6, b.y() + 4,
                    active ? 0xFFFFAA00 : 0xFFDDDDDD);
        }
        for (int[] sw : swatches) {
            boolean hot = mouseX >= sw[0] && mouseX < sw[0] + 12
                    && mouseY >= sw[1] && mouseY < sw[1] + 12;
            ctx.fill(sw[0], sw[1], sw[0] + 12, sw[1] + 12, sw[2]);
            ctx.drawStrokedRectangle(sw[0], sw[1], 12, 12,
                    color == sw[2] ? 0xFFFFFFFF : (hot ? 0xAAFFFFFF : 0x55000000));
        }

        // The title has its own first row now; draw it last as a second guard.
        drawCentered(ctx, "WAR TABLE", width / 2f, 8, 0xFFFFAA00);

        // Bottom status bar.
        ctx.fill(0, (int) (height - STATUS_H), (int) width, (int) height, 0xC0080C12);
        StringBuilder info = new StringBuilder();
        if (proj != null) {
            double wx = proj.pxToWorldX((mouseX - width / 2.0) / zoom + centerPx);
            double wz = proj.pxToWorldZ((mouseY - height / 2.0) / zoom + centerPy);
            info.append((int) wx).append(", ").append((int) wz);
        }
        info.append("   zoom ").append(String.format("%.2f", zoom)).append("x")
                .append("   ").append(WarTable.size()).append(" strokes")
                .append("   map: ").append(LiveMap.rendererLabel(LiveMap.renderer()));
        if (admin) info.append("   tool: ").append(tool.label);
        if (labeling) info.append("   [enter to place, esc to cancel]");
        else if (!draft.isEmpty()) info.append("   [release to finish, esc to cancel]");
        drawText(ctx, info.toString(), 8, height - STATUS_H + 4, 0xFFAAAAAA);
    }

    // ------------------------------------------------------------ input ----

    private double screenX(MapProjection p, double wx) {
        return (p.worldToPxX(wx) - centerPx) * zoom + width / 2.0;
    }

    private double screenY(MapProjection p, double wz) {
        return (p.worldToPxZ(wz) - centerPy) * zoom + height / 2.0;
    }

    private double worldX(MapProjection p, double sx) {
        return p.pxToWorldX((sx - width / 2.0) / zoom + centerPx);
    }

    private double worldZ(MapProjection p, double sy) {
        return p.pxToWorldZ((sy - height / 2.0) / zoom + centerPy);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        // Toolbar first.
        for (Tb b : buttons) {
            if (mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h()) {
                b.action().run();
                return true;
            }
        }
        for (int[] sw : swatches) {
            if (mx >= sw[0] && mx < sw[0] + 12 && my >= sw[1] && my < sw[1] + 12) {
                color = sw[2];
                return true;
            }
        }
        if (my < TOOLBAR_H + 2 || my > height - STATUS_H) return super.mouseClicked(click, doubled);
        if (labeling) {
            if (labelField.isMouseOver(mx, my)) return super.mouseClicked(click, doubled);
            endLabeling();
            return true;
        }

        MapProjection proj = LiveMap.projection();
        if (proj == null) return super.mouseClicked(click, doubled);
        double wx = worldX(proj, mx), wz = worldZ(proj, my);

        boolean canEdit = admin && !labeling;
        if (!canEdit || tool == UiTool.PAN || click.button() == 2 || click.button() == 1) {
            panning = true;
            panLastX = mx;
            panLastY = my;
            return true;
        }

        switch (tool) {
            case PEN, LINE, ARROW -> {
                dragging = true;
                draft.clear();
                draft.add((float) wx);
                draft.add((float) wz);
            }
            case LABEL, PIN -> beginLabeling(wx, wz, tool, mx, my);
            case ERASE -> {
                Stroke near = WarTable.nearStroke(wx, wz, 14 / zoom);
                if (near != null) WarTable.delete(near.id);
            }
            default -> { }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (panning) {
            centerPx -= (click.x() - panLastX) / zoom;
            centerPy -= (click.y() - panLastY) / zoom;
            panLastX = click.x();
            panLastY = click.y();
            return true;
        }
        if (dragging && !draft.isEmpty()) {
            MapProjection proj = LiveMap.projection();
            if (proj == null) return true;
            double wx = worldX(proj, click.x()), wz = worldZ(proj, click.y());
            int n = draft.size();
            double dx = wx - draft.get(n - 2), dz = wz - draft.get(n - 1);
            if (tool != UiTool.PEN || dx * dx + dz * dz > 9) {          // ≥3 blocks between samples
                updateDraftEndpoint(wx, wz);
            }
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    private void updateDraftEndpoint(double wx, double wz) {
        if (draft.size() < 2) return;
        if ((tool == UiTool.LINE || tool == UiTool.ARROW) && draft.size() >= 4) {
            draft.set(2, (float) wx);
            draft.set(3, (float) wz);
        } else {
            draft.add((float) wx);
            draft.add((float) wz);
        }
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (panning) { panning = false; return true; }
        if (dragging) {
            dragging = false;
            MapProjection proj = LiveMap.projection();
            if (proj == null || draft.size() < 2) { draft.clear(); return true; }
            double ex = worldX(proj, click.x()), ez = worldZ(proj, click.y());
            updateDraftEndpoint(ex, ez);
            float[] pts = toArray(draft);
            draft.clear();
            Stroke.Tool t = tool == UiTool.ARROW ? Stroke.Tool.ARROW
                    : tool == UiTool.LINE ? Stroke.Tool.LINE : Stroke.Tool.PEN;
            pts = Stroke.decimate(pts, 2.5);
            if (pts.length >= 4) WarTable.add(t, color, strokeWidth, pts, null);
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontal, double vertical) {
        MapProjection proj = LiveMap.projection();
        if (proj == null || vertical == 0) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        double pxBefore = (mouseX - width / 2.0) / zoom + centerPx;
        double pyBefore = (mouseY - height / 2.0) / zoom + centerPy;
        zoom = Math.max(minZoom, Math.min(MAX_ZOOM, zoom * Math.pow(1.15, vertical)));
        centerPx = pxBefore - (mouseX - width / 2.0) / zoom;
        centerPy = pyBefore - (mouseY - height / 2.0) / zoom;
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (labeling && input.getKeycode() == GLFW.GLFW_KEY_ENTER) {
            String text = labelField.getText().trim();
            Stroke.Tool committed = labelTool == UiTool.PIN
                    ? Stroke.Tool.MARKER : Stroke.Tool.LABEL;
            if (committed == Stroke.Tool.MARKER || !text.isEmpty()) {
                WarTable.add(committed, color, strokeWidth,
                        new float[]{(float) labelAnchorX, (float) labelAnchorZ},
                        text.isEmpty() ? null : text);
            }
            endLabeling();
            return true;
        }
        if (input.getKeycode() == GLFW.GLFW_KEY_Z && input.hasCtrlOrCmd()) {
            if (admin) WarTable.undoOwn();
            return true;
        }
        if (input.getKeycode() == GLFW.GLFW_KEY_ESCAPE) {
            if (labeling) { endLabeling(); return true; }
            if (!draft.isEmpty()) { draft.clear(); dragging = false; return true; }
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    private void beginLabeling(double wx, double wz, UiTool kind,
                               double mouseX, double mouseY) {
        labelAnchorX = wx;
        labelAnchorZ = wz;
        labelTool = kind;
        labeling = true;
        int fieldW = Math.min(240, Math.max(80, (int) width - 12));
        int fx = Math.clamp((int) mouseX + 10, 6, Math.max(6, (int) width - fieldW - 6));
        int fy = Math.clamp((int) mouseY - 24, TOOLBAR_H + 4,
                Math.max(TOOLBAR_H + 4, (int) height - STATUS_H - 24));
        labelField.setX(fx);
        labelField.setY(fy);
        labelField.setWidth(fieldW);
        labelField.setVisible(true);
        labelField.setText("");
        labelField.setPlaceholder(Text.literal(kind == UiTool.PIN
                ? "pin label…" : "label text…"));
        setFocused(labelField);
    }

    private void endLabeling() {
        labeling = false;
        labelTool = null;
        labelField.setVisible(false);
        setFocused(null);
    }

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static int withAlpha(int argb, int a) {
        return (argb & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    private static int scaleAlpha(int argb, float factor) {
        int alpha = Math.clamp(Math.round(((argb >>> 24) & 0xFF) * factor), 0, 255);
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    private static String ago(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        if (seconds < 10) return "now";
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + String.format("%02d", seconds % 60) + "s";
    }

    @Override
    public void removed() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (Identifier id : tileTextures.values()) tm.destroyTexture(id);
        tileTextures.clear();
        if (strokeTexId != null) {
            tm.destroyTexture(strokeTexId);
            strokeTexId = null;
            strokeTex = null;
        }
        WarTable.persist();
    }
}
