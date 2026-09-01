package io.github.pushkindesu.lavagolem.nav;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Evicts a chunk's cached navmesh the moment terrain that could change its columns actually
 * changes, so a freshly dug staircase or a blown-up wall is walkable (or not) on the next path
 * request instead of waiting out the TTL. This is deliberately NOT exhaustive — water flow and
 * falling blocks fire far too often to hook here — which is exactly what the TTL in NavMesh is for:
 * the safety net for whatever this listener doesn't cover.
 *
 * The same event also has to drop any cached ROUTE (see Navigation's route memory) that crosses the
 * changed chunk — a remembered polyline through a doorway that just got walled up is worse than no
 * memory at all, since it would be replayed with no A* to catch the problem.
 */
public final class NavMeshListener implements Listener {

    private final NavMesh mesh;
    private final Navigation navigation;

    public NavMeshListener(NavMesh mesh, Navigation navigation) {
        this.mesh = mesh;
        this.navigation = navigation;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) { invalidate(e.getBlock()); }

    @EventHandler
    public void onBreak(BlockBreakEvent e) { invalidate(e.getBlock()); }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        invalidate(e.getBlock());
        for (Block b : e.blockList()) invalidate(b);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) invalidate(b);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        invalidate(e.getBlock());
        for (Block b : e.getBlocks()) invalidate(b);
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        invalidate(e.getBlock());
        for (Block b : e.getBlocks()) invalidate(b);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        NavMesh.ChunkKey key = NavMesh.ChunkKey.of(
                e.getWorld().getUID(), e.getChunk().getX() << 4, e.getChunk().getZ() << 4);
        mesh.invalidate(key);
        navigation.evictRoutesThrough(key);
    }

    private void invalidate(Block b) {
        Location l = b.getLocation();
        NavMesh.ChunkKey key = NavMesh.ChunkKey.of(l.getWorld().getUID(), b.getX(), b.getZ());
        mesh.invalidate(key);
        navigation.evictRoutesThrough(key);
        // A block change can flip standability of the column NEXT DOOR too (e.g. breaking a wall
        // changes whether its neighbour counts as lava-adjacent), so the four horizontal neighbours'
        // chunks are invalidated as well — cheap (it's just a map remove, a miss costs nothing extra)
        // and cheaper than being wrong until the TTL catches up. A neighbour invalidation only rarely
        // lands in a different chunk than the one just evicted above, so route eviction runs there too.
        invalidateNeighbourChunk(l, b.getX() + 1, b.getZ());
        invalidateNeighbourChunk(l, b.getX() - 1, b.getZ());
        invalidateNeighbourChunk(l, b.getX(), b.getZ() + 1);
        invalidateNeighbourChunk(l, b.getX(), b.getZ() - 1);
    }

    private void invalidateNeighbourChunk(Location l, int x, int z) {
        NavMesh.ChunkKey key = NavMesh.ChunkKey.of(l.getWorld().getUID(), x, z);
        mesh.invalidate(key);
        navigation.evictRoutesThrough(key);
    }
}
