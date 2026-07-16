package io.github.pushkindesu.lavagolem;

public class PluginConfig {

    public final int searchRadius;
    public final double reachDistance;
    public final long searchCooldownTicks;
    public final long tickPeriod;
    public final String bucketSignText;
    public final String lavaSignText;
    public final String smeltSignText;
    public final String fuelSignText;
    public final String outputSignText;
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
}
