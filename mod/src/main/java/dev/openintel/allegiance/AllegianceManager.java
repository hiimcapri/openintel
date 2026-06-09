package dev.openintel.allegiance;

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
        replace(modUsers, users);
        replace(allies, allyList);
        replace(enemies, enemyList);
        replace(focus, focusList);
    }

    private static void replace(Set<String> target, Collection<String> source) {
        target.clear();
        if (source != null) source.forEach(s -> target.add(s.toLowerCase(Locale.ROOT)));
    }
}
