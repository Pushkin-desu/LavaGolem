package io.github.pushkindesu.lavagolem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LavaGolemPlugin extends JavaPlugin {

    // PDC keys
    public NamespacedKey heartItemKey;
    public NamespacedKey heartRoleKey;
    public NamespacedKey golemEntityKey;
    public NamespacedKey roleKey;
    public NamespacedKey modeKey;
    public NamespacedKey lavaDeliveredKey;
    public NamespacedKey bucketsTakenKey;
    public NamespacedKey itemsSmeltedKey;
    public NamespacedKey itemsMovedKey;
    public NamespacedKey potionsBrewedKey;
    public NamespacedKey fishCaughtKey;
    public NamespacedKey treasureCaughtKey;
    public NamespacedKey rodsUsedKey;
    /** Set while the fisher carries a catch that came off the treasure table, so the delivery step
     *  knows to look for [Treasure] rather than [Output]. */
    public NamespacedKey treasureFlagKey;
    public NamespacedKey courierRoutesKey;
    public NamespacedKey createdAtKey;
    /** Set when a player has paused this golem from its menu; the ticker then leaves it alone. */
    public NamespacedKey pausedKey;
    /** Ingredients the alchemist is told NOT to use (absent = brew everything in the chest). */
    public NamespacedKey alchemyDisabledKey;

    /**
     * The station tags a golem looks for. The config value is only the DEFAULT — each golem can be
     * given its own tag from its menu, so two stations of the same kind can sit side by side without
     * fighting over one shared `[Output]`.
     */
    public enum GolemTag { BUCKETS, LAVA, SMELT, FUEL, OUTPUT, BREW, RODS, TREASURE }

    private final Map<GolemTag, NamespacedKey> tagKeys = new java.util.EnumMap<>(GolemTag.class);

    /** The config default for a tag (what a golem uses unless it was given its own). */
    public String defaultTag(GolemTag t) {
        return switch (t) {
            case BUCKETS -> cfg.bucketSignText;
            case LAVA -> cfg.lavaSignText;
            case SMELT -> cfg.smeltSignText;
            case FUEL -> cfg.fuelSignText;
            case OUTPUT -> cfg.outputSignText;
            case BREW -> cfg.brewSignText;
            case RODS -> cfg.rodsSignText;
            case TREASURE -> cfg.treasureSignText;
        };
    }

    /** The tag THIS golem looks for: its own override, else the config default. */
    public String tagFor(org.bukkit.entity.Mob golem, GolemTag t) {
        String v = golem.getPersistentDataContainer().get(tagKeys.get(t), PersistentDataType.STRING);
        return (v == null || v.isEmpty()) ? defaultTag(t) : v;
    }

    public boolean hasTagOverride(org.bukkit.entity.Mob golem, GolemTag t) {
        String v = golem.getPersistentDataContainer().get(tagKeys.get(t), PersistentDataType.STRING);
        return v != null && !v.isEmpty();
    }

    /** Sets a golem's own tag; null/blank clears it back to the config default. */
    public void setTag(org.bukkit.entity.Mob golem, GolemTag t, String value) {
        if (value == null || value.isBlank()) {
            golem.getPersistentDataContainer().remove(tagKeys.get(t));
        } else {
            golem.getPersistentDataContainer().set(tagKeys.get(t), PersistentDataType.STRING, value.trim());
        }
    }

    /** Golem roles. */
    public static final String ROLE_HAULER = "LAVA_HAULER";
    public static final String ROLE_SMELTER = "SMELTER";
    public static final String ROLE_COURIER = "COURIER";
    public static final String ROLE_ALCHEMIST = "ALCHEMIST";
    public static final String ROLE_FISHER = "FISHER";

    /** Resolves the argument to /golemdebug's role-targeting form (e.g. "courier", "couriers") to
     *  the canonical role constant, accepting the obvious short forms so a server owner doesn't have
     *  to remember or type the exact PDC value. Returns null for anything unrecognised. */
    public static String resolveDebugRole(String arg) {
        String a = arg.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (a) {
            case "hauler", "haulers", "lava", "lavahauler", "lavagolem" -> ROLE_HAULER;
            case "smelter", "smelters" -> ROLE_SMELTER;
            case "courier", "couriers" -> ROLE_COURIER;
            case "alchemist", "alchemists" -> ROLE_ALCHEMIST;
            case "fisher", "fishers" -> ROLE_FISHER;
            default -> null;
        };
    }

    /** Short, human phrase for the /golemdebug confirmation message describing where the trace is
     *  actually going, so "watch chat" isn't printed when chat is exactly where it ISN'T going. */
    private String debugOutputHint() {
        return switch (cfg.golemdebugOutput) {
            case CHAT -> "watch chat";
            case FILE -> "see plugins/LavaGolem/golemdebug.log";
            case BOTH -> "watch chat, also logged to golemdebug.log";
        };
    }

    /** Counts live golems, optionally filtered to one role, for the /golemdebug all|<role> confirmation
     *  ("N currently") — purely informational, so a player knows immediately whether the toggle they
     *  just typed actually matched anything. */
    private int countGolems(String roleFilterOrNull) {
        int n = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntitiesByClass(Mob.class)) {
                if (!e.getPersistentDataContainer().has(golemEntityKey, PersistentDataType.BYTE)) continue;
                if (roleFilterOrNull != null) {
                    String role = e.getPersistentDataContainer()
                            .getOrDefault(roleKey, PersistentDataType.STRING, ROLE_HAULER);
                    if (!roleFilterOrNull.equals(role)) continue;
                }
                n++;
            }
        }
        return n;
    }

    /** Writes the file marker for toggling ONE specific golem — full UUID, role, and location, since
     *  with file output several golems can be traced at once and these markers are how the maintainer
     *  finds the right section instead of scrolling through interleaved lines. No-op under chat-only
     *  output: there's no file to mark. */
    private void logDebugToggleMarker(Mob golem, boolean on, org.bukkit.entity.Player by) {
        if (cfg.golemdebugOutput == PluginConfig.DebugOutput.CHAT || debugLog == null) return;
        String role = golem.getPersistentDataContainer()
                .getOrDefault(roleKey, PersistentDataType.STRING, ROLE_HAULER);
        Location l = golem.getLocation();
        String where = l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
        debugLog.enqueue("[" + GolemDebugLog.timestamp() + "] === TRACE " + (on ? "ON " : "OFF ")
                + golem.getUniqueId() + " (" + role + ") at " + where + ", toggled by " + by.getName() + " ===");
    }

    /** Same marker as {@link #logDebugToggleMarker}, but for the broad "all" / "<role>" modes, which
     *  don't name one golem — so the label is descriptive text ("ALL golems", "role COURIER")
     *  instead, plus the count for an ON marker (an OFF marker has nothing meaningful to count). */
    private void logDebugBroadMarker(String label, boolean on, org.bukkit.entity.Player by, int count) {
        if (cfg.golemdebugOutput == PluginConfig.DebugOutput.CHAT || debugLog == null) return;
        String suffix = on ? " (" + count + " golem(s) currently)" : "";
        debugLog.enqueue("[" + GolemDebugLog.timestamp() + "] === TRACE " + (on ? "ON " : "OFF ")
                + label + suffix + ", toggled by " + by.getName() + " ===");
    }

    /** Whether a role is switched on in the config. A disabled role can't be crafted or spawned, and
     *  any golems of that role already in the world sit inert until it's switched back on. */
    public boolean isRoleEnabled(String role) {
        if (ROLE_SMELTER.equals(role)) return cfg.enableSmelter;
        if (ROLE_COURIER.equals(role)) return cfg.enableCourier;
        if (ROLE_ALCHEMIST.equals(role)) return cfg.enableAlchemist;
        if (ROLE_FISHER.equals(role)) return cfg.enableFisher;
        return cfg.enableLava; // hauler / default
    }

    /** Smelter work modes (configurable per-golem via its GUI). */
    public static final String MODE_BALANCED = "BALANCED";
    public static final String MODE_LOAD_ONLY = "LOAD_ONLY";
    public static final String MODE_COLLECT_ONLY = "COLLECT_ONLY";

    /** UUIDs of golems whose vanilla AI has already been cleaned up in this session. */
    public final Set<UUID> cleanedUpGolems = ConcurrentHashMap.newKeySet();

    /** golem UUID -> watching player UUID, for /golemdebug live decision tracing of ONE specific
     *  golem (the original "walk up to it and toggle" behaviour). Transient. */
    public final Map<UUID, UUID> debugWatchers = new ConcurrentHashMap<>();

    /** Watching player UUID for {@code /golemdebug all}, or null when nobody has it on. Kept as its
     *  own flag rather than stuffing every golem's UUID into debugWatchers so it also covers golems
     *  that spawn AFTER the toggle, and turning it off never has to enumerate the whole server. */
    public volatile UUID debugAllWatcher;

    /** Role -> watching player UUID for {@code /golemdebug <role>} — same reasoning as
     *  debugAllWatcher, just scoped to one role instead of every golem. Transient. */
    public final Map<String, UUID> debugRoleWatchers = new ConcurrentHashMap<>();

    public GolemDebugLog debugLog;

    /** Valid inputs per furnace kind, discovered from recipes at startup.
     *  smeltableInputs (regular furnace) is a superset of the other two in vanilla. */
    public final Set<Material> smeltableInputs = new HashSet<>();
    public final Set<Material> blastableInputs = new HashSet<>();
    public final Set<Material> smokableInputs = new HashSet<>();

    public PluginConfig cfg;
    public Messages msg;
    public GolemMenu golemMenu;
    public CourierMenu courierMenu;
    public AlchemistMenu alchemistMenu;
    public TagPrompt tagPrompt;
    public GolemTicker golemTicker;
    public io.github.pushkindesu.lavagolem.nav.NavMesh navMesh;
    public io.github.pushkindesu.lavagolem.nav.Navigation navigation;

    /** Golems whose settings menu is currently open. The ticker holds any such golem still, whatever
     *  its role, so it doesn't wander off (or act on a half-configured route) while you're in its menu. */
    private final java.util.Set<java.util.UUID> openMenus = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Marks a golem's menu as open and halts its current movement so it stops on the spot. */
    public void markMenuOpen(org.bukkit.entity.Mob golem) {
        openMenus.add(golem.getUniqueId());
        golem.getPathfinder().stopPathfinding();
        // A courier (or any role) mid-search can otherwise sit on a wedged nav state that only an
        // external reset clears -- a menu open is a natural, always-safe point to force that reset,
        // since the golem is about to stand still anyway and will re-plan fresh once the menu closes.
        if (navigation != null) navigation.cancel(golem);
    }

    public void markMenuClosed(java.util.UUID golemId) { openMenus.remove(golemId); }

    public boolean isMenuOpen(java.util.UUID golemId) { return openMenus.contains(golemId); }

    /** Whether a player has parked this golem via its menu's power button. Unlike a menu being open
     *  (which holds it only while you look at it), a pause persists until you switch it back on. */
    public boolean isPaused(org.bukkit.entity.Mob golem) {
        return golem.getPersistentDataContainer()
                .getOrDefault(pausedKey, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    public void setPaused(org.bukkit.entity.Mob golem, boolean paused) {
        if (paused) {
            golem.getPersistentDataContainer().set(pausedKey, PersistentDataType.BYTE, (byte) 1);
            golem.getPathfinder().stopPathfinding(); // stop mid-stride rather than coasting on
        } else {
            golem.getPersistentDataContainer().remove(pausedKey);
        }
    }

    @Override
    public void onEnable() {
        // Write a fresh config.yml if none exists, then add any keys THIS version introduced that an
        // existing install's copy is missing -- saveDefaultConfig() alone only ever writes the file
        // once, so without this step every option added after someone's first install stays invisible
        // to them forever. reloadConfig() re-reads the file ConfigMigrator just appended to, since it
        // edits config.yml on disk directly rather than going through the cached in-memory copy; both
        // have to happen before PluginConfig reads a single value out of getConfig().
        saveDefaultConfig();
        ConfigMigrator.migrate(this);
        reloadConfig();
        cfg = new PluginConfig(this);
        msg = new Messages(this);
        // Created regardless of golemdebug-output so a mid-session config reload isn't needed to
        // start using file tracing — an unused GolemDebugLog just flushes an empty queue every tick.
        debugLog = new GolemDebugLog(this);
        debugLog.start();

        heartItemKey     = new NamespacedKey(this, "golem_heart");
        heartRoleKey     = new NamespacedKey(this, "heart_role");
        golemEntityKey   = new NamespacedKey(this, "lava_golem");
        roleKey          = new NamespacedKey(this, "role");
        modeKey          = new NamespacedKey(this, "mode");
        lavaDeliveredKey = new NamespacedKey(this, "lava_delivered");
        bucketsTakenKey  = new NamespacedKey(this, "buckets_taken");
        itemsSmeltedKey  = new NamespacedKey(this, "items_smelted");
        itemsMovedKey    = new NamespacedKey(this, "items_moved");
        potionsBrewedKey = new NamespacedKey(this, "potions_brewed");
        fishCaughtKey    = new NamespacedKey(this, "fish_caught");
        treasureCaughtKey = new NamespacedKey(this, "treasure_caught");
        rodsUsedKey      = new NamespacedKey(this, "rods_used");
        treasureFlagKey  = new NamespacedKey(this, "carrying_treasure");
        pausedKey        = new NamespacedKey(this, "paused");
        alchemyDisabledKey = new NamespacedKey(this, "alchemy_disabled");
        for (GolemTag t : GolemTag.values()) {
            tagKeys.put(t, new NamespacedKey(this, "tag_" + t.name().toLowerCase()));
        }
        courierRoutesKey = new NamespacedKey(this, "courier_routes");
        createdAtKey     = new NamespacedKey(this, "created_at");

        if (cfg.bstats) {
            new org.bstats.bukkit.Metrics(this, 31068);
        }

        // Reads server tags, so it has to happen here on the main thread — the pathfinding worker
        // only ever consults the finished set.
        io.github.pushkindesu.lavagolem.nav.NavMesh.initNonFloorMaterials();

        buildSmeltableInputs();
        // Only register a role's recipe if it's enabled — a disabled golem can't be crafted.
        if (cfg.enableLava) registerHeartRecipe();
        if (cfg.enableSmelter) registerSmelterHeartRecipe();
        if (cfg.enableCourier) registerCourierHeartRecipe();
        if (cfg.enableAlchemist) registerAlchemistHeartRecipe();
        if (cfg.enableFisher) registerFisherHeartRecipe();
        golemMenu = new GolemMenu(this);
        courierMenu = new CourierMenu(this);
        alchemistMenu = new AlchemistMenu(this);
        tagPrompt = new TagPrompt(this);
        navMesh = new io.github.pushkindesu.lavagolem.nav.NavMesh(cfg.navChunkCacheSeconds);
        navigation = new io.github.pushkindesu.lavagolem.nav.Navigation(this, navMesh);

        getServer().getPluginManager().registerEvents(new HeartUseListener(this), this);
        getServer().getPluginManager().registerEvents(golemMenu, this);
        getServer().getPluginManager().registerEvents(courierMenu, this);
        getServer().getPluginManager().registerEvents(alchemistMenu, this);
        getServer().getPluginManager().registerEvents(tagPrompt, this);
        getServer().getPluginManager().registerEvents(
                new io.github.pushkindesu.lavagolem.nav.NavMeshListener(navMesh, navigation), this);

        getCommand("removegolems").setExecutor((sender, command, label, args) -> {
            int count = 0;
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntitiesByClass(Mob.class)) {
                    if (entity.getPersistentDataContainer().has(golemEntityKey, PersistentDataType.BYTE)) {
                        cleanedUpGolems.remove(entity.getUniqueId());
                        entity.remove();
                        count++;
                    }
                }
            }
            sender.sendMessage(msg.get("removed-count", Map.of("count", String.valueOf(count))));
            return true;
        });

        getCommand("golemdebug").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player p)) { sender.sendMessage("In-game only."); return true; }

            // No argument: the original behaviour, unchanged — walk up to a golem and toggle it.
            if (args.length == 0) {
                Mob nearest = null;
                double best = Double.MAX_VALUE;
                for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), 12, 12, 12)) {
                    if (!(e instanceof Mob m)) continue;
                    if (!m.getPersistentDataContainer().has(golemEntityKey, PersistentDataType.BYTE)) continue;
                    double d = e.getLocation().distanceSquared(p.getLocation());
                    if (d < best) { best = d; nearest = m; }
                }
                if (nearest == null) {
                    p.sendMessage(Component.text("No golem within 12 blocks.", NamedTextColor.RED));
                    return true;
                }
                if (debugWatchers.remove(nearest.getUniqueId()) != null) {
                    p.sendMessage(Component.text("Debug OFF for the nearest golem.", NamedTextColor.YELLOW));
                    logDebugToggleMarker(nearest, false, p);
                } else {
                    debugWatchers.put(nearest.getUniqueId(), p.getUniqueId());
                    p.sendMessage(Component.text("Debug ON for the nearest golem — "
                            + debugOutputHint() + ".", NamedTextColor.GREEN));
                    logDebugToggleMarker(nearest, true, p);
                }
                return true;
            }

            // "all": every golem on the server, including ones that spawn later — a separate flag
            // rather than one debugWatchers entry per golem, so it doesn't need re-arming as new
            // golems appear and doesn't need enumerating the whole server just to switch it off.
            if (args[0].equalsIgnoreCase("all")) {
                if (debugAllWatcher != null) {
                    debugAllWatcher = null;
                    p.sendMessage(Component.text("Debug OFF for all golems.", NamedTextColor.YELLOW));
                    logDebugBroadMarker("ALL golems", false, p, 0);
                } else {
                    debugAllWatcher = p.getUniqueId();
                    int count = countGolems(null);
                    p.sendMessage(Component.text("Debug ON for ALL golems (" + count
                            + " currently) — " + debugOutputHint() + ".", NamedTextColor.GREEN));
                    logDebugBroadMarker("ALL golems", true, p, count);
                }
                return true;
            }

            // A specific role, e.g. "courier" or "couriers" — same idea as "all" but scoped down.
            String role = resolveDebugRole(args[0]);
            if (role == null) {
                p.sendMessage(Component.text("Unknown golem role '" + args[0]
                        + "'. Use: all, lava_hauler, smelter, courier, alchemist, fisher.", NamedTextColor.RED));
                return true;
            }
            if (debugRoleWatchers.remove(role) != null) {
                p.sendMessage(Component.text("Debug OFF for role " + role + ".", NamedTextColor.YELLOW));
                logDebugBroadMarker("role " + role, false, p, 0);
            } else {
                debugRoleWatchers.put(role, p.getUniqueId());
                int count = countGolems(role);
                p.sendMessage(Component.text("Debug ON for role " + role + " (" + count
                        + " currently) — " + debugOutputHint() + ".", NamedTextColor.GREEN));
                logDebugBroadMarker("role " + role, true, p, count);
            }
            return true;
        });
        getCommand("golemdebug").setTabCompleter((sender, command, alias, args) -> {
            if (args.length != 1) return List.of();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = List.of("all", "lava_hauler", "smelter", "courier", "alchemist", "fisher");
            List<String> out = new ArrayList<>();
            for (String o : options) if (o.startsWith(prefix)) out.add(o);
            return out;
        });

        getCommand("golemstats").setExecutor((sender, command, label, args) -> {
            int golems = 0;
            int totalLava = 0;
            int totalBuckets = 0;
            int totalSmelted = 0;
            int totalCaught = 0;
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntitiesByClass(Mob.class)) {
                    if (!entity.getPersistentDataContainer().has(golemEntityKey, PersistentDataType.BYTE)) continue;
                    golems++;
                    totalLava += entity.getPersistentDataContainer()
                            .getOrDefault(lavaDeliveredKey, PersistentDataType.INTEGER, 0);
                    totalBuckets += entity.getPersistentDataContainer()
                            .getOrDefault(bucketsTakenKey, PersistentDataType.INTEGER, 0);
                    totalSmelted += entity.getPersistentDataContainer()
                            .getOrDefault(itemsSmeltedKey, PersistentDataType.INTEGER, 0);
                    totalCaught += entity.getPersistentDataContainer()
                            .getOrDefault(fishCaughtKey, PersistentDataType.INTEGER, 0)
                            + entity.getPersistentDataContainer()
                            .getOrDefault(treasureCaughtKey, PersistentDataType.INTEGER, 0);
                }
            }
            sender.sendMessage(Component.text()
                    .append(Component.text(msg.get("total-stats-header"), NamedTextColor.GOLD))
                    .append(Component.newline())
                    .append(Component.text(msg.get("total-alive"), NamedTextColor.GRAY))
                    .append(Component.text(golems, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(msg.get("total-delivered"), NamedTextColor.GRAY))
                    .append(Component.text(totalLava, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(msg.get("total-buckets"), NamedTextColor.GRAY))
                    .append(Component.text(totalBuckets, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(msg.get("total-smelted"), NamedTextColor.GRAY))
                    .append(Component.text(totalSmelted, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(msg.get("total-caught"), NamedTextColor.GRAY))
                    .append(Component.text(totalCaught, NamedTextColor.WHITE))
                    .build());
            return true;
        });

        golemTicker = new GolemTicker(this);
        // Navigation traces through GolemTicker's existing /golemdebug machinery rather than
        // duplicating it -- wired here since GolemTicker (and its debugWatchers plumbing) only exists
        // from this point on.
        navigation.setTracer(golemTicker::traceFromNav);
        golemTicker.runTaskTimer(this, 20L, cfg.tickPeriod);

        getLogger().info("LavaGolem enabled.");
    }

    @Override
    public void onDisable() {
        // Stops the periodic drain and does one last synchronous flush so the tail of whatever was
        // being traced when the server stopped actually makes it to disk instead of being lost.
        if (debugLog != null) debugLog.shutdown();

        // The nav worker pool holds daemon threads, so the JVM wouldn't hang on them regardless —
        // but shutting it down explicitly means a /reload doesn't quietly accumulate a second pool.
        if (navigation != null) navigation.shutdown();

        // Every recipe we add must be removed here, or a /reload leaves the old one registered and
        // addRecipe rejects the new one as a duplicate key — the heart then silently stops crafting.
        Bukkit.removeRecipe(new NamespacedKey(this, "golem_heart_recipe"));
        Bukkit.removeRecipe(new NamespacedKey(this, "smelter_heart_recipe"));
        Bukkit.removeRecipe(new NamespacedKey(this, "courier_heart_recipe"));
        Bukkit.removeRecipe(new NamespacedKey(this, "alchemist_heart_recipe"));
        Bukkit.removeRecipe(new NamespacedKey(this, "fisher_heart_recipe"));
    }

    /** Builds the sets of valid inputs per furnace kind (regular / blast / smoker). */
    private void buildSmeltableInputs() {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            // BlastingRecipe and SmokingRecipe are checked before FurnaceRecipe because they are
            // NOT subclasses of it (all three extend CookingRecipe).
            if (recipe instanceof BlastingRecipe blasting) {
                blastableInputs.add(blasting.getInput().getType());
            } else if (recipe instanceof SmokingRecipe smoking) {
                smokableInputs.add(smoking.getInput().getType());
            } else if (recipe instanceof FurnaceRecipe furnaceRecipe) {
                smeltableInputs.add(furnaceRecipe.getInput().getType());
            }
        }
    }

    /**
     * Clears vanilla AI of a golem: removes all goals from goalSelector/targetSelector
     * and clears Brain behaviors and sensors via reflection.
     */
    public void cleanupGolemAi(org.bukkit.entity.Mob golem) {
        try {
            java.lang.reflect.Method getHandle = golem.getClass().getMethod("getHandle");
            Object nmsMob = getHandle.invoke(golem);

            // Clear goalSelector and targetSelector
            for (String fieldName : new String[]{"goalSelector", "targetSelector"}) {
                Class<?> cls = nmsMob.getClass();
                java.lang.reflect.Field field = null;
                while (cls != null && field == null) {
                    try { field = cls.getDeclaredField(fieldName); }
                    catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
                }
                if (field != null) {
                    field.setAccessible(true);
                    Object selector = field.get(nmsMob);
                    selector.getClass()
                            .getMethod("removeAllGoals", java.util.function.Predicate.class)
                            .invoke(selector, (java.util.function.Predicate<Object>) g -> true);
                }
            }

            // Clear Brain behaviors and sensors
            Object brain = nmsMob.getClass().getMethod("getBrain").invoke(nmsMob);
            for (java.lang.reflect.Field f : brain.getClass().getDeclaredFields()) {
                if (java.util.Map.class.isAssignableFrom(f.getType())
                        && f.getName().toLowerCase().contains("behavior")) {
                    f.setAccessible(true);
                    ((java.util.Map<?, ?>) f.get(brain)).clear();
                    break;
                }
            }
            for (java.lang.reflect.Field f : brain.getClass().getDeclaredFields()) {
                if (java.util.Map.class.isAssignableFrom(f.getType())
                        && f.getName().toLowerCase().contains("sensor")) {
                    f.setAccessible(true);
                    ((java.util.Map<?, ?>) f.get(brain)).clear();
                    break;
                }
            }
        } catch (Throwable t) {
            getLogger().warning("[LG] AI cleanup failed for " + golem.getUniqueId()
                    + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Creates the Golem Heart item for the default (LAVA_HAULER) role. */
    public ItemStack createGolemHeart() {
        return createGolemHeart(ROLE_HAULER);
    }

    /** Creates the Golem Heart item (copper golem spawn egg with PDC tag) for the given role. */
    public ItemStack createGolemHeart(String role) {
        String prefix = ROLE_SMELTER.equals(role) ? "smelter-heart"
                : ROLE_COURIER.equals(role) ? "courier-heart"
                : ROLE_ALCHEMIST.equals(role) ? "alchemist-heart"
                : ROLE_FISHER.equals(role) ? "fisher-heart"
                : "heart";
        ItemStack item = new ItemStack(Material.COPPER_GOLEM_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(msg.get(prefix + "-display-name"), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(msg.get(prefix + "-lore-1"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(msg.get(prefix + "-lore-2"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(heartItemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(heartRoleKey, PersistentDataType.STRING, role);
        item.setItemMeta(meta);
        return item;
    }

    private void registerHeartRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "golem_heart_recipe"),
                createGolemHeart(ROLE_HAULER)
        );
        // Pattern: CRC / RLR / CRC (copper corners, redstone cross, lava bucket center)
        recipe.shape("CRC", "RLR", "CRC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('L', Material.LAVA_BUCKET);
        Bukkit.addRecipe(recipe);
    }

    private void registerSmelterHeartRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "smelter_heart_recipe"),
                createGolemHeart(ROLE_SMELTER)
        );
        // Pattern: CRC / RLR / CRC (copper corners, redstone cross, furnace center)
        recipe.shape("CRC", "RLR", "CRC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('L', Material.FURNACE);
        Bukkit.addRecipe(recipe);
    }

    private void registerCourierHeartRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "courier_heart_recipe"),
                createGolemHeart(ROLE_COURIER)
        );
        // Pattern: CRC / RLR / CRC (copper corners, redstone cross, hopper center)
        recipe.shape("CRC", "RLR", "CRC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('L', Material.HOPPER);
        Bukkit.addRecipe(recipe);
    }

    private void registerAlchemistHeartRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "alchemist_heart_recipe"),
                createGolemHeart(ROLE_ALCHEMIST)
        );
        // Pattern: CRC / RLR / CRC (copper corners, redstone cross, brewing stand center)
        recipe.shape("CRC", "RLR", "CRC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('L', Material.BREWING_STAND);
        Bukkit.addRecipe(recipe);
    }

    private void registerFisherHeartRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "fisher_heart_recipe"),
                createGolemHeart(ROLE_FISHER)
        );
        // Pattern: CRC / RLR / CRC (copper corners, redstone cross, fishing rod center)
        recipe.shape("CRC", "RLR", "CRC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('L', Material.FISHING_ROD);
        Bukkit.addRecipe(recipe);
    }
}
