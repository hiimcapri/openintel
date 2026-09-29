package dev.openintel.jm;

import dev.openintel.OpenIntelClient;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.JourneyMapPlugin;
import journeymap.api.v2.common.waypoint.Waypoint;

/**
 * JourneyMap entrypoint (registered under "journeymap" in fabric.mod.json).
 * JM instantiates this only when JourneyMap itself is loaded, so the whole
 * integration is a true soft dependency — nothing outside dev.openintel.jm
 * references the API and OpenIntelClient only sees a Runnable hook.
 */
@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public class OpenIntelJmPlugin implements IClientPlugin {

    @Override
    public void initialize(IClientAPI api) {
        OpenIntelClient.jmTick = new JmBridge(api);
        // Legacy cleanup: relics used to be persistent waypoints — sweep
        // any left in JM's store; they're MarkerOverlays now.
        try {
            for (Waypoint wp : api.getWaypoints("openintel")) {
                if (wp.getName().startsWith("Relic ")) {
                    api.removeWaypoint("openintel", wp);
                }
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public String getModId() {
        return "openintel";
    }
}
