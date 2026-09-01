package io.github.pushkindesu.lavagolem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
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
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * GUI for configuring a Courier golem: a paged, per-route editor (source tag, dest tag,
 * whitelist/blacklist item filter). Tags are discovered from nearby container names/signs.
 */
public class CourierMenu implements Listener {

    private final LavaGolemPlugin plugin;

    /** Players awaiting a typed tag in chat -> which route field to set.
     *  ConcurrentHashMap: put() runs on the main thread, remove() on the async chat thread. */
    private final java.util.Map<UUID, PendingTag> pending = new java.util.concurrent.ConcurrentHashMap<>();

    private record PendingTag(UUID golemId, int routeIndex, boolean dest) {}

    // Slot layout (54-slot / 6 rows).
    private static final int SLOT_PREV = 0, SLOT_ADD = 2, SLOT_INDICATOR = 4, SLOT_DELETE = 6, SLOT_NEXT = 8;
    private static final int SLOT_POWER = 46;
    private static final int SLOT_SOURCE = 11, SLOT_DEST = 15, SLOT_MODE = 22, SLOT_STATS = 49;
    private static final int SLOT_SAVE = 53;
    private static final int SLOT_STATUS = 45;
    private static final int FILTER_START = 36, FILTER_END = 44; // 9 filter slots

