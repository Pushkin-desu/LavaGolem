package io.github.pushkindesu.lavagolem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GUI for an Alchemist golem: one toggle per potion it may brew, plus the modifiers it may apply.
 * Icons are the real potion items, so the client renders each potion's own localised name.
 * The golem still only brews what the [Brew] chest actually contains — this just narrows the choice.
 */
public class AlchemistMenu implements Listener {

    private final LavaGolemPlugin plugin;

    // Slot layout (54-slot / 6 rows).
    private static final int POTION_START = 9;   // potions fill 9.. sequentially
    private static final int MOD_START = 37;     // modifiers on the 5th row
    private static final int SLOT_POWER = 45, SLOT_STATS = 53;
    private static final int SLOT_TAG_BREW = 47, SLOT_TAG_OUTPUT = 48;

    public AlchemistMenu(LavaGolemPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        private final UUID golemId;
        private Inventory inventory;
        private Holder(UUID golemId) { this.golemId = golemId; }
        @Override public Inventory getInventory() { return inventory; }
    }

    /** Slot -> the ingredient that slot toggles, rebuilt on every render. */
    private final Map<UUID, Map<Integer, Material>> slotMap = new java.util.concurrent.ConcurrentHashMap<>();

    public void open(Player player, Mob golem) {
        plugin.markMenuOpen(golem); // hold it still while its recipe list is being edited
        Holder holder = new Holder(golem.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text(plugin.msg.get("alchemist-menu-title"), NamedTextColor.DARK_GRAY));
        holder.inventory = inv;
        render(holder, golem);
        player.openInventory(inv);
    }

    private void render(Holder holder, Mob golem) {
        Inventory inv = holder.inventory;
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        Map<Integer, Material> slots = new java.util.HashMap<>();

        int slot = POTION_START;
        for (var e : GolemTicker.BREW_RESULTS.entrySet()) {
            inv.setItem(slot, potionToggle(golem, e.getKey(), e.getValue()));
            slots.put(slot, e.getKey());
            slot++;
        }

        int mod = MOD_START;
        for (Material m : GolemTicker.BREW_MODIFIER_ORDER) {
            inv.setItem(mod, modifierToggle(golem, m));
            slots.put(mod, m);
            mod++;
        }

        inv.setItem(SLOT_POWER, powerButton(golem));
        inv.setItem(SLOT_TAG_BREW,
                tagButton(golem, LavaGolemPlugin.GolemTag.BREW, Material.BREWING_STAND, "tag-brew"));
        inv.setItem(SLOT_TAG_OUTPUT,
                tagButton(golem, LavaGolemPlugin.GolemTag.OUTPUT, Material.CHEST, "tag-output"));
        inv.setItem(SLOT_STATS, statsItem(golem));
        slotMap.put(holder.golemId, slots);
    }

