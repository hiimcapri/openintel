package dev.openintel.api.hud;

import dev.openintel.OpenIntelClient;
import dev.openintel.config.OIConfig;
import dev.openintel.gui.HudEditorScreen;
import dev.openintel.mixin.DrawContextAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class HudApi {
    public static final String COORDINATE_CONTRACT = "Positions are nonnegative top-left GUI-scaled pixels. "
            + "Stored positions are clamped against the current viewport and declared size at draw/hit-test time; "
            + "resizing does not rewrite them. Callbacks draw from local (0, 0), with the matrix already translated "
            + "and clipping restricted to the element and viewport. Do not add the stored position again.";
    public static final String THREADING_CONTRACT = "register, registration.close, elements, element, isEnabled and getPosition are thread-safe. "
            + "Off-thread reads return immutable last-synchronized snapshots. Registration may precede OpenIntel initialization. "
            + "All setters, reset, saveLayout and rendering require the Minecraft client thread, otherwise IllegalStateException. "
            + "Use MinecraftClient.execute to marshal mutations. setPosition saves unless persistImmediately is false; "
            + "call saveLayout at the end of a drag. Other mutations save automatically.";
    public static final String CALLBACK_CONTRACT = "Callbacks run on the client thread and must not retain the context. "
            + "Balance your own matrix/scissor pushes and pops; never remove the API's base clip. "
            + "Preview callbacks are optional and run even when disabled; absent previews use the editor's labeled box, "
            + "not the runtime callback. A callback failure is logged once and suppresses both callbacks until reset "
            + "or unregister/re-register. This suppression does not change the persisted enabled preference.";

    private static final HudApi INSTANCE = new HudApi();
    private static final Logger LOGGER = LoggerFactory.getLogger("OpenIntel/HudApi");
    private final Map<Identifier, Entry> entries = new LinkedHashMap<>();
    private boolean pendingSave;
    private boolean clearOnBind;
    private boolean rendering;

    private HudApi() {
    }

    public static HudApi getInstance() {
        return INSTANCE;
    }

    public HudRegistration register(Identifier id, String name, HudSize size, HudPosition defaultPosition,
                                    HudRenderer renderer) {
        return register(id, name, size, defaultPosition, renderer, null);
    }

    public synchronized HudRegistration register(Identifier id, String name, HudSize size,
                                                 HudPosition defaultPosition, HudRenderer renderer,
                                                 HudRenderer previewRenderer) {
        validateId(id);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(defaultPosition, "defaultPosition");
        Objects.requireNonNull(renderer, "renderer");
        if (name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("HUD name must be 1-128 visible characters without control characters");
        }
        if (entries.containsKey(id)) throw new IllegalArgumentException("HUD ID is already registered: " + id);
        Entry entry = new Entry(id, name.strip(), size, defaultPosition, renderer, previewRenderer);
        entries.put(id, entry);
        return new HudRegistration() {
            @Override
            public Identifier id() {
                return id;
            }

            @Override
            public void close() {
                synchronized (HudApi.this) {
                    entries.remove(id, entry);
                }
            }
        };
    }

    public synchronized List<HudElementDescriptor> elements() {
        synchronizeConfigIfClientThread();
        return entries.values().stream().map(Entry::descriptor).toList();
    }

    public synchronized Optional<HudElementDescriptor> element(Identifier id) {
        validateId(id);
        synchronizeConfigIfClientThread();
        Entry entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor());
    }

    public synchronized boolean isEnabled(Identifier id) {
        synchronizeConfigIfClientThread();
        return requireEntry(id).enabled;
    }

    public synchronized HudPosition getPosition(Identifier id) {
        synchronizeConfigIfClientThread();
        return requireEntry(id).position;
    }

    public void setPosition(Identifier id, HudPosition position) {
        setPosition(id, position, true);
    }

    public synchronized void setPosition(Identifier id, HudPosition position, boolean persistImmediately) {
        requireClientThread();
        Objects.requireNonNull(position, "position");
        synchronizeConfigIfClientThread();
        Entry entry = requireEntry(id);
        entry.position = position;
        entry.positionChanged = true;
        store(entry);
        if (persistImmediately) saveLayout();
    }

    public synchronized void setEnabled(Identifier id, boolean enabled) {
        requireClientThread();
        synchronizeConfigIfClientThread();
        Entry entry = requireEntry(id);
        entry.enabled = enabled;
        entry.enabledChanged = true;
        store(entry);
        saveLayout();
    }

    public synchronized void reset(Identifier id) {
        requireClientThread();
        synchronizeConfigIfClientThread();
        resetEntry(requireEntry(id));
        saveLayout();
    }

    public synchronized void resetAll() {
        requireClientThread();
        synchronizeConfigIfClientThread();
        OIConfig config = OpenIntelClient.config();
        if (config == null) clearOnBind = true;
        else config.externalHudElements.clear();
        for (Entry entry : entries.values()) resetEntry(entry);
        saveLayout();
    }

    public synchronized void saveLayout() {
        requireClientThread();
        synchronizeConfigIfClientThread();
        OIConfig config = OpenIntelClient.config();
        if (config == null) pendingSave = true;
        else config.save();
    }

    public void renderAll(DrawContext context, float tickDelta) {
        MinecraftClient client = requireClientThread();
        Objects.requireNonNull(context, "context");
        if (rendering || client.currentScreen instanceof HudEditorScreen || client.options.hudHidden) return;
        List<RenderJob> jobs = new ArrayList<>();
        synchronized (this) {
            synchronizeConfigIfClientThread();
            for (Entry entry : entries.values()) {
                if (entry.enabled && !entry.failed) jobs.add(new RenderJob(entry, entry.descriptor(), entry.renderer, false));
            }
        }
        for (RenderJob job : jobs) {
            invoke(job, context, context.getScaledWindowWidth(), context.getScaledWindowHeight(), tickDelta);
        }
    }

    public void renderPreview(Identifier id, DrawContext context, int viewportWidth, int viewportHeight, float tickDelta) {
        requireClientThread();
        validateId(id);
        Objects.requireNonNull(context, "context");
        if (viewportWidth < 0 || viewportHeight < 0) throw new IllegalArgumentException("Viewport dimensions must be nonnegative");
        if (rendering) return;
        RenderJob job;
        synchronized (this) {
            synchronizeConfigIfClientThread();
            Entry entry = entries.get(id);
            if (entry == null || entry.failed || entry.previewRenderer == null) return;
            job = new RenderJob(entry, entry.descriptor(), entry.previewRenderer, true);
        }
        invoke(job, context, viewportWidth, viewportHeight, tickDelta);
    }

    private void invoke(RenderJob job, DrawContext context, int viewportWidth, int viewportHeight, float tickDelta) {
        synchronized (this) {
            if (entries.get(job.entry.id) != job.entry || job.entry.failed) return;
            if (!job.preview && !job.entry.enabled) return;
        }
        if (viewportWidth <= 0 || viewportHeight <= 0) return;
        HudPosition origin = job.descriptor.position().clamp(job.descriptor.size(), viewportWidth, viewportHeight);
        rendering = true;
        try (ElementDrawContext local = new ElementDrawContext(context, origin, job.descriptor.size(), viewportWidth, viewportHeight)) {
            job.renderer.render(local, job.descriptor.size(), tickDelta);
            local.drawDeferredElements();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            synchronized (this) {
                if (!job.entry.failed) {
                    job.entry.failed = true;
                    LOGGER.error("External HUD {} failed; callbacks disabled until reset or re-registration", job.entry.id, failure);
                }
            }
        } finally {
            rendering = false;
        }
    }

    private void synchronizeConfigIfClientThread() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) return;
        OIConfig config = OpenIntelClient.config();
        if (config == null) return;
        if (config.externalHudElements == null) config.repairExternalHudElements();
        if (clearOnBind) {
            config.externalHudElements.clear();
            clearOnBind = false;
        }
        for (Entry entry : entries.values()) {
            if (entry.boundConfig == config) continue;
            OIConfig.ExternalHudState state = config.externalHudElements.get(entry.id.toString());
            if (state != null) {
                if (!entry.positionChanged) {
                    entry.position = new HudPosition(state.x == null || state.x < 0 ? entry.defaultPosition.x() : state.x,
                            state.y == null || state.y < 0 ? entry.defaultPosition.y() : state.y);
                }
                if (!entry.enabledChanged) entry.enabled = state.enabled == null || state.enabled;
            }
            entry.boundConfig = config;
            store(entry);
        }
        if (pendingSave) {
            pendingSave = false;
            config.save();
        }
    }

    private void store(Entry entry) {
        if (entry.boundConfig == null) return;
        entry.boundConfig.externalHudElements.put(entry.id.toString(),
                new OIConfig.ExternalHudState(entry.position.x(), entry.position.y(), entry.enabled));
        entry.positionChanged = false;
        entry.enabledChanged = false;
    }

    private void resetEntry(Entry entry) {
        entry.position = entry.defaultPosition;
        entry.enabled = true;
        entry.failed = false;
        entry.positionChanged = true;
        entry.enabledChanged = true;
        store(entry);
    }

    private Entry requireEntry(Identifier id) {
        validateId(id);
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("HUD ID is not registered: " + id);
        return entry;
    }

    private static void validateId(Identifier id) {
        Objects.requireNonNull(id, "id");
        if (id.getNamespace().equals("openintel")) throw new IllegalArgumentException("The openintel namespace is reserved");
        if (id.getPath().isEmpty()) throw new IllegalArgumentException("HUD ID path must not be empty");
    }

    private static MinecraftClient requireClientThread() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            throw new IllegalStateException("HUD mutation/rendering requires the Minecraft client thread; use MinecraftClient.execute");
        }
        return client;
    }

    private record RenderJob(Entry entry, HudElementDescriptor descriptor, HudRenderer renderer, boolean preview) {
    }

    private static final class Entry {
        private final Identifier id;
        private final String name;
        private final HudSize size;
        private final HudPosition defaultPosition;
        private final HudRenderer renderer;
        private final HudRenderer previewRenderer;
        private HudPosition position;
        private boolean enabled = true;
        private boolean failed;
        private boolean positionChanged;
        private boolean enabledChanged;
        private OIConfig boundConfig;

        private Entry(Identifier id, String name, HudSize size, HudPosition defaultPosition,
                      HudRenderer renderer, HudRenderer previewRenderer) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.defaultPosition = defaultPosition;
            this.position = defaultPosition;
            this.renderer = renderer;
            this.previewRenderer = previewRenderer;
        }

        private HudElementDescriptor descriptor() {
            return new HudElementDescriptor(id, name, size, defaultPosition, position, enabled, failed);
        }
    }

    private static final class ElementDrawContext extends DrawContext implements AutoCloseable {
        private int scissorDepth;

        private ElementDrawContext(DrawContext parent, HudPosition origin, HudSize size, int viewportWidth, int viewportHeight) {
            super(MinecraftClient.getInstance(), ((DrawContextAccessor) parent).openintel$state(), -1, -1);
            getMatrices().set(parent.getMatrices());
            getMatrices().pushMatrix();
            getMatrices().translate((float) origin.x(), (float) origin.y());
            enableScissor(0, 0, Math.min(size.width(), viewportWidth - origin.x()),
                    Math.min(size.height(), viewportHeight - origin.y()));
        }

        @Override
        public void enableScissor(int x1, int y1, int x2, int y2) {
            super.enableScissor(x1, y1, x2, y2);
            scissorDepth++;
        }

        @Override
        public void disableScissor() {
            if (scissorDepth <= 1) throw new IllegalStateException("HUD callback cannot remove the element's base clip");
            super.disableScissor();
            scissorDepth--;
        }

        @Override
        public void createNewRootLayer() {
            throw new UnsupportedOperationException("HUD callbacks cannot change the global GUI layer");
        }

        @Override
        public void applyBlur() {
            throw new UnsupportedOperationException("HUD callbacks cannot blur the global GUI layer");
        }

        @Override
        public void close() {
            while (scissorDepth > 0) {
                super.disableScissor();
                scissorDepth--;
            }
            try {
                getMatrices().popMatrix();
            } finally {
                getMatrices().clear();
            }
        }
    }
}