    public CourierMenu(LavaGolemPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        private final UUID golemId;
        private int route;
        private Inventory inventory;
        private Holder(UUID golemId, int route) { this.golemId = golemId; this.route = route; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public void open(Player player, Mob golem) {
        openAt(player, golem, 0);
    }

    private void openAt(Player player, Mob golem, int routeIndex) {
        plugin.markMenuOpen(golem); // pause the courier while its routes are being edited
        Holder holder = new Holder(golem.getUniqueId(), Math.max(0, routeIndex));
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text(plugin.msg.get("courier-menu-title"), NamedTextColor.DARK_GRAY));
        holder.inventory = inv;
        render(holder, golem);
        player.openInventory(inv);
    }

    // ===== rendering =====

    private void render(Holder holder, Mob golem) {
        Inventory inv = holder.inventory;
        inv.clear();
        List<CourierRoute> routes = load(golem);
        if (routes.isEmpty()) { routes.add(new CourierRoute()); save(golem, routes); }
        if (holder.route >= routes.size()) holder.route = routes.size() - 1;
        if (holder.route < 0) holder.route = 0;
        CourierRoute route = routes.get(holder.route);

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        inv.setItem(SLOT_PREV, button(Material.ARROW, "courier-prev", null));
        inv.setItem(SLOT_NEXT, button(Material.ARROW, "courier-next", null));
        inv.setItem(SLOT_ADD, button(Material.LIME_DYE, "courier-add", null));
        inv.setItem(SLOT_DELETE, button(Material.BARRIER, "courier-delete", null));
        inv.setItem(SLOT_INDICATOR, button(Material.PAPER,
                "courier-route-indicator", (holder.route + 1) + " / " + routes.size()));

        inv.setItem(SLOT_SOURCE, tagButton(Material.CHEST, "courier-source", route.source));
        inv.setItem(SLOT_DEST, tagButton(Material.BARREL, "courier-dest", route.dest));

        boolean bl = route.blacklist;
        inv.setItem(SLOT_MODE, button(bl ? Material.RED_DYE : Material.GREEN_DYE,
                bl ? "courier-mode-blacklist" : "courier-mode-whitelist", null));

        // Filter samples
        int fi = FILTER_START;
        for (Material m : route.filter) {
            if (fi > FILTER_END) break;
            inv.setItem(fi++, named(m, Component.text(prettyName(m), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)));
        }
        for (int i = fi; i <= FILTER_END; i++) {
            inv.setItem(i, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "courier-filter-empty", null));
        }

        int moved = golem.getPersistentDataContainer()
                .getOrDefault(plugin.itemsMovedKey, PersistentDataType.INTEGER, 0);
        inv.setItem(SLOT_STATS, button(Material.BOOK, "courier-stats", String.valueOf(moved)));
        inv.setItem(SLOT_SAVE, button(Material.EMERALD, "courier-save", null));
        inv.setItem(SLOT_STATUS, statusItem(golem, route));
        inv.setItem(SLOT_POWER, powerButton(golem));
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

    private ItemStack statusItem(Mob golem, CourierRoute route) {
        String status = plugin.golemTicker.courierRouteStatus(golem, route);
        // STUCK:<reason> is free text (the recorded lastProblem), not one of the fixed translated
        // states below — the ladder gave a SPECIFIC reason, and that's the one place in the plugin a
        // player actually looks when a golem stops working, so it's shown verbatim rather than
        // collapsed into a generic "not ready" message.
        if (status.startsWith("STUCK:")) {
            String reason = status.substring("STUCK:".length());
            ItemStack stuckItem = new ItemStack(Material.ORANGE_DYE);
            ItemMeta stuckMeta = stuckItem.getItemMeta();
            stuckMeta.displayName(Component.text(plugin.msg.get("courier-status-stuck"), NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            stuckMeta.lore(List.of(
                    Component.text(reason, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text(plugin.msg.get("courier-activity") + plugin.golemTicker.courierActivity(golem),
                            NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            stuckItem.setItemMeta(stuckMeta);
            return stuckItem;
        }
        boolean ok = status.equals("OK");
        String key = switch (status) {
            case "OK" -> "courier-status-ok";
            case "NO_SOURCE_TAG" -> "courier-status-no-source";
            case "SOURCE_EMPTY" -> "courier-status-source-empty";
            case "NO_DEST_TAG" -> "courier-status-no-dest";
            case "DEST_FULL" -> "courier-status-dest-full";
            case "SAME_CONTAINER" -> "courier-status-same";
            default -> "courier-status-unconfigured";
        };
        ItemStack item = new ItemStack(ok ? Material.LIME_DYE : Material.ORANGE_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.msg.get(key), ok ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(plugin.msg.get("courier-activity") + plugin.golemTicker.courierActivity(golem),
                NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    // ===== click handling =====

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true); // read-only GUI: never let items actually move

        Entity ent = Bukkit.getEntity(holder.golemId);
        if (!(ent instanceof Mob golem) || !golem.isValid()) {
            event.getWhoClicked().closeInventory();
            return;
        }
        List<CourierRoute> routes = load(golem);
        if (routes.isEmpty()) routes.add(new CourierRoute());
        if (holder.route >= routes.size()) holder.route = routes.size() - 1;
        CourierRoute route = routes.get(holder.route);

        // Shift-click an item in the player's own inventory to add it to the filter.
        if (event.getClickedInventory() != null
                && !(event.getClickedInventory().getHolder() instanceof Holder)) {
            if (event.isShiftClick()) {
                ItemStack it = event.getCurrentItem();
                if (it != null && it.getType() != Material.AIR
                        && route.filter.size() < (FILTER_END - FILTER_START + 1)) {
                    route.filter.add(it.getType());
                    save(golem, routes);
                    render(holder, golem);
                    click(event);
                }
            }
            return;
        }

        int slot = event.getRawSlot();
        switch (slot) {
            case SLOT_PREV -> { if (holder.route > 0) holder.route--; }
            case SLOT_NEXT -> { if (holder.route < routes.size() - 1) holder.route++; }
            case SLOT_ADD -> { routes.add(new CourierRoute()); holder.route = routes.size() - 1; save(golem, routes); }
            case SLOT_DELETE -> {
                routes.remove(holder.route);
                if (routes.isEmpty()) routes.add(new CourierRoute());
                if (holder.route >= routes.size()) holder.route = routes.size() - 1;
                save(golem, routes);
            }
            case SLOT_SOURCE -> {
                if (event.getWhoClicked() instanceof Player p && event.isRightClick()) {
                    startTagInput(p, golem, holder.route, false);
                    return;
                }
                route.source = cycleTag(golem, route.source, false);
                save(golem, routes);
            }
            case SLOT_DEST -> {
                if (event.getWhoClicked() instanceof Player p && event.isRightClick()) {
                    startTagInput(p, golem, holder.route, true);
                    return;
                }
                route.dest = cycleTag(golem, route.dest, false);
                save(golem, routes);
            }
            case SLOT_MODE -> { route.blacklist = !route.blacklist; save(golem, routes); }
            case SLOT_POWER -> plugin.setPaused(golem, !plugin.isPaused(golem));
            case SLOT_SAVE -> {
                save(golem, routes); // already auto-saved, but confirm + close for clarity
                if (event.getWhoClicked() instanceof Player p) {
                    p.closeInventory();
                    p.sendMessage(Component.text(plugin.msg.get("courier-saved"), NamedTextColor.GREEN));
                }
                return;
            }
            default -> {
                if (slot >= FILTER_START && slot <= FILTER_END) {
                    // Click a filter entry to remove it.
                    ItemStack clicked = event.getCurrentItem();
                    if (clicked != null && clicked.getType() != Material.AIR
                            && clicked.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                        route.filter.remove(clicked.getType());
                        save(golem, routes);
                    } else {
                        return;
                    }
                } else {
                    return; // decorative slot — nothing to re-render
                }
            }
        }
        render(holder, golem);
        click(event);
    }

    private void click(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
    }

    private void startTagInput(Player player, Mob golem, int routeIndex, boolean dest) {
        pending.put(player.getUniqueId(), new PendingTag(golem.getUniqueId(), routeIndex, dest));
        player.closeInventory();
        player.sendMessage(Component.text(plugin.msg.get("courier-type-tag"), NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        PendingTag p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity ent = Bukkit.getEntity(p.golemId);
            if (!(ent instanceof Mob golem) || !golem.isValid()) return;
            if (!text.isEmpty() && !text.equalsIgnoreCase("cancel")) {
                List<CourierRoute> routes = load(golem);
                if (p.routeIndex < routes.size()) {
                    CourierRoute r = routes.get(p.routeIndex);
                    if (p.dest) r.dest = text; else r.source = text;
                    save(golem, routes);
                }
            }
            Player pl = Bukkit.getPlayer(event.getPlayer().getUniqueId());
            if (pl != null) openAt(pl, golem, p.routeIndex);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        // Keep the golem paused if the close is only to let this player type a tag in chat
        // (startTagInput closes the menu, then onChat reopens it). Otherwise resume the courier.
        for (PendingTag pt : pending.values()) {
            if (pt.golemId().equals(holder.golemId)) return;
        }
        plugin.markMenuClosed(holder.golemId);
    }

    // ===== tag discovery =====

    /** Distinct tag texts from nearby container names/signs, plus the plugin's configured tags. */
    private List<String> discoverTags(Mob golem) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(plugin.cfg.lavaSignText);
        tags.add(plugin.cfg.fuelSignText);
        tags.add(plugin.cfg.outputSignText);
        tags.add(plugin.cfg.bucketSignText);
        tags.add(plugin.cfg.smeltSignText);

        Location o = golem.getLocation();
        var world = o.getWorld();
        int ox = o.getBlockX(), oy = o.getBlockY(), oz = o.getBlockZ();
        int r = plugin.cfg.courierSearchRadius;
        var plain = PlainTextComponentSerializer.plainText();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (!isStorageMaterial(b.getType())) continue;
                    if (!(b.getState() instanceof Container c)) continue;
                    if (c.customName() != null) {
                        String t = plain.serialize(c.customName()).trim();
                        if (!t.isEmpty()) tags.add(t);
                    }
                    for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN,
                            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                        if (b.getRelative(face).getState() instanceof Sign sign) {
                            for (Component line : sign.getSide(org.bukkit.block.sign.Side.FRONT).lines()) {
                                String t = plain.serialize(line).trim();
                                if (!t.isEmpty()) tags.add(t);
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(tags);
    }

    private String cycleTag(Mob golem, String current, boolean backward) {
        List<String> tags = discoverTags(golem);
        if (tags.isEmpty()) return current;
        int i = tags.indexOf(current);
        if (i < 0) return backward ? tags.get(tags.size() - 1) : tags.get(0);
        int n = tags.size();
        return tags.get(((i + (backward ? -1 : 1)) % n + n) % n);
    }

    private boolean isStorageMaterial(Material m) {
        return m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL
                || Tag.SHULKER_BOXES.isTagged(m);
    }

    // ===== persistence & item helpers =====

    private List<CourierRoute> load(Mob golem) {
        return CourierRoute.parse(
                golem.getPersistentDataContainer().get(plugin.courierRoutesKey, PersistentDataType.STRING));
    }

    private void save(Mob golem, List<CourierRoute> routes) {
        golem.getPersistentDataContainer().set(
                plugin.courierRoutesKey, PersistentDataType.STRING, CourierRoute.serialize(routes));
    }

    private ItemStack named(Material m, Component name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack button(Material m, String key, String value) {
        String text = plugin.msg.get(key);
        if (value != null) text = text + value;
        return named(m, Component.text(text, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
    }

    private ItemStack tagButton(Material m, String key, String tag) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        String label = tag == null || tag.isEmpty() ? plugin.msg.get("courier-tag-unset") : tag;
        meta.displayName(Component.text(plugin.msg.get(key) + label, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(plugin.msg.get("courier-tag-hint"), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    private String prettyName(Material m) {
        String n = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