    /** Shows which container this alchemist looks for, and lets the player point it at its own —
     *  so a brewery and a smeltery can sit side by side without sharing one [Output]. */
    private ItemStack tagButton(Mob golem, LavaGolemPlugin.GolemTag tag, Material icon, String labelKey) {
        boolean custom = plugin.hasTagOverride(golem, tag);
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get(labelKey) + plugin.tagFor(golem, tag),
                custom ? NamedTextColor.AQUA : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (custom) {
            lore.add(Component.text(plugin.msg.get("tag-default") + plugin.defaultTag(tag),
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text(plugin.msg.get("tag-hint"), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(custom);
        item.setItemMeta(meta);
        return item;
    }

    /** The real potion item as the icon, so the client shows "Potion of Healing" in its own language. */
    private ItemStack potionToggle(Mob golem, Material ingredient, PotionType type) {
        boolean on = plugin.golemTicker.alchemyAllows(golem, ingredient);
        ItemStack item = new ItemStack(Material.POTION);
        if (item.getItemMeta() instanceof PotionMeta pm) {
            pm.setBasePotionType(type);
            applyToggleLore(pm, on, prettyName(ingredient));
            item.setItemMeta(pm);
        }
        return item;
    }

    private ItemStack modifierToggle(Mob golem, Material m) {
        boolean on = plugin.golemTicker.alchemyAllows(golem, m);
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get(modKey(m)), on ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        applyToggleLore(meta, on, prettyName(m));
        item.setItemMeta(meta);
        return item;
    }

    private static String modKey(Material m) {
        return switch (m) {
            case REDSTONE -> "alchemy-mod-extend";
            case GLOWSTONE_DUST -> "alchemy-mod-amplify";
            case GUNPOWDER -> "alchemy-mod-splash";
            case DRAGON_BREATH -> "alchemy-mod-linger";
            default -> "alchemy-mod-corrupt"; // FERMENTED_SPIDER_EYE
        };
    }

    private void applyToggleLore(ItemMeta meta, boolean on, String ingredientName) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(plugin.msg.get("alchemy-needs") + ingredientName, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(on
                ? Component.text(plugin.msg.get("alchemy-brewing"), NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)
                : Component.text(plugin.msg.get("alchemy-skipped"), NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(plugin.msg.get("alchemy-toggle-hint"), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(on);
    }

    private ItemStack powerButton(Mob golem) {
        boolean paused = plugin.isPaused(golem);
        ItemStack item = new ItemStack(paused ? Material.RED_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get(paused ? "power-paused" : "power-working"),
                paused ? NamedTextColor.RED : NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(plugin.msg.get("power-hint"), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack statsItem(Mob golem) {
        var pdc = golem.getPersistentDataContainer();
        int brewed = pdc.getOrDefault(plugin.potionsBrewedKey, PersistentDataType.INTEGER, 0);
        long createdAt = pdc.getOrDefault(plugin.createdAtKey, PersistentDataType.LONG, 0L);
        String since = (createdAt == 0L)
                ? plugin.msg.get("stats-since-unknown")
                : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                        .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneId.systemDefault()));
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get("menu-stats-name"), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(plugin.msg.get("stats-brewed") + brewed, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(plugin.msg.get("stats-since") + since, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true); // read-only GUI: never let items actually move
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof Holder)) return;

        Entity ent = Bukkit.getEntity(holder.golemId);
        if (!(ent instanceof Mob golem) || !golem.isValid()) {
            event.getWhoClicked().closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot == SLOT_POWER) {
            plugin.setPaused(golem, !plugin.isPaused(golem));
        } else if (slot == SLOT_TAG_BREW || slot == SLOT_TAG_OUTPUT) {
            // Right-click names a custom container, left-click resets to the config default.
            LavaGolemPlugin.GolemTag tag = slot == SLOT_TAG_BREW
                    ? LavaGolemPlugin.GolemTag.BREW : LavaGolemPlugin.GolemTag.OUTPUT;
            if (event.isRightClick() && event.getWhoClicked() instanceof Player p) {
                plugin.tagPrompt.prompt(p, golem, tag);
                return;
            }
            plugin.setTag(golem, tag, null);
        } else {
            Map<Integer, Material> slots = slotMap.get(holder.golemId);
            Material m = slots == null ? null : slots.get(slot);
            if (m == null) return; // decorative slot
            plugin.golemTicker.setAlchemyAllowed(golem, m, !plugin.golemTicker.alchemyAllows(golem, m));
        }
        render(holder, golem);
        if (event.getWhoClicked() instanceof Player p) {
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) {
            slotMap.remove(holder.golemId);
            // Keep it held if the menu only closed so the player could type a tag in chat.
            if (plugin.tagPrompt.hasPendingFor(holder.golemId)) return;
            plugin.markMenuClosed(holder.golemId);
        }
    }

    private ItemStack named(Material m, Component name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        it.setItemMeta(meta);
        return it;
    }

    private String prettyName(Material m) {
        String n = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
