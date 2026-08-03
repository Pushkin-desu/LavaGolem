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

        // Refuse to place a role the server has switched off (leaves the heart in hand).
        if (!plugin.isRoleEnabled(role)) {
            player.sendMessage(Component.text(plugin.msg.get("role-disabled"), NamedTextColor.RED));
            return;
        }

        Location loc = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        spawnLavaGolem(loc, role);

        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }

        player.sendMessage(Component.text(plugin.msg.get("golem-spawned"), NamedTextColor.GOLD));
    }

    private static String initialState(String role) {
        if (LavaGolemPlugin.ROLE_SMELTER.equals(role)) return "SMELTER_IDLE";
        if (LavaGolemPlugin.ROLE_COURIER.equals(role)) return "COURIER_IDLE";
        if (LavaGolemPlugin.ROLE_ALCHEMIST.equals(role)) return "ALCHEMIST_IDLE";
        if (LavaGolemPlugin.ROLE_FISHER.equals(role)) return "FISHER_IDLE";
        return "SEEKING_BUCKET";
    }

    private static String roleNameKey(String role) {
        if (LavaGolemPlugin.ROLE_SMELTER.equals(role)) return "smelter-name";
        if (LavaGolemPlugin.ROLE_COURIER.equals(role)) return "courier-name";
        if (LavaGolemPlugin.ROLE_ALCHEMIST.equals(role)) return "alchemist-name";
        if (LavaGolemPlugin.ROLE_FISHER.equals(role)) return "fisher-name";
        return "golem-name";
    }

    /** Only the Lava Golem burns — it hauls lava, so the flame reads as "that's the lava one" from
     *  a distance, where the name tag doesn't. Purely visual: it deals no damage. */
    private static boolean burns(String role) {
        return role == null || LavaGolemPlugin.ROLE_HAULER.equals(role);
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
                PersistentDataType.STRING, initialState(role));
        golem.getPersistentDataContainer().set(
                plugin.lavaDeliveredKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.bucketsTakenKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.itemsSmeltedKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.itemsMovedKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.potionsBrewedKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer().set(
                plugin.createdAtKey, PersistentDataType.LONG, System.currentTimeMillis());

        golem.setInvulnerable(true);
        golem.setPersistent(true);
        golem.setRemoveWhenFarAway(false);
        golem.setVisualFire(burns(role));
        golem.customName(Component.text(plugin.msg.get(roleNameKey(role)), NamedTextColor.GOLD));
        golem.setCustomNameVisible(true);
        golem.setWeatheringState(WeatheringCopperState.UNAFFECTED);

        plugin.cleanupGolemAi(golem);
        plugin.cleanedUpGolems.add(golem.getUniqueId());
    }

    /**
     * Recreates a golem loaded from disk, restoring ALL of its PDC state by copying the raw
     * container (so any current or future key survives a chunk reload without hand-threading).
     */
    public void spawnLavaGolemRestored(Location loc, byte[] pdcBytes, String role,
                                       ItemStack heldItem, ItemStack offHandItem) {
        CopperGolem golem =
                (CopperGolem) loc.getWorld().spawnEntity(loc, EntityType.COPPER_GOLEM);

        var pdc = golem.getPersistentDataContainer();
        boolean restored = false;
        if (pdcBytes != null) {
            try { pdc.readFromBytes(pdcBytes, true); restored = true; }
            catch (java.io.IOException ignored) { /* fall back below */ }
        }
        if (!restored) {
            // Fallback: keep it recognised as ours with a sane starting state.
            pdc.set(plugin.golemEntityKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(plugin.roleKey, PersistentDataType.STRING,
                    role != null ? role : LavaGolemPlugin.ROLE_HAULER);
            pdc.set(new NamespacedKey(plugin, "state"), PersistentDataType.STRING, initialState(role));
        }

        golem.setInvulnerable(true);
        golem.setPersistent(true);
        golem.setRemoveWhenFarAway(false);
        golem.setVisualFire(burns(role));
        golem.customName(Component.text(plugin.msg.get(roleNameKey(role)), NamedTextColor.GOLD));
        golem.setCustomNameVisible(true);
        golem.setWeatheringState(WeatheringCopperState.UNAFFECTED);

        plugin.cleanupGolemAi(golem);
        plugin.cleanedUpGolems.add(golem.getUniqueId());

        if (heldItem != null && heldItem.getType() != Material.AIR) {
            golem.getEquipment().setItemInMainHand(heldItem);
        }
        // The fisher's rod lives in the off-hand and must survive a chunk reload with it — a golem
        // that came back empty-handed would quietly fetch a fresh rod and the old one would vanish.
        if (offHandItem != null && offHandItem.getType() != Material.AIR) {
            golem.getEquipment().setItemInOffHand(offHandItem);
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
                byte[] pdcBytes;
                try { pdcBytes = pdc.serializeToBytes(); }
                catch (java.io.IOException e) { pdcBytes = null; }
                ItemStack held = (old.getEquipment() != null)
                        ? old.getEquipment().getItemInMainHand() : null;
                ItemStack offHand = (old.getEquipment() != null)
                        ? old.getEquipment().getItemInOffHand() : null;

                plugin.cleanedUpGolems.remove(old.getUniqueId());
                old.remove();

                spawnLavaGolemRestored(loc, pdcBytes, role, held, offHand);
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
            // The fisher holds its rod in the off-hand; give that back too, part-worn as it is.
            ItemStack offHand = golem.getEquipment().getItemInOffHand();
            if (offHand != null && offHand.getType() != Material.AIR) {
                golem.getWorld().dropItemNaturally(golem.getLocation(), offHand);
            }
            plugin.cleanedUpGolems.remove(golem.getUniqueId());
            golem.remove();
            player.sendMessage(Component.text(plugin.msg.get("golem-disassembled"), NamedTextColor.GRAY));

        } else if (!player.isSneaking()
                && player.getInventory().getItemInMainHand().getType() == Material.AIR) {
            // Empty hand (no sneak): open the golem's GUI (courier = routes, others = stats/settings).
            String role = golem.getPersistentDataContainer().getOrDefault(
                    plugin.roleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);
            if (LavaGolemPlugin.ROLE_COURIER.equals(role)) {
                plugin.courierMenu.open(player, golem);
            } else if (LavaGolemPlugin.ROLE_ALCHEMIST.equals(role)) {
                plugin.alchemistMenu.open(player, golem); // which potions to brew + stats
            } else {
                // smelter (modes + stats), fisher (tags + stats), hauler (stats)
                plugin.golemMenu.open(player, golem);
            }
        }
    }
}
