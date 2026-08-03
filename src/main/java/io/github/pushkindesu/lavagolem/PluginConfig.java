package io.github.pushkindesu.lavagolem;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PluginConfig {

    /** A config-defined extra fishing catch: an item added to one of the three loot pools. */
    public record CustomCatch(Material material, int weight, int amount, String pool) {}

    public final int searchRadius;
    public final double reachDistance;
    public final long searchCooldownTicks;
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

    public PluginConfig(LavaGolemPlugin plugin) {
        plugin.saveDefaultConfig();
        var c = plugin.getConfig();
        this.searchRadius        = c.getInt("search-radius", 8);
        this.reachDistance       = c.getDouble("reach-distance", 2.2);
        this.searchCooldownTicks = c.getLong("search-cooldown-ticks", 40);
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
        this.courierTeleport     = c.getBoolean("courier-teleport", true);
        this.courierStuckTicks   = c.getLong("courier-stuck-ticks", 20);
        this.locale              = c.getString("locale", "en");
        this.bstats              = c.getBoolean("bstats", true);
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
