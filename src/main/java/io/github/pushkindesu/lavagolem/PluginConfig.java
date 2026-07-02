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
        this.locale              = c.getString("locale", "en");
        this.bstats              = c.getBoolean("bstats", true);
    }
}
