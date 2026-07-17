package io.github.pushkindesu.lavagolem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a player type a golem's station tag into chat, so any golem can be pointed at its own
 * containers instead of everyone sharing the config's tags. Shared by every station menu.
 */
public class TagPrompt implements Listener {

    private final LavaGolemPlugin plugin;

    /** Player -> what they're naming. ConcurrentHashMap: put() on the main thread, remove() on the
     *  async chat thread. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(UUID golemId, LavaGolemPlugin.GolemTag tag) {}

    public TagPrompt(LavaGolemPlugin plugin) {
        this.plugin = plugin;
    }

    /** Closes the menu and waits for the player to type a tag for this golem. The tag is matched
     *  against the container's whole name/sign line, so the prompt spells that out and shows the
     *  current value as a worked example — "brackets or no brackets" is the easiest thing to get
     *  wrong, since they're only a convention of the default tags, not syntax. */
    public void prompt(Player player, Mob golem, LavaGolemPlugin.GolemTag tag) {
        pending.put(player.getUniqueId(), new Pending(golem.getUniqueId(), tag));
        player.closeInventory();
        player.sendMessage(Component.text(plugin.msg.get("tag-type-prompt"), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(plugin.msg.get("tag-type-current") + plugin.tagFor(golem, tag),
                NamedTextColor.WHITE));
        player.sendMessage(Component.text(plugin.msg.get("tag-type-options"), NamedTextColor.DARK_GRAY));
    }

    /** Whether someone is mid-prompt for this golem — menus keep it paused until they're done. */
    public boolean hasPendingFor(UUID golemId) {
        for (Pending p : pending.values()) {
            if (p.golemId().equals(golemId)) return true;
        }
        return false;
    }

    @EventHandler
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Pending p = pending.remove(event.getPlayer().getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity ent = Bukkit.getEntity(p.golemId());
            if (!(ent instanceof Mob golem) || !golem.isValid()) return;
            if (!text.equalsIgnoreCase("cancel")) {
                // "reset" (or an empty line) puts the golem back on the config's tag.
                plugin.setTag(golem, p.tag(), text.equalsIgnoreCase("reset") ? null : text);
            }
            Player pl = Bukkit.getPlayer(playerId);
            if (pl != null) reopen(pl, golem);
        });
    }

    /** Reopens whichever menu this golem's role uses, so the prompt feels like part of the GUI. */
    private void reopen(Player player, Mob golem) {
        String role = golem.getPersistentDataContainer()
                .getOrDefault(plugin.roleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);
        if (LavaGolemPlugin.ROLE_ALCHEMIST.equals(role)) {
            plugin.alchemistMenu.open(player, golem);
        } else {
            plugin.golemMenu.open(player, golem);
        }
    }
}
