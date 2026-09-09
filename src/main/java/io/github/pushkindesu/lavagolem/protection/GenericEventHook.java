package io.github.pushkindesu.lavagolem.protection;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;

/**
 * Fires a normal, cancellable {@link PlayerInteractEvent} (RIGHT_CLICK_BLOCK on the container) as the
 * owner, standing in for the click the golem itself never makes — this is what lets a protection
 * plugin with no GriefPrevention/WorldGuard-style API of its own (the majority of them) still see and
 * veto the access. Only meaningful while the owner is online: a cancellable event needs a real,
 * connected {@code Player} to fire as, so {@link ProtectionManager} only ever consults this hook then
 * — see its class doc for why an offline owner falls back to protection-mode instead.
 */
public final class GenericEventHook implements ProtectionHook {

    @Override
    @SuppressWarnings("deprecation") // isCancelled() as a belt-and-suspenders check alongside useInteractedBlock()
    public boolean canAccess(UUID owner, Block container) {
        Player player = Bukkit.getPlayer(owner);
        if (player == null) return true; // ProtectionManager only calls this while online; belt and suspenders.
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), container, BlockFace.UP);
        Bukkit.getPluginManager().callEvent(event);
        if (event.useInteractedBlock() == Event.Result.DENY) return false;
        return !event.isCancelled();
    }

    @Override
    public String name() {
        return "generic-event";
    }
}
