package io.github.pushkindesu.lavagolem.nav;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chunk cache of walkable columns: for every (x,z) in a chunk, the sorted Y values a golem
 * could stand on. This is the data AStar searches over, and it is the whole reason the search can
 * run off the main thread at all — once a column list exists here, reading it back is a plain map
 * lookup with no Bukkit call in sight, safe from any thread.
 *
 * Building one is split in two on purpose. {@link #snapshot} takes Paper's own copy-on-write
 * ChunkSnapshot, which is cheap and must run on the main thread because it touches live chunk
 * sections. {@link #buildColumns} then does the actual per-block classification — up to 16k calls
 * for a 16x16x64 window — against that snapshot, and is safe to run anywhere, which is the point:
 * the expensive part moves to a worker thread, the main thread only ever pays for the copy.
 */
public final class NavMesh {

    /** Identifies one chunk regardless of world — chunk coordinates alone collide across worlds,
     *  so every key here carries the world's UID too. */
    public record ChunkKey(UUID world, int cx, int cz) {
        public static ChunkKey of(UUID world, int blockX, int blockZ) {
            return new ChunkKey(world, blockX >> 4, blockZ >> 4);
        }
    }

    /** One standable Y in a column, with the two cheap-to-precompute facts AStar's cost function
     *  needs about it: whether the golem would be standing in water there, and whether lava sits
     *  in one of the four blocks beside it. Both are cosmetic-only inputs (golems are invulnerable)
     *  but a golem strolling through lava looks broken, so the search steers around it when it can. */
    public static final byte FLAG_WATER = 1;
    public static final byte FLAG_LAVA_ADJACENT = 2;

    /** One chunk's worth of columns, indexed [localZ * 16 + localX]. Both arrays for a given column
     *  are the same length and index together: ys[i] is the ith standable Y, flags[i] its flags. */
    public static final class Column {
        public static final Column EMPTY = new Column(new short[0], new byte[0]);
        public final short[] ys;
        public final byte[] flags;
        public Column(short[] ys, byte[] flags) { this.ys = ys; this.flags = flags; }

        /** Index of {@code y} in this column, or -1. Columns are built in increasing Y order so a
         *  binary search is safe. */
        public int indexOf(int y) {
            return Arrays.binarySearch(ys, (short) y);
        }
    }

    private static final class Entry {
        final Column[] columns; // 256 entries, chunk-local
        /** Raw per-block passability for the same build window as {@link #columns}, [256][window],
         *  aligned to {@link #minY}. Null for the unloaded sentinel. This exists ONLY for the headroom
         *  check in AStar (see {@link #isPassableAt}): "standable" already answers "can a golem stand
         *  HERE" (floor + feet + head), which is a different question from "is this one block, on its
         *  own, passable" — the latter is what climbing needs to check one block above where the golem
         *  is currently standing, and isn't derivable from the standable index at all. */
        final boolean[][] passable;
        /** World Y the {@link #passable} window starts at — needed to turn an absolute Y into an
         *  offset into it. Meaningless when {@link #passable} is null. */
        final int minY;
        final long builtAtMs;
        /** True for a sentinel recorded by drainWanted() when a wanted chunk turned out not to be
         *  loaded. Golems cannot exist in an unloaded chunk, so treating it as a wall (not a "still
         *  pending" gap) is the honest model, not an approximation — see the short-TTL handling below. */
        final boolean unloaded;
        Entry(Column[] columns, boolean[][] passable, int minY, long builtAtMs, boolean unloaded) {
            this.columns = columns;
            this.passable = passable;
            this.minY = minY;
            this.builtAtMs = builtAtMs;
            this.unloaded = unloaded;
        }
    }

    /** Every column for a chunk-shaped sentinel: all-EMPTY, so a lookup into any position in it comes
     *  back as "no standable Y here" — impassable — without ever returning null, which is what keeps
     *  AStar from adding it back to `wanted` (see the class doc there). */
    private static final Column[] UNLOADED_COLUMNS = buildAllEmptyColumns();
    private static Column[] buildAllEmptyColumns() {
        Column[] c = new Column[256];
        java.util.Arrays.fill(c, Column.EMPTY);
        return c;
    }

    /** Sentinels expire quickly and on a FIXED schedule, deliberately not the (now much longer,
     *  configurable) real-mesh TTL: a chunk nobody's near yet is still a wall for path-planning
     *  purposes today, but should get an honest mesh soon after it actually loads rather than
     *  staying a wall for the better part of nav-chunk-cache-seconds. This reuses the plugin's
     *  original TTL default, back from before real meshes needed a longer one. */
    private static final long UNLOADED_TTL_MS = 60_000L;

    private final ConcurrentHashMap<ChunkKey, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMs;

    public NavMesh(long ttlSeconds) {
        this.ttlMs = Math.max(0, ttlSeconds) * 1000L;
    }

    private static long ttlFor(Entry e, long realTtlMs) {
        return e.unloaded ? UNLOADED_TTL_MS : realTtlMs;
    }

    /** WORKER-THREAD SAFE. Returns the column at the given world block coordinates, or null if this
     *  chunk has no (or an expired) entry — the caller then treats it as impassable and remembers it
     *  wants that chunk built. Never touches the live world: it only ever reads the cache map. A
     *  sentinel entry (see storeUnloaded) returns a real (non-null) all-EMPTY column instead of null,
     *  which is exactly what tells the caller "impassable, and don't bother asking for it again". */
    public Column columnAt(UUID world, int blockX, int blockZ) {
        Entry e = cache.get(ChunkKey.of(world, blockX, blockZ));
        if (e == null) return null;
        long ttl = ttlFor(e, ttlMs);
        if (ttl > 0 && System.currentTimeMillis() - e.builtAtMs > ttl) return null;
        int lx = blockX & 15, lz = blockZ & 15;
        return e.columns[lz * 16 + lx];
    }

    public void invalidate(ChunkKey key) {
        cache.remove(key);
    }

    public void invalidate(UUID world, int blockX, int blockZ) {
        cache.remove(ChunkKey.of(world, blockX, blockZ));
    }

    /** Whether a live (non-expired) mesh OR unloaded-sentinel already exists for the chunk containing
     *  this block -- used to skip re-queuing a chunk that a search's bounding-box pre-warm would
     *  otherwise seed into {@code wanted} needlessly (a still-valid sentinel means "already recorded
     *  as impassable", which is just as much "already covered" as a real mesh is). */
    public boolean hasMesh(UUID world, int blockX, int blockZ) {
        Entry e = cache.get(ChunkKey.of(world, blockX, blockZ));
        if (e == null) return false;
        long ttl = ttlFor(e, ttlMs);
        return ttl <= 0 || System.currentTimeMillis() - e.builtAtMs <= ttl;
    }

    /** MAIN THREAD ONLY, called from drainWanted(): records that this chunk is not currently loaded,
     *  so a search treats it as impassable terrain instead of re-adding it to `wanted` forever (an
     *  unloaded chunk can never be built without force-loading it, which this plugin never does). */
    public void storeUnloaded(ChunkKey key) {
        cache.put(key, new Entry(UNLOADED_COLUMNS, null, 0, System.currentTimeMillis(), true));
    }

    /** MAIN THREAD ONLY. Paper documents ChunkSnapshot as safe to read off-thread once taken; taking
     *  it is the only part of a mesh build that has to touch the live chunk. */
    public static ChunkSnapshot snapshot(Chunk chunk) {
        return chunk.getChunkSnapshot(false, false, false);
    }

    /** Bundled result of one {@link #buildColumns} call: the standable-column index plus the raw
     *  passability grid alongside it, since both come out of the same per-block scan and {@link #store}
     *  needs to keep them together with the window's {@code minY} for {@link #isPassableAt} to work. */
    public static final class BuildResult {
        public final Column[] columns;
        public final boolean[][] passable;
        BuildResult(Column[] columns, boolean[][] passable) {
            this.columns = columns;
            this.passable = passable;
        }
    }

    /**
     * Runs anywhere (this is the piece meant for the worker thread). Classifies every column in the
     * snapshot's chunk between {@code minY} and {@code maxY} inclusive. Lava-adjacency only looks at
     * neighbours inside the SAME chunk — a block right across a chunk border is treated as "no lava
     * there" rather than pulling in a second snapshot for one cosmetic flag.
     */
    public BuildResult buildColumns(ChunkSnapshot snap, int minY, int maxY) {
        Column[] out = new Column[256];
        boolean[][] passableOut = new boolean[256][];
        // Reused per-column scratch buffers sized for the whole scan window; trimmed to the real
        // count before being stored, since most columns only have a handful of standable Y's.
        int window = maxY - minY + 1;
        short[] ysBuf = new short[window];
        byte[] flagsBuf = new byte[window];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                boolean[] passCol = new boolean[window];
                int count = 0;
                for (int y = minY; y <= maxY; y++) {
                    BlockData feet = snap.getBlockData(lx, y, lz);
                    BlockData head = snap.getBlockData(lx, y + 1, lz);
                    Material below = snap.getBlockType(lx, y - 1, lz);
                    // Raw passability of THIS one block, with no floor or head-clearance requirement --
                    // recorded for every y in the window regardless of standability, since this is
                    // exactly the "is there room to jump through" fact standable-ness can't answer.
                    passCol[y - minY] = isPassable(feet);
                    if (!standableApprox(feet, head, below)) continue;
                    byte flag = 0;
                    if (feet.getMaterial() == Material.WATER) flag |= FLAG_WATER;
                    if (lavaBeside(snap, lx, y, lz)) flag |= FLAG_LAVA_ADJACENT;
                    ysBuf[count] = (short) y;
                    flagsBuf[count] = flag;
                    count++;
                }
                out[lz * 16 + lx] = count == 0 ? Column.EMPTY
                        : new Column(Arrays.copyOf(ysBuf, count), Arrays.copyOf(flagsBuf, count));
                passableOut[lz * 16 + lx] = passCol;
            }
        }
        return new BuildResult(out, passableOut);
    }

    private boolean lavaBeside(ChunkSnapshot snap, int lx, int y, int lz) {
        return sideIsLava(snap, lx + 1, y, lz) || sideIsLava(snap, lx - 1, y, lz)
                || sideIsLava(snap, lx, y, lz + 1) || sideIsLava(snap, lx, y, lz - 1);
    }

    private boolean sideIsLava(ChunkSnapshot snap, int lx, int y, int lz) {
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15) return false; // neighbouring chunk — see class doc
        return snap.getBlockType(lx, y, lz) == Material.LAVA;
    }

    public void store(ChunkKey key, BuildResult built, int minY) {
        cache.put(key, new Entry(built.columns, built.passable, minY, System.currentTimeMillis(), false));
    }

    /** WORKER-THREAD SAFE. Whether the raw block at this exact position is passable, with no floor or
     *  standability requirement — used only for the climbing headroom check in AStar: a target cell
     *  being standable says nothing about whether there's room to jump up INTO it from one block over,
     *  since that space is one block above the golem's own head at the source, not anywhere the target
     *  column's standable index looks at. Conservatively false (refuse the move) when this chunk has no
     *  mesh, the mesh has expired, or the queried Y falls outside the window it was built for -- the
     *  source cell is always already meshed by the time a search asks this (it just walked there), so
     *  this is a rare TTL-timing edge at worst, not a routine miss worth its own `wanted` tracking. */
    public boolean isPassableAt(UUID world, int blockX, int y, int blockZ) {
        Entry e = cache.get(ChunkKey.of(world, blockX, blockZ));
        if (e == null || e.passable == null) return false;
        long ttl = ttlFor(e, ttlMs);
        if (ttl > 0 && System.currentTimeMillis() - e.builtAtMs > ttl) return false;
        int offset = y - e.minY;
        int lx = blockX & 15, lz = blockZ & 15;
        boolean[] col = e.passable[lz * 16 + lx];
        if (offset < 0 || offset >= col.length) return false;
        return col[offset];
    }

    /**
     * The same standable test as {@code GolemTicker#standable(Block)} — feet passable, head passable,
     * a solid non-water floor — but rebuilt against {@link BlockData} instead of a live
     * {@link org.bukkit.block.Block}, because that's all a ChunkSnapshot (or this class's own worker
     * thread) ever has to work with. Bukkit has no static "is this block passable" query — only
     * Block#isPassable(), which needs a live BlockState — so this approximates it as: not solid by
     * material, OR an {@link Openable} (door/trapdoor/fence gate) currently open. That second clause
     * matters more than it looks: a closed door's Material#isSolid() is true regardless of state, so
     * without it every doorway in the mesh would read as a permanent wall — a real problem for a
     * plugin whose whole job is automating a base interior. A closed door correctly stays impassable
     * (copper golems can't open doors, so it genuinely is one). What this still can't see is a
     * per-placement collision shape on an odd block that isn't Openable — a cosmetic risk given
     * golems are invulnerable, not a safety one.
     *
     * Shallow water is deliberately standable rather than rejected outright: feet may be WATER as
     * long as the head above is passable and NOT water — that's wading, and AStar's own +2.0 water
     * cost is what discourages it without forbidding it, exactly like a vanilla mob crossing a
     * stream. Feet-water WITH head-water means the golem would be fully submerged and swimming,
     * which this excludes rather than merely costs, since nothing here paths through open water.
     * below == WATER stays rejected regardless — the surface itself is never a floor to stand on.
     */
    /**
     * Blocks a mob can never stand on top of, however solid Material#isSolid() claims they are,
     * because their collision reaches above their own cube: fences, fence gates and walls all stand
     * 1.5 blocks tall. Without this the floor test sees air, air and "something solid below" and
     * happily records the top of a fence as a walkable ledge — which is exactly how couriers ended
     * up being routed along the top of a base's perimeter fence, planning a step vanilla's own
     * navigation then refused to make.
     *
     * Filled once on the main thread at enable, because Tag lookups go through the server registry
     * and must never be made from the pathfinding worker; the worker only reads the finished set.
     */
    private static volatile Set<Material> nonFloor = Set.of();

    /** Must be called once from onEnable, on the main thread, before any search can run. */
    public static void initNonFloorMaterials() {
        EnumSet<Material> found = EnumSet.noneOf(Material.class);
        for (Material m : Material.values()) {
            if (!m.isBlock()) continue;
            if (Tag.FENCES.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.WALLS.isTagged(m)) {
                found.add(m);
            }
        }
        nonFloor = Collections.unmodifiableSet(found);
    }

    public static boolean standableApprox(BlockData feet, BlockData head, Material below) {
        if (below == Material.WATER) return false; // the surface itself is never a floor
        if (head.getMaterial() == Material.WATER) return false; // fully submerged — swimming, not wading
        if (nonFloor.contains(below)) return false; // a fence top is not a ledge
        return isPassable(feet) && isPassable(head) && below.isSolid();
    }

    private static boolean isPassable(BlockData data) {
        if (!data.getMaterial().isSolid()) return true;
        return data instanceof Openable openable && openable.isOpen();
    }
}
