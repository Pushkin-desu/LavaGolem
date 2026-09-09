package io.github.pushkindesu.lavagolem.protection;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.block.Block;

import java.util.UUID;

/**
 * Consults GriefPrevention's claim data directly by UUID: claim ownership and the Container trust
 * level both resolve without the owner needing to be online, which is what lets this hook (unlike
 * {@link GenericEventHook}) keep protecting a station even while its owner is logged off.
 */
public final class GriefPreventionHook implements ProtectionHook {

    public GriefPreventionHook() {
        // Touch the API right away so a version mismatch (a renamed class or method) surfaces here,
        // at registration, as a caught exception — see ProtectionManager#registerHooks — rather than
        // as an unguarded NoSuchMethodError/NoClassDefFoundError the first time a golem walks up to
        // a container.
        if (GriefPrevention.instance == null) {
            throw new IllegalStateException("GriefPrevention API not initialized yet");
        }
    }

    @Override
    public boolean canAccess(UUID owner, Block container) {
        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(container.getLocation(), false, null);
        if (claim == null) return true; // wilderness — nobody has claimed this container's location
        if (owner.equals(claim.ownerID)) return true; // the golem's own owner owns the claim
        // Container is the trust level GriefPrevention itself uses for chest/furnace/etc access
        // (granted by /containertrust and everything above it); Inventory is only a deprecated
        // alias for the same level.
        return claim.hasExplicitPermission(owner, ClaimPermission.Container);
    }

    @Override
    public String name() {
        return "GriefPrevention";
    }
}
