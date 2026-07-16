package io.github.pushkindesu.lavagolem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chest-like settings/stats window for a Smelter golem, opened by right-clicking it.
 * Mode buttons in slots 0-2 write the golem's work mode to its PDC; slot 4 shows stats.
 */
public class GolemMenu implements Listener {

    private final LavaGolemPlugin plugin;

    public GolemMenu(LavaGolemPlugin plugin) {
        this.plugin = plugin;
    }

    /** Holds the controlled golem's UUID so click handling knows which entity to update. */
    private static final class Holder implements InventoryHolder {
        private final UUID golemId;
        private Inventory inventory;

        private Holder(UUID golemId) { this.golemId = golemId; }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    /** Opens the settings menu for the given golem. */
    public void open(Player player, Mob golem) {
        plugin.markMenuOpen(golem); // hold the golem still while its menu is open
        boolean smelter = isSmelter(golem);
        Holder holder = new Holder(golem.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 9,
                Component.text(plugin.msg.get(smelter ? "smelter-menu-title" : "hauler-menu-title"),
                        NamedTextColor.DARK_GRAY));
        holder.inventory = inv;
        render(inv, golem);
        player.openInventory(inv);
    }

    private boolean isSmelter(Mob golem) {
        return LavaGolemPlugin.ROLE_SMELTER.equals(golem.getPersistentDataContainer()
                .getOrDefault(plugin.roleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER));
    }

    private void render(Inventory inv, Mob golem) {
        ItemStack filler = filler();
        for (int i = 0; i < 9; i++) inv.setItem(i, filler);

        if (!isSmelter(golem)) {
            // Lava Hauler: stats-only view (no work modes).
            inv.setItem(4, haulerStatsItem(golem));
            return;
        }

        String current = golem.getPersistentDataContainer()
                .getOrDefault(plugin.modeKey, PersistentDataType.STRING, LavaGolemPlugin.MODE_BALANCED);
        inv.setItem(0, modeButton(Material.FURNACE, "mode-balanced-name", "mode-balanced-desc",
                LavaGolemPlugin.MODE_BALANCED.equals(current)));
        inv.setItem(1, modeButton(Material.IRON_ORE, "mode-load-name", "mode-load-desc",
                LavaGolemPlugin.MODE_LOAD_ONLY.equals(current)));
        // IRON_INGOT (not CHEST): chest items render as a 3D block model in GUIs and do NOT
        // show the enchantment glint used to mark the selected mode.
        inv.setItem(2, modeButton(Material.IRON_INGOT, "mode-collect-name", "mode-collect-desc",
                LavaGolemPlugin.MODE_COLLECT_ONLY.equals(current)));
        inv.setItem(4, statsItem(golem));
    }

    private ItemStack haulerStatsItem(Mob golem) {
        var pdc = golem.getPersistentDataContainer();
        int lava = pdc.getOrDefault(plugin.lavaDeliveredKey, PersistentDataType.INTEGER, 0);
        int buckets = pdc.getOrDefault(plugin.bucketsTakenKey, PersistentDataType.INTEGER, 0);
        long createdAt = pdc.getOrDefault(plugin.createdAtKey, PersistentDataType.LONG, 0L);
        String dateStr = (createdAt == 0L)
                ? plugin.msg.get("stats-since-unknown")
                : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneId.systemDefault()));
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get("menu-stats-name"), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(plugin.msg.get("stats-delivered") + lava, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(plugin.msg.get("stats-buckets") + buckets, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(plugin.msg.get("stats-since") + dateStr, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack modeButton(Material icon, String nameKey, String descKey, boolean selected) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get(nameKey),
                selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(plugin.msg.get(descKey), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(selected
                ? Component.text(plugin.msg.get("mode-selected"), NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)
                : Component.text(plugin.msg.get("mode-click-select"), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(selected);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack statsItem(Mob golem) {
        var pdc = golem.getPersistentDataContainer();
        int smelted = pdc.getOrDefault(plugin.itemsSmeltedKey, PersistentDataType.INTEGER, 0);
        long createdAt = pdc.getOrDefault(plugin.createdAtKey, PersistentDataType.LONG, 0L);
        String dateStr = (createdAt == 0L)
                ? plugin.msg.get("stats-since-unknown")
                : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneId.systemDefault()));

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get("menu-stats-name"), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(plugin.msg.get("stats-smelted") + smelted, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(plugin.msg.get("stats-since") + dateStr, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true); // read-only menu — never let items move
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof Holder)) return;

        String mode = switch (event.getRawSlot()) {
            case 0 -> LavaGolemPlugin.MODE_BALANCED;
            case 1 -> LavaGolemPlugin.MODE_LOAD_ONLY;
            case 2 -> LavaGolemPlugin.MODE_COLLECT_ONLY;
            default -> null;
        };
        if (mode == null) return;

        Entity ent = Bukkit.getEntity(holder.golemId);
        if (!(ent instanceof Mob golem) || !golem.isValid()) {
            event.getWhoClicked().closeInventory();
            return;
        }
        if (!isSmelter(golem)) return; // hauler view has no work modes
        golem.getPersistentDataContainer().set(plugin.modeKey, PersistentDataType.STRING, mode);
        render(event.getInventory(), golem);
        if (event.getWhoClicked() instanceof Player p) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) {
            plugin.markMenuClosed(holder.golemId); // resume the golem once its menu is closed
        }
    }
}
