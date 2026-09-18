package dev.openintel.jm;

import dev.openintel.OpenIntelClient;
import dev.openintel.ping.PingManager;
import dev.openintel.tracker.Tracker;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Mirrors OpenIntel's snitch hits and shared pings onto JourneyMap's
 * fullscreen map as MarkerOverlays. Runs as a Runnable from the client
 * tick loop; reconciles desired state against what's shown every second,
 * so expiry, disconnect clears and allegiance changes all take care of
 * themselves. Rendering on our side is untouched — JM-only extra.
 */
final class JmBridge implements Runnable {

    /** Desired marker: stable signature + display data. */
    private record Desired(String sig, BlockPos pos, String dimension,
                           int rgb, String title, String label) { }

    private final IClientAPI api;
    private final Map<String, MarkerOverlay> shown = new HashMap<>();
    private final Map<String, String> sigs = new HashMap<>();
    private NativeImage icon;
    private int ticks;
    private boolean broken;

    JmBridge(IClientAPI api) {
        this.api = api;
    }

    @Override
    public void run() {
        if (broken || ++ticks % 20 != 0) return;   // ~1s reconcile cadence
        try {
            sync();
        } catch (Throwable t) {
            broken = true;   // never let an API hiccup hurt the client
        }
    }

    private void sync() {
        var cfg = OpenIntelClient.config();
        var tracker = OpenIntelClient.tracker();
        Map<String, Desired> want = new HashMap<>();

        if (cfg != null && cfg.jmMarkers && tracker != null) {
            for (Tracker.SnitchHit h : tracker.snitchHits()) {
                int rgb = OpenIntelClient.allegiances().of(h.player).argb & 0xFFFFFF;
                String label = h.player + " (via " + h.reporter + ")";
                want.put("snitch:" + h.player + "@" + h.snitch,
                        new Desired("snitch|" + rgb + "|" + label,
                                BlockPos.ofFloored(h.x, h.y, h.z), h.dimension, rgb,
                                "Snitch: " + h.snitch, label));
            }
            for (PingManager.Ping p : PingManager.active()) {
                int rgb = p.color & 0xFFFFFF;
                want.put("ping:" + p.id,
                        new Desired("ping|" + rgb + "|" + p.label,
                                BlockPos.ofFloored(p.x, p.y, p.z), p.dimension, rgb,
                                "Ping: " + p.label, p.sender));
            }
        }

        // Drop markers that expired, cleared, or lost the toggle.
        Iterator<Map.Entry<String, MarkerOverlay>> it = shown.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, MarkerOverlay> e = it.next();
            if (!want.containsKey(e.getKey())) {
                removeQuietly(e.getValue());
                sigs.remove(e.getKey());
                it.remove();
            }
        }

        // Add new markers; recreate any whose appearance changed.
        for (Map.Entry<String, Desired> e : want.entrySet()) {
            Desired d = e.getValue();
            if (d.sig.equals(sigs.get(e.getKey()))) continue;
            MarkerOverlay old = shown.remove(e.getKey());
            if (old != null) removeQuietly(old);
            MarkerOverlay overlay = show(d);
            if (overlay != null) {
                shown.put(e.getKey(), overlay);
                sigs.put(e.getKey(), d.sig);
            }
        }
    }

    private MarkerOverlay show(Desired d) {
        RegistryKey<World> dim;
        try {
            dim = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(d.dimension));
        } catch (Exception e) {
            return null;   // "openintel:unknown" or a custom world id — skip
        }
        MarkerOverlay overlay = new MarkerOverlay("openintel", d.pos, icon(d.rgb));
        overlay.setDimension(dim);
        overlay.setTitle(d.title);
        overlay.setLabel(d.label);
        overlay.setOverlayGroupName("OpenIntel");
        overlay.setActiveUIs(Context.UI.Fullscreen);
        overlay.setActiveMapTypes(Context.MapType.all());
        try {
            api.show(overlay);
            return overlay;
        } catch (Exception e) {
            broken = true;
            return null;
        }
    }

    private void removeQuietly(MarkerOverlay overlay) {
        try {
            api.remove(overlay);
        } catch (Throwable ignored) { }
    }

    /** Shared diamond icon, tinted per-marker by allegiance/ping color. */
    private MapImage icon(int rgb) {
        if (icon == null) {
            icon = new NativeImage(16, 16, false);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int d = Math.abs(x - 7) + Math.abs(y - 7);
                    if (d <= 6) icon.setColorArgb(x, y, d >= 5 ? 0xFF202020 : 0xFFFFFFFF);
                }
            }
        }
        return new MapImage(icon).setColor(rgb).centerAnchors();
    }
}
