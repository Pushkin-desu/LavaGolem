package io.github.pushkindesu.lavagolem.protection;

import org.bukkit.block.Block;

import java.util.UUID;

/**
 * One protection plugin's opinion on whether a golem's owner may read from or write to a container.
 * Implementations are tried in registration order and ALL must agree for access to be allowed — see
 * {@link ProtectionManager} for how they're registered, consulted, and cached.
 */
public interface ProtectionHook {

    /** May this golem's owner take from / put into this container? */
    boolean canAccess(UUID owner, Block container);

    /** Short name for logging (which hook denied, which hook failed to register). */
    String name();
}
