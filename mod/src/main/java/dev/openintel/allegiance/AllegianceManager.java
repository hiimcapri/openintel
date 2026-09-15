package dev.openintel.allegiance;

import dev.openintel.api.internal.ApiBridge;
import net.minecraft.client.MinecraftClient;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a player name to a render color. Lists are pushed live from the
 * relay server (which the admins edit through the admin panel / webhook-logged
 * REST API), so the whole network re-colors instantly when allegiances change.
 *
 *  FOCUS   (bright purple) — marked by a Captain via /oi focus; beats everything
 *  FRIEND  (green)         — another approved user of the mod network
 *  ALLY    (soft purple)   — on the allies list, not running the mod
 *  ENEMY   (red)           — on the enemies list
 *  NEUTRAL (grey)          — everyone else
 */
public final class AllegianceManager {

    public enum Allegiance {
        FOCUS(0xFFE040FB),
        FRIEND(0xFF55FF55),
        ALLY(0xFFAA55FF),
        ENEMY(0xFFFF5555),
        NEUTRAL(0xFFAAAAAA);

        public final int argb;
        Allegiance(int argb) { this.argb = argb; }
    }

    private final Set<String> modUsers = ConcurrentHashMap.newKeySet();
    private final Set<String> allies   = ConcurrentHashMap.newKeySet();
    private final Set<String> enemies  = ConcurrentHashMap.newKeySet();
    private final Set<String> focus    = ConcurrentHashMap.newKeySet();

    public Allegiance of(String playerName) {
        String n = playerName.toLowerCase(Locale.ROOT);
        if (focus.contains(n))    return Allegiance.FOCUS;
        if (modUsers.contains(n)) return Allegiance.FRIEND;
        if (enemies.contains(n))  return Allegiance.ENEMY;   // enemy beats ally if mislisted
        if (allies.contains(n))   return Allegiance.ALLY;
        return Allegiance.NEUTRAL;
    }

    public boolean isEnemy(String playerName) {
        Allegiance a = of(playerName);
        return a == Allegiance.ENEMY || a == Allegiance.FOCUS;
    }

    /** Called when the relay pushes a fresh allegiance/user snapshot. */
    public void replaceAll(Collection<String> users, Collection<String> allyList,
                           Collection<String> enemyList, Collection<String> focusList) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            var userCopy = users == null ? java.util.List.<String>of() : java.util.List.copyOf(users);
            var allyCopy = allyList == null ? java.util.List.<String>of() : java.util.List.copyOf(allyList);
            var enemyCopy = enemyList == null ? java.util.List.<String>of() : java.util.List.copyOf(enemyList);
            var focusCopy = focusList == null ? java.util.List.<String>of() : java.util.List.copyOf(focusList);
            var world = client.world;
            client.execute(() -> { if (client.world == world) replaceAll(userCopy, allyCopy, enemyCopy, focusCopy); });
            return;
        }
        replace(modUsers, users);
        replace(allies, allyList);
        replace(enemies, enemyList);
        replace(focus, focusList);
        ApiBridge.allegiancesChanged(modUsers, allies, enemies, focus);
    }

    private static void replace(Set<String> target, Collection<String> source) {
        target.clear();
        if (source != null) source.forEach(s -> target.add(s.toLowerCase(Locale.ROOT)));
    }
}
