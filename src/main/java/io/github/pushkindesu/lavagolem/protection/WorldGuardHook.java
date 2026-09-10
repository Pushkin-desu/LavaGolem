package io.github.pushkindesu.lavagolem.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * Region query on WorldGuard's CHEST_ACCESS flag. {@code wrapOfflinePlayer} is what lets this hook
 * answer even while the owner is logged off — the one capability that makes round-the-clock station
 * protection possible without {@link GenericEventHook}, which needs a live {@code Player} to fire an
 * event as and so only ever works while its target is online.
 */
public final class WorldGuardHook implements ProtectionHook {

    public WorldGuardHook() {
        // Touch the API right away so a version mismatch surfaces here, at registration, as a caught
        // exception (see ProtectionManager#registerHooks) instead of the first time a golem uses it.
        WorldGuard.getInstance();
    }

    @Override
    public boolean canAccess(UUID owner, Block container) {
        OfflinePlayer offlineOwner = Bukkit.getOfflinePlayer(owner);
        LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapOfflinePlayer(offlineOwner);
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return query.testState(BukkitAdapter.adapt(container.getLocation()), localPlayer, Flags.CHEST_ACCESS);
    }

    @Override
    public String name() {
        return "WorldGuard";
    }
}
