package io.github.pushkindesu.lavagolem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
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

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
    public enum GolemTag { BUCKETS, LAVA, SMELT, FUEL, OUTPUT, BREW }

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

    /** Smelter work modes (configurable per-golem via its GUI). */
    public static final String MODE_BALANCED = "BALANCED";
    public static final String MODE_LOAD_ONLY = "LOAD_ONLY";
    public static final String MODE_COLLECT_ONLY = "COLLECT_ONLY";

    /** UUIDs of golems whose vanilla AI has already been cleaned up in this session. */
    public final Set<UUID> cleanedUpGolems = ConcurrentHashMap.newKeySet();

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

    /** Golems whose settings menu is currently open. The ticker holds any such golem still, whatever
     *  its role, so it doesn't wander off (or act on a half-configured route) while you're in its menu. */
    private final java.util.Set<java.util.UUID> openMenus = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Marks a golem's menu as open and halts its current movement so it stops on the spot. */
    public void markMenuOpen(org.bukkit.entity.Mob golem) {
        openMenus.add(golem.getUniqueId());
        golem.getPathfinder().stopPathfinding();
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
        cfg = new PluginConfig(this);
        msg = new Messages(this);

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

        buildSmeltableInputs();
        registerHeartRecipe();
        registerSmelterHeartRecipe();
        registerCourierHeartRecipe();
        registerAlchemistHeartRecipe();
        golemMenu = new GolemMenu(this);
        courierMenu = new CourierMenu(this);
        alchemistMenu = new AlchemistMenu(this);
        tagPrompt = new TagPrompt(this);
        getServer().getPluginManager().registerEvents(new HeartUseListener(this), this);
        getServer().getPluginManager().registerEvents(golemMenu, this);
        getServer().getPluginManager().registerEvents(courierMenu, this);
        getServer().getPluginManager().registerEvents(alchemistMenu, this);
        getServer().getPluginManager().registerEvents(tagPrompt, this);

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

        getCommand("golemstats").setExecutor((sender, command, label, args) -> {
            int golems = 0;
            int totalLava = 0;
            int totalBuckets = 0;
            int totalSmelted = 0;
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
                    .build());
            return true;
        });

        golemTicker = new GolemTicker(this);
        golemTicker.runTaskTimer(this, 20L, cfg.tickPeriod);

        getLogger().info("LavaGolem enabled.");
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(new NamespacedKey(this, "golem_heart_recipe"));
        Bukkit.removeRecipe(new NamespacedKey(this, "smelter_heart_recipe"));
        Bukkit.removeRecipe(new NamespacedKey(this, "courier_heart_recipe"));
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
}
