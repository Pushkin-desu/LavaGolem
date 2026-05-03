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
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LavaGolemPlugin extends JavaPlugin {

    // PDC keys
    public NamespacedKey heartItemKey;
    public NamespacedKey golemEntityKey;
    public NamespacedKey lavaDeliveredKey;
    public NamespacedKey bucketsTakenKey;
    public NamespacedKey createdAtKey;

    /** UUIDs of golems whose vanilla AI has already been cleaned up in this session. */
    public final Set<UUID> cleanedUpGolems = ConcurrentHashMap.newKeySet();

    public PluginConfig cfg;
    public Messages msg;

    @Override
    public void onEnable() {
        cfg = new PluginConfig(this);
        msg = new Messages(this);

        heartItemKey     = new NamespacedKey(this, "golem_heart");
        golemEntityKey   = new NamespacedKey(this, "lava_golem");
        lavaDeliveredKey = new NamespacedKey(this, "lava_delivered");
        bucketsTakenKey  = new NamespacedKey(this, "buckets_taken");
        createdAtKey     = new NamespacedKey(this, "created_at");

        if (cfg.bstats) {
            new org.bstats.bukkit.Metrics(this, 31068);
        }

        registerHeartRecipe();
        getServer().getPluginManager().registerEvents(new HeartUseListener(this), this);

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
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntitiesByClass(Mob.class)) {
                    if (!entity.getPersistentDataContainer().has(golemEntityKey, PersistentDataType.BYTE)) continue;
                    golems++;
                    totalLava += entity.getPersistentDataContainer()
                            .getOrDefault(lavaDeliveredKey, PersistentDataType.INTEGER, 0);
                    totalBuckets += entity.getPersistentDataContainer()
                            .getOrDefault(bucketsTakenKey, PersistentDataType.INTEGER, 0);
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
                    .build());
            return true;
        });

        new GolemTicker(this).runTaskTimer(this, 20L, cfg.tickPeriod);

        getLogger().info("LavaGolem enabled.");
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(new NamespacedKey(this, "golem_heart_recipe"));
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

    /** Creates the Golem Heart item (copper golem spawn egg with PDC tag). */
    public ItemStack createGolemHeart() {
        ItemStack item = new ItemStack(Material.COPPER_GOLEM_SPAWN_EGG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(msg.get("heart-display-name"), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(msg.get("heart-lore-1"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(msg.get("heart-lore-2"), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(heartItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void registerHeartRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "golem_heart_recipe"),
                createGolemHeart()
        );
        // Pattern: CRC / RLR / CRC (copper corners, redstone cross, lava bucket center)
        recipe.shape("CRC", "RLR", "CRC");
        recipe.setIngredient('C', Material.COPPER_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('L', Material.LAVA_BUCKET);
        Bukkit.addRecipe(recipe);
    }
}
