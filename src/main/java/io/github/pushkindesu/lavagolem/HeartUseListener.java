package io.github.pushkindesu.lavagolem;

import io.papermc.paper.world.WeatheringCopperState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class HeartUseListener implements Listener {

    private final LavaGolemPlugin plugin;

    public HeartUseListener(LavaGolemPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getClickedBlock() == null) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COPPER_GOLEM_SPAWN_EGG) return;
        if (!item.hasItemMeta()) return;

        // Only our spawn egg (with PDC tag); vanilla eggs have no tag
        Byte tag = item.getItemMeta().getPersistentDataContainer()
                .get(plugin.heartItemKey, PersistentDataType.BYTE);
        if (tag == null) return;

        String role = item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(plugin.heartRoleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);

        Player player = event.getPlayer();
        event.setCancelled(true);

        Location loc = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        spawnLavaGolem(loc, role);

        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }

        player.sendMessage(Component.text(plugin.msg.get("golem-spawned"), NamedTextColor.GOLD));
    }

    private void spawnLavaGolem(Location loc, String role) {
        CopperGolem golem = (CopperGolem) loc.getWorld().spawnEntity(loc, EntityType.COPPER_GOLEM);

        // Set all PDC tags first (before any other Paper/CraftBukkit calls)
        golem.getPersistentDataContainer().set(
                plugin.golemEntityKey, PersistentDataType.BYTE, (byte) 1);
        golem.getPersistentDataContainer().set(
                plugin.roleKey, PersistentDataType.STRING, role);
        golem.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "state"),
                PersistentDataType.STRING,
                LavaGolemPlugin.ROLE_SMELTER.equals(role) ? "SMELTER_IDLE" : "SEEKING_BUCKET");
        golem.getPersistentDataContainer().set(
                plugin.lavaDeliveredKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.bucketsTakenKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.itemsSmeltedKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.createdAtKey, PersistentDataType.LONG, System.currentTimeMillis());

        golem.setInvulnerable(true);
        golem.setPersistent(true);
        golem.setRemoveWhenFarAway(false);
        golem.setVisualFire(true);
        golem.customName(Component.text(plugin.msg.get(
                LavaGolemPlugin.ROLE_SMELTER.equals(role) ? "smelter-name" : "golem-name"),
                NamedTextColor.GOLD));
        golem.setCustomNameVisible(true);
        golem.setWeatheringState(WeatheringCopperState.UNAFFECTED);

        plugin.cleanupGolemAi(golem);
        plugin.cleanedUpGolems.add(golem.getUniqueId());
    }

    /** Recreates a golem that was loaded from disk, restoring all its state. */
    public void spawnLavaGolemRestored(
            Location loc, String role, String mode, String state, String target,
            int lavaDelivered, int bucketsTaken, int itemsSmelted, long createdAt,
            ItemStack heldItem, String jobFurnace, Integer jobAmount, String jobMaterial) {
        CopperGolem golem =
                (CopperGolem) loc.getWorld().spawnEntity(loc, EntityType.COPPER_GOLEM);

        var pdc = golem.getPersistentDataContainer();
        pdc.set(plugin.golemEntityKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(plugin.roleKey, PersistentDataType.STRING,
                role != null ? role : LavaGolemPlugin.ROLE_HAULER);
        if (mode != null) {
            pdc.set(plugin.modeKey, PersistentDataType.STRING, mode);
        }
        pdc.set(new NamespacedKey(plugin, "state"),
                PersistentDataType.STRING, state != null ? state : "SEEKING_BUCKET");
        if (target != null) {
            pdc.set(new NamespacedKey(plugin, "target"), PersistentDataType.STRING, target);
        }
        if (jobFurnace != null) {
            pdc.set(new NamespacedKey(plugin, "job_furnace"), PersistentDataType.STRING, jobFurnace);
        }
        if (jobAmount != null) {
            pdc.set(new NamespacedKey(plugin, "job_amount"), PersistentDataType.INTEGER, jobAmount);
        }
        if (jobMaterial != null) {
            pdc.set(new NamespacedKey(plugin, "job_material"), PersistentDataType.STRING, jobMaterial);
        }
        pdc.set(plugin.lavaDeliveredKey, PersistentDataType.INTEGER, lavaDelivered);
        pdc.set(plugin.bucketsTakenKey, PersistentDataType.INTEGER, bucketsTaken);
        pdc.set(plugin.itemsSmeltedKey, PersistentDataType.INTEGER, itemsSmelted);
        pdc.set(plugin.createdAtKey, PersistentDataType.LONG, createdAt);

        golem.setInvulnerable(true);
        golem.setPersistent(true);
        golem.setRemoveWhenFarAway(false);
        golem.setVisualFire(true);
        golem.customName(Component.text(plugin.msg.get(
                LavaGolemPlugin.ROLE_SMELTER.equals(role) ? "smelter-name" : "golem-name"),
                NamedTextColor.GOLD));
        golem.setCustomNameVisible(true);
        golem.setWeatheringState(WeatheringCopperState.UNAFFECTED);

        plugin.cleanupGolemAi(golem);
        plugin.cleanedUpGolems.add(golem.getUniqueId());

        if (heldItem != null && heldItem.getType() != Material.AIR) {
            golem.getEquipment().setItemInMainHand(heldItem);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        java.util.List<Mob> ours = new java.util.ArrayList<>();
        for (org.bukkit.entity.Entity e : event.getEntities()) {
            if (!(e instanceof Mob mob)) continue;
            if (!mob.getPersistentDataContainer().has(plugin.golemEntityKey, PersistentDataType.BYTE)) continue;
            ours.add(mob);
        }
        if (ours.isEmpty()) return;

        // Run next tick so the entity is fully initialized before we recreate it
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Mob old : ours) {
                if (!old.isValid()) continue;

                var pdc = old.getPersistentDataContainer();
                Location loc = old.getLocation();
                String role = pdc.getOrDefault(plugin.roleKey,
                        PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);
                String mode = pdc.get(plugin.modeKey, PersistentDataType.STRING);
                String state = pdc.getOrDefault(
                        new NamespacedKey(plugin, "state"),
                        PersistentDataType.STRING, "SEEKING_BUCKET");
                String storedTarget = pdc.get(
                        new NamespacedKey(plugin, "target"),
                        PersistentDataType.STRING);
                String jobFurnace = pdc.get(
                        new NamespacedKey(plugin, "job_furnace"),
                        PersistentDataType.STRING);
                Integer jobAmount = pdc.get(
                        new NamespacedKey(plugin, "job_amount"),
                        PersistentDataType.INTEGER);
                String jobMaterial = pdc.get(
                        new NamespacedKey(plugin, "job_material"),
                        PersistentDataType.STRING);
                int lavaDelivered = pdc.getOrDefault(plugin.lavaDeliveredKey,
                        PersistentDataType.INTEGER, 0);
                int bucketsTaken = pdc.getOrDefault(plugin.bucketsTakenKey,
                        PersistentDataType.INTEGER, 0);
                int itemsSmelted = pdc.getOrDefault(plugin.itemsSmeltedKey,
                        PersistentDataType.INTEGER, 0);
                long createdAt = pdc.getOrDefault(plugin.createdAtKey,
                        PersistentDataType.LONG, System.currentTimeMillis());
                ItemStack held = (old.getEquipment() != null)
                        ? old.getEquipment().getItemInMainHand() : null;

                plugin.cleanedUpGolems.remove(old.getUniqueId());
                old.remove();

                spawnLavaGolemRestored(loc, role, mode, state, storedTarget,
                        lavaDelivered, bucketsTaken, itemsSmelted, createdAt, held,
                        jobFurnace, jobAmount, jobMaterial);
            }
        });
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (org.bukkit.entity.Entity e : event.getEntities()) {
            if (!(e instanceof Mob mob)) continue;
            if (!mob.getPersistentDataContainer().has(plugin.golemEntityKey, PersistentDataType.BYTE)) continue;
            plugin.cleanedUpGolems.remove(mob.getUniqueId());
        }
    }

    @EventHandler
    public void onGolemInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Mob golem)) return;
        Byte tag = golem.getPersistentDataContainer()
                .get(plugin.golemEntityKey, PersistentDataType.BYTE);
        if (tag == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()
                && player.getInventory().getItemInMainHand().getType() == Material.AIR) {
            // Sneak + empty hand = disassemble
            String role = golem.getPersistentDataContainer().getOrDefault(
                    plugin.roleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);
            golem.getWorld().dropItemNaturally(golem.getLocation(), plugin.createGolemHeart(role));
            ItemStack inHand = golem.getEquipment().getItemInMainHand();
            if (inHand != null && inHand.getType() != Material.AIR) {
                golem.getWorld().dropItemNaturally(golem.getLocation(), inHand);
            }
            plugin.cleanedUpGolems.remove(golem.getUniqueId());
            golem.remove();
            player.sendMessage(Component.text(plugin.msg.get("golem-disassembled"), NamedTextColor.GRAY));

        } else if (!player.isSneaking()
                && player.getInventory().getItemInMainHand().getType() == Material.AIR) {
            // Empty hand (no sneak): smelter opens its settings/stats GUI; hauler shows chat stats.
            var pdc = golem.getPersistentDataContainer();
            String role = pdc.getOrDefault(plugin.roleKey, PersistentDataType.STRING,
                    LavaGolemPlugin.ROLE_HAULER);
            if (LavaGolemPlugin.ROLE_SMELTER.equals(role)) {
                plugin.golemMenu.open(player, golem);
                return;
            }
            long createdAt = pdc.getOrDefault(plugin.createdAtKey, PersistentDataType.LONG, 0L);

            String dateStr = (createdAt == 0L)
                    ? plugin.msg.get("stats-since-unknown")
                    : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        .format(LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(createdAt), ZoneId.systemDefault()));

            int lavaDelivered = pdc.getOrDefault(plugin.lavaDeliveredKey, PersistentDataType.INTEGER, 0);
            int bucketsTaken  = pdc.getOrDefault(plugin.bucketsTakenKey,  PersistentDataType.INTEGER, 0);
            player.sendMessage(Component.text()
                    .append(Component.text(plugin.msg.get("stats-header"), NamedTextColor.GOLD))
                    .append(Component.newline())
                    .append(Component.text(plugin.msg.get("stats-delivered"), NamedTextColor.GRAY))
                    .append(Component.text(lavaDelivered, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(plugin.msg.get("stats-buckets"), NamedTextColor.GRAY))
                    .append(Component.text(bucketsTaken, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text(plugin.msg.get("stats-since"), NamedTextColor.GRAY))
                    .append(Component.text(dateStr, NamedTextColor.WHITE))
                    .build());
        }
    }
}
