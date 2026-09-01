package io.github.pushkindesu.lavagolem;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PluginConfig {

    /** A config-defined extra fishing catch: an item added to one of the three loot pools. */
    public record CustomCatch(Material material, int weight, int amount, String pool) {}

    /** Where /golemdebug's trace lines go. FILE and BOTH are what make it possible to leave a
     *  courier tracing unattended and read the result later instead of watching chat live. */
    public enum DebugOutput { CHAT, FILE, BOTH }

    public final int searchRadius;
    public final double reachDistance;
    public final long searchCooldownTicks;
    public final long golemStuckTicks;
    public final long tickPeriod;
    public final String bucketSignText;
    public final String lavaSignText;
    public final String smeltSignText;
    public final String fuelSignText;
    public final String outputSignText;
    public final String brewSignText;
    public final String rodsSignText;
    public final String treasureSignText;
    public final long fisherMinWaitTicks;
    public final long fisherMaxWaitTicks;
    public final boolean fisherTreasure;
    public final List<CustomCatch> customCatches;
    public final boolean enableLava;
    public final boolean enableSmelter;
    public final boolean enableCourier;
    public final boolean enableAlchemist;
    public final boolean enableFisher;
    public final int courierSearchRadius;
    public final int courierCarryLimit;
    public final String waypointSignText;
    public final boolean courierTeleport;
    public final long courierStuckTicks;
    public final String locale;
    public final boolean bstats;
    public final DebugOutput golemdebugOutput;
    public final boolean navAsync;
    public final int navMaxDistance;
    public final int navMaxNodes;
    public final long navChunkCacheSeconds;
    public final int navChunksPerTick;
    public final int navMaxConcurrent;
    public final int navChunksPerTickBurst;
    public final int navStarvedRetries;
    public final int navStarvedStallTries;
    public final int navPrewarmMaxChunks;
    public final int navMaxStepDown;
    public final int navSearchMargin;
    public final int navMaxLegBlocks;
    public final int navMoveRefusedTicks;

    public PluginConfig(LavaGolemPlugin plugin) {
        // saveDefaultConfig(), ConfigMigrator.migrate(), and reloadConfig() have already run by the
        // time this constructor is called (see LavaGolemPlugin#onEnable) -- config.yml on disk is
        // guaranteed to exist, and to carry every key this version knows about, before anything here
        // reads a single value out of it.
        var c = plugin.getConfig();
        // The hauler/smelter/alchemist/fisher search is a cube scan too — same O(radius^3) cost as
        // the courier's, so it gets the same 1..32 clamp rather than trusting an admin-set value.
        int rawSearchRadius = c.getInt("search-radius", 8);
        this.searchRadius        = Math.max(1, Math.min(32, rawSearchRadius));
        if (rawSearchRadius != this.searchRadius) {
            plugin.getLogger().warning("search-radius " + rawSearchRadius + " is out of range (1-32), using "
                    + this.searchRadius + " instead.");
        }
        this.reachDistance       = c.getDouble("reach-distance", 2.2);
        this.searchCooldownTicks = c.getLong("search-cooldown-ticks", 40);
        // Logic ticks (each tick-period game ticks) with no real progress toward a target before a
        // golem gives up on it and goes back to idle, rather than shoving into the same wall forever.
        this.golemStuckTicks     = Math.max(5, c.getLong("golem-stuck-ticks", 30));
        this.tickPeriod          = c.getLong("tick-period", 10);
        this.bucketSignText      = c.getString("bucket-sign-text", "[Buckets]");
        this.lavaSignText        = c.getString("lava-sign-text", "[Lava]");
        this.smeltSignText       = c.getString("smelt-sign-text", "[Smelt]");
        this.fuelSignText        = c.getString("fuel-sign-text", "[Fuel]");
        this.outputSignText      = c.getString("output-sign-text", "[Output]");
        this.brewSignText        = c.getString("brew-sign-text", "[Brew]");
        this.rodsSignText        = c.getString("rods-sign-text", "[Rods]");
        this.treasureSignText    = c.getString("treasure-sign-text", "[Treasure]");
        // Vanilla's own wait window is 100-600 ticks (5-30s), which Lure shortens. Kept configurable
        // so a server can slow the fisher down, but defaulted to the values a player would get.
        this.fisherMinWaitTicks  = Math.max(1, c.getLong("fisher-min-wait-ticks", 100));
        this.fisherMaxWaitTicks  = Math.max(this.fisherMinWaitTicks,
                                            c.getLong("fisher-max-wait-ticks", 600));
        this.fisherTreasure      = c.getBoolean("fisher-treasure", true);
        this.customCatches       = parseCustomCatches(plugin, c);
        // Per-role switches: a disabled role can't be crafted or spawned, and any that already exist
        // sit inert. Everything on by default.
        this.enableLava          = c.getBoolean("enable-lava-golem", true);
        this.enableSmelter       = c.getBoolean("enable-smelter-golem", true);
        this.enableCourier       = c.getBoolean("enable-courier-golem", true);
        this.enableAlchemist     = c.getBoolean("enable-alchemist-golem", true);
        this.enableFisher        = c.getBoolean("enable-fisher-golem", true);
        // Uniform search box in ALL directions (a "sphere-ish" cube). Capped at 32 because a scan
        // is O(radius^3): 32 => ~275k blocks; larger would hammer the main thread.
        this.courierSearchRadius = Math.max(1, Math.min(32, c.getInt("courier-search-radius", 24)));
        this.courierCarryLimit   = Math.max(1, Math.min(64, c.getInt("courier-carry-limit", 16)));
        this.waypointSignText    = c.getString("waypoint-sign-text", "[Waypoint]");
        this.courierTeleport     = c.getBoolean("courier-teleport", false);
        this.courierStuckTicks   = c.getLong("courier-stuck-ticks", 20);
        this.locale              = c.getString("locale", "en");
        this.bstats              = c.getBoolean("bstats", true);
        // Same warn-and-fall-back pattern as the other validated strings above: a typo here would
        // otherwise silently leave the maintainer wondering why golemdebug.log stayed empty.
        String rawDebugOutput = c.getString("golemdebug-output", "chat");
        DebugOutput parsedDebugOutput;
        switch (rawDebugOutput == null ? "" : rawDebugOutput.trim().toLowerCase(Locale.ROOT)) {
            case "chat" -> parsedDebugOutput = DebugOutput.CHAT;
            case "file" -> parsedDebugOutput = DebugOutput.FILE;
            case "both" -> parsedDebugOutput = DebugOutput.BOTH;
            default -> {
                plugin.getLogger().warning("golemdebug-output '" + rawDebugOutput
                        + "' is not one of chat/file/both, using chat instead.");
                parsedDebugOutput = DebugOutput.CHAT;
            }
        }
        this.golemdebugOutput = parsedDebugOutput;

        this.navAsync            = c.getBoolean("nav-async", true);
        // How far a single navmesh search is allowed to reach before giving up and letting the
        // caller fall back — a search doesn't get cheaper just because the map does, so this is
        // capped rather than trusted outright.
        int rawMaxDistance = c.getInt("nav-max-distance", 256);
        this.navMaxDistance      = Math.max(16, Math.min(1024, rawMaxDistance));
        // Node budget per search. Too low and long routes never finish (they just return a short
        // partial path over and over); too high and one stuck golem's search can eat a worker
        // thread for a noticeable stretch of wall-clock time.
        int rawMaxNodes = c.getInt("nav-max-nodes", 20000);
        this.navMaxNodes         = Math.max(500, Math.min(200_000, rawMaxNodes));
        // How long a chunk's walkability map is trusted before being rebuilt from scratch, on TOP of
        // being rebuilt immediately by NavMeshListener on block place/break/explosion/piston/unload --
        // that event-based invalidation does the real work of keeping the map honest. This TTL exists
        // only as the backstop for the two things deliberately NOT hooked (water flow, falling blocks
        // fire far too often to listen to), so it can afford to be long: a route that takes minutes to
        // walk round-trip should not have its own corridor expire out from under it on the way back.
        this.navChunkCacheSeconds = Math.max(5, c.getLong("nav-chunk-cache-seconds", 600));
        // Raised from an earlier default of 2: a starved search now waits for its corridor instead
        // of walking a bad partial path, so building that corridor promptly matters more than before.
        this.navChunksPerTick    = Math.max(1, Math.min(16, c.getInt("nav-chunks-per-tick", 4)));
        this.navMaxConcurrent    = Math.max(1, Math.min(16, c.getInt("nav-max-concurrent", 4)));
        // Ceiling allowed for one tick while at least one golem is actually blocked in COMPUTING --
        // these are chunks that are already LOADED, so the only main-thread cost is getChunkSnapshot
        // (the parse itself runs on the worker), which is why this can be pushed much harder than the
        // steady-state rate without real risk. Turn it back down if a big multi-courier base ever
        // shows TPS trouble from a burst of simultaneous route requests.
        this.navChunksPerTickBurst = Math.max(this.navChunksPerTick, Math.min(64, c.getInt("nav-chunks-per-tick-burst", 24)));
        // Absolute ceiling on how many times in a row a search may come back starved before giving up
        // on waiting and walking the best partial anyway, regardless of whether it's still making
        // progress. This is a runaway guard, not the normal exit: the normal exit is
        // nav-starved-stall-tries below noticing the unmapped count has stopped shrinking. Deliberately
        // generous, since a search that's genuinely converging (19 -> 18 -> 11 -> 5 unmapped) should be
        // allowed to keep going rather than being cut off by an arbitrary attempt count.
        this.navStarvedRetries   = Math.max(5, Math.min(500, c.getInt("nav-starved-retries", 40)));
        // The real exit condition: how many consecutive starved results with NO reduction in the
        // unmapped-chunk count before concluding the corridor has stopped growing and it's time to
        // walk the best partial instead of waiting further.
        this.navStarvedStallTries = Math.max(1, Math.min(20, c.getInt("nav-starved-stall-tries", 3)));
        // Cap on how many chunks a single leg's first request may seed into the build queue when it
        // pre-warms the from/goal bounding box (expanded 2 chunks each side). Keeps a very long or
        // very diagonal leg from flooding the queue in one go; the search still works without every
        // chunk pre-seeded, just discovers the rest of the corridor a little more incrementally.
        this.navPrewarmMaxChunks = Math.max(16, Math.min(4096, c.getInt("nav-prewarm-max-chunks", 256)));
        // How many blocks a search may voluntarily step DOWN in one move. Vanilla ground navigation
        // will not walk a mob off anything bigger than a short drop, so a value here more permissive
        // than that just hands the golem turn points moveTo refuses to reach. Defaulted to 1 (a single
        // stair step) rather than what AStar could safely climb DOWN on its own, because a bigger drop
        // should be a staircase the player built, not a shortcut the golem takes off a ledge.
        this.navMaxStepDown      = Math.max(1, Math.min(3, c.getInt("nav-max-step-down", 1)));
        this.navSearchMargin     = Math.max(8, Math.min(128, c.getInt("nav-search-margin", 32)));
        // Longest a single leg of the string-pulled walk is allowed to be. Over a long clear stretch,
        // string-pulling's own line-of-sight test can leave two turn points dozens of blocks apart --
        // moveTo then has to re-derive its own route across that whole gap using its own node budget
        // and follow range, which can fail for reasons this plugin's model never saw. Chopping long
        // legs back down to raw, known-walkable path points keeps every hop small enough that vanilla
        // is doing local steering, exactly as designed, rather than a second full pathfind of its own.
        this.navMaxLegBlocks     = Math.max(4, Math.min(32, c.getInt("nav-max-leg-blocks", 10)));
        // How many consecutive ticks getPathfinder().moveTo() may flatly REFUSE the current turn
        // point before that alone counts as a stall, without waiting for golem-stuck-ticks. A refusal
        // is a hard, unambiguous "no" from vanilla -- not a heuristic like "hasn't got closer" -- so
        // sitting on it for the full distance-based window only wastes the golem's time and blames the
        // wrong leg: the golem never actually attempted the one the timer eventually penalises.
        this.navMoveRefusedTicks = Math.max(2, Math.min(40, c.getInt("nav-move-refused-ticks", 4)));
    }

    /** Reads the optional fisher-custom-catches list. Each entry adds an item to one loot pool
     *  (fish/junk/treasure); a bad entry is warned about and skipped rather than failing startup. */
    private static List<CustomCatch> parseCustomCatches(LavaGolemPlugin plugin, org.bukkit.configuration.file.FileConfiguration c) {
        List<CustomCatch> out = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("fisher-custom-catches")) {
            Object rawMat = m.get("material");
            if (rawMat == null) { plugin.getLogger().warning("fisher-custom-catch with no material, skipped"); continue; }
            Material mat = Material.matchMaterial(String.valueOf(rawMat));
            if (mat == null || !mat.isItem()) {
                plugin.getLogger().warning("fisher-custom-catch: unknown item '" + rawMat + "', skipped");
                continue;
            }
            int weight = m.get("weight") instanceof Number n ? n.intValue() : 1;
            int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
            String pool = m.get("pool") == null ? "fish"
                    : String.valueOf(m.get("pool")).toLowerCase(Locale.ROOT);
            if (!pool.equals("fish") && !pool.equals("junk") && !pool.equals("treasure")) {
                plugin.getLogger().warning("fisher-custom-catch '" + mat + "': pool must be fish/junk/treasure, was '"
                        + pool + "', defaulting to fish");
                pool = "fish";
            }
            if (weight < 1) weight = 1;
            if (amount < 1) amount = 1;
            out.add(new CustomCatch(mat, weight, amount, pool));
        }
        return List.copyOf(out);
    }
}
