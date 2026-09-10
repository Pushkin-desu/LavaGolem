package io.github.pushkindesu.lavagolem.protection;

import io.github.pushkindesu.lavagolem.LavaGolemPlugin;
import io.github.pushkindesu.lavagolem.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central gate for whether a golem may read from or write to a container it has just walked up to —
 * see {@code GolemTicker#containerFor}, the single choke point every arrival handler goes through so
 * this class (and GriefPrevention/WorldGuard) never has to be known about anywhere else.
 *
 * <p>Registered hooks are consulted only here, at ARRIVAL, never while a golem is merely scanning a
 * search cube for candidates — asking a protection plugin about every block in a 24-radius cube would
 * be far too expensive, so a scan stays optimistic (a golem may walk all the way to a container it
 * turns out isn't allowed to touch, which then shows up as an ordinary idle/backoff via lastProblem,
 * exactly like any other station-broke reason). This is also the only place the verdict cache below is
 * written; nothing outside this class ever calls a hook directly.
 */
public final class ProtectionManager {

    private final LavaGolemPlugin plugin;
    private final List<ProtectionHook> hooks = new ArrayList<>();

    /** Kept aside (not just left in {@link #hooks}) because it is the one hook that structurally
     *  cannot answer for an offline owner — see the on-line filtering in {@link #canAccess}. */
    private GenericEventHook genericHook;

    private static final class Verdict {
        final boolean allowed;
        final long expiryMs;
        Verdict(boolean allowed, long expiryMs) { this.allowed = allowed; this.expiryMs = expiryMs; }
    }

    /** "owner UUID + block location" -> the last authoritative verdict for that pair. Keyed by owner
     *  as well as location because the very same container can be allowed for one golem's owner and
     *  denied for another's. Written only on an authoritative (arrival-time) check; read only as the
     *  offline fallback for protection-mode "cached" below. */
    private final Map<String, Verdict> cache = new ConcurrentHashMap<>();

    /** How many checks in a row a hook may fail before it is switched off for the session. */
    private static final int HOOK_FAILURE_LIMIT = 3;

    /** Consecutive failed checks per hook name, and the hooks given up on. Neither integration here
     *  can be tested on the maintainer's own server, so the thing that has to be right is what
     *  happens when one of them breaks on someone else's: never a denial, never a wedged golem, and
     *  never an endless wall of console warnings. */
    private final Map<String, Integer> hookFailures = new ConcurrentHashMap<>();
    private final java.util.Set<String> disabledHooks = ConcurrentHashMap.newKeySet();

    public ProtectionManager(LavaGolemPlugin plugin) {
        this.plugin = plugin;
        registerHooks();
    }

    /** Re-registers every hook from scratch and drops all per-session bookkeeping (the verdict
     *  cache, failure counts, and any hook that had been switched off after repeated failures) —
     *  called from LavaGolemPlugin's /lavagolem reload so protection-mode and protection-cache-
     *  seconds take effect immediately, and a hook that got disabled earlier this session gets a
     *  fresh set of strikes rather than staying off until a full server restart. */
    public void reload() {
        hooks.clear();
        genericHook = null;
        hookFailures.clear();
        disabledHooks.clear();
        cache.clear();
        registerHooks();
    }

    /** Registers each hook only when its plugin is actually enabled, and only if constructing it
     *  (which touches the real API) doesn't throw — a missing class or a method WorldGuard/
     *  GriefPrevention has since renamed then just disables that one hook with a warning, rather than
     *  ever being allowed to take the whole plugin down at startup. */
    private void registerHooks() {
        var pm = Bukkit.getPluginManager();
        if (pm.isPluginEnabled("GriefPrevention")) {
            try {
                hooks.add(new GriefPreventionHook());
                plugin.getLogger().info("[LG] GriefPrevention detected — container access will respect its claims.");
            } catch (Throwable t) {
                plugin.getLogger().warning("[LG] GriefPrevention is installed but its API could not be hooked ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + "); container protection will "
                        + "not consult it.");
            }
        }
        if (pm.isPluginEnabled("WorldGuard")) {
            try {
                hooks.add(new WorldGuardHook());
                plugin.getLogger().info("[LG] WorldGuard detected — container access will respect its regions.");
            } catch (Throwable t) {
                plugin.getLogger().warning("[LG] WorldGuard is installed but its API could not be hooked ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage() + "); container protection will "
                        + "not consult it.");
            }
        }
        try {
            genericHook = new GenericEventHook();
            hooks.add(genericHook);
        } catch (Throwable t) {
            plugin.getLogger().warning("[LG] Generic protection event hook could not be created ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage() + "); protection plugins with no "
                    + "GriefPrevention/WorldGuard-style API of their own will not be able to veto golem access.");
        }
    }

    private String cacheKey(UUID owner, Block block) {
        var loc = block.getLocation();
        return owner + "|" + loc.getWorld().getUID() + "|" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /**
     * Authoritative access check for the golem's owner at {@code block} — call this ONLY from the
     * arrival choke point, never from a search scan (see the class doc). Returns true immediately
     * under {@code protection-mode: off} (today's behaviour) and for an unowned/legacy golem under
     * anything but {@code strict} (which has no owner here to verify, so it refuses).
     */
    public boolean canAccess(Mob golem, Block block) {
        PluginConfig.ProtectionMode mode = plugin.cfg.protectionMode;
        if (mode == PluginConfig.ProtectionMode.OFF) return true;

        UUID owner = plugin.ownerOf(golem);
        if (owner == null) {
            // A legacy golem placed before ownership existed has no owner to check anything against.
            // STRICT refuses what it cannot verify; CACHED (and OFF, already returned above) keep it
            // working exactly as it always has, per the ownership spec's "legacy golems keep working".
            return mode != PluginConfig.ProtectionMode.STRICT;
        }

        boolean ownerOnline = Bukkit.getOfflinePlayer(owner).isOnline();
        List<ProtectionHook> toConsult = new ArrayList<>(hooks.size());
        for (ProtectionHook hook : hooks) {
            if (hook == genericHook && !ownerOnline) continue; // needs a live player to fire an event as
            toConsult.add(hook);
        }

        String key = cacheKey(owner, block);
        if (toConsult.isEmpty()) {
            // Nothing could actually be asked right now — no GriefPrevention/WorldGuard installed, and
            // the owner is offline so even the generic event hook can't fire. Fall back to whatever
            // protection-mode says about a station whose owner isn't here to ask.
            if (ownerOnline) return true; // online, but genuinely no hook is registered at all
            if (mode == PluginConfig.ProtectionMode.STRICT) return false;
            Verdict cached = cache.get(key);
            return cached == null || System.currentTimeMillis() >= cached.expiryMs || cached.allowed;
        }

        boolean allowed = true;
        for (ProtectionHook hook : toConsult) {
            if (disabledHooks.contains(hook.name())) continue;
            try {
                if (!hook.canAccess(owner, block)) { allowed = false; break; }
                hookFailures.remove(hook.name()); // it answered, so whatever went wrong before passed
            } catch (Throwable t) {
                // A hook misbehaving must never wedge a golem in place — treat "it threw" as "no
                // opinion" rather than as a denial, the same spirit as every other per-golem guard in
                // this plugin (see GolemTicker#run's own per-golem try/catch).
                //
                // And give up on it after a few strikes. A hook that throws is almost always an API
                // that shifted under us in an update, which means it throws on EVERY check — one
                // warning per container access would bury a busy server's console at several lines a
                // second, and each doomed call still costs time. Better to say so once and stop.
                int strikes = hookFailures.merge(hook.name(), 1, Integer::sum);
                if (strikes >= HOOK_FAILURE_LIMIT) {
                    disabledHooks.add(hook.name());
                    plugin.getLogger().warning("[LG] Protection hook '" + hook.name() + "' failed "
                            + strikes + " checks in a row and has been switched off for this session."
                            + " Golem container access is no longer being checked against it."
                            + " Last failure: " + t);
                } else {
                    plugin.getLogger().warning("[LG] Protection hook '" + hook.name()
                            + "' threw during a check, ignoring it this time: " + t);
                }
            }
        }
        cache.put(key, new Verdict(allowed,
                System.currentTimeMillis() + plugin.cfg.protectionCacheSeconds * 1000L));
        return allowed;
    }
}
