package io.github.pushkindesu.lavagolem.nav;

import io.github.pushkindesu.lavagolem.LavaGolemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Mob;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * The facade GolemTicker talks to for all movement. Everywhere else in the plugin still thinks in
 * terms of "walk to this target and tell me when you get there" — this class is what turns that
 * into an async A* search over {@link NavMesh}, a string-pulled walk between the resulting turn
 * points, and a small ladder of fallbacks for when the mesh can't help (see {@link #tick}).
 *
 * Two things beyond the search itself live here. Route memory ({@link #routeCache}) remembers the
 * polyline of any search that actually reached its goal, keyed by (world, start block SNAPPED to a
 * 4-block grid, goal block exact), so a courier running the same leg for the hundredth time replays
 * it with no A* at all — until {@link #evictRoutesThrough} (driven by NavMeshListener) or a stall
 * mid-replay says otherwise. And every meaningful event — a path requested, a cache hit, a stall, a
 * fallback rung, a teleport — is traced through an injected callback into GolemTicker's existing
 * /golemdebug machinery, because the courier is exactly the role whose failures are hardest to see.
 *
 * Every method here that can be called from GolemTicker's tick loop runs on the MAIN thread; the
 * only thing that ever runs on a worker is {@link AStar#search}, fed exclusively by data gathered
 * up front on the main thread ({@link PathJob}). Nothing in this class hands a live Bukkit object
 * to the worker pool.
 */
public final class Navigation {

    public enum Status { MOVING, ARRIVED, COMPUTING, STUCK }

    /** How a target is measured as "reached". BLOCK is a solid container/stand the golem stands
     *  NEXT TO (centre-based distance, cfg.reachDistance) — most golem jobs. SPOT is a standable
     *  location the golem must stand ON (the fisher's bank spot) — plain point distance, 1.3. */
    public enum Arrival { BLOCK, SPOT }

    private static final double SPOT_REACH = 1.3;
    private static final double DRIFT_LIMIT = 4.0;
    private static final long STALE_MS = 30_000L;
    private static final int ROUTE_CACHE_CAP = 256;
    /** Grid size the START of a route-cache key is snapped to. A courier never stops at the exact
     *  same block twice beside a container, so an exact key would never hit; snapping the start
     *  (never the goal — that's a container block and is always identical) turns "somewhere in this
     *  4-block neighbourhood of the source" into one shared, reusable key. */
    private static final int ROUTE_SNAP = 4;
    /** Bounding-box pre-warm is expanded this many chunks past the from/goal box on every side, so a
     *  route that detours off the straight line (a staircase, a door around a wall) still has its
     *  real corridor seeded instead of just the chunks a straight line would touch. */
    private static final int PREWARM_MARGIN_CHUNKS = 2;
    /** How long a stalled-on cell stays penalised for. Short on purpose: this is a "don't immediately
     *  repeat the exact mistake" nudge for the very next search or two, not a permanent memory —
     *  terrain a golem genuinely can't cross should be caught by the navmesh itself, not by this. */
    private static final long FAILED_CELL_TTL_MS = 60_000L;

    private final LavaGolemPlugin plugin;
    private final NavMesh mesh;
    private final ExecutorService pool;
    private final AtomicInteger inFlight = new AtomicInteger();

    /** Traces one event into GolemTicker's /golemdebug chat feed. A no-op until GolemTicker wires
     *  itself in (see LavaGolemPlugin#onEnable) — the gate on whether anyone's actually watching
     *  lives in gdebug itself, so this class never has to ask "is anyone watching?" first. */
    private BiConsumer<UUID, String> tracer = (id, msg) -> { };

    /** One entry per golem currently under Navigation's control. Everything here is transient —
     *  rebuilt from scratch the moment a target changes — which is also why sweep() only needs to
     *  drop entries, never merge or migrate them. */
    private final Map<UUID, NavState> states = new ConcurrentHashMap<>();

    /** Chunks some search wanted but had no mesh for, server-wide, mapped to an anchor Y to scan
     *  around. Drained a few at a time in {@link #drainWanted()} — see the class doc on NavMesh and
     *  the design note on the missing-chunk protocol: this is what makes a 256-block route affordable,
     *  since the main thread only ever pays for the thin corridor a search actually explored. */
    private final Map<NavMesh.ChunkKey, Integer> wanted = new ConcurrentHashMap<>();

    /** Reverse index: which golems are currently sitting still waiting specifically on this chunk.
     *  Populated by {@link #registerWaiting}, drained the moment that chunk's mesh actually lands
     *  (see the {@code justBuilt} queue below) so the golem retries the instant its corridor grows,
     *  rather than on a timer. */
    private final Map<NavMesh.ChunkKey, Set<UUID>> waitingGolems = new ConcurrentHashMap<>();

    /** Chunk keys a worker just finished meshing, handed back for the main thread to react to.
     *  Populated from a worker thread in {@link #drainWanted()}'s build lambda, drained only on the
     *  main thread (the next call to drainWanted) — a plain thread-safe queue is enough since it's
     *  genuinely a single-producer-at-a-time-per-key, single-consumer handoff. */
    private final ConcurrentLinkedQueue<NavMesh.ChunkKey> justBuilt = new ConcurrentLinkedQueue<>();

    /** One remembered leg: the string-pulled polyline of a search that actually reached its goal
     *  (never a partial — see applyResult), the raw A* cells underlying each of its turn-point-to-
     *  turn-point hops (see {@link #legRawCells} on NavState — a stall on a REPLAYED path needs these
     *  just as much as a stall on a fresh one does), plus every chunk it crosses, for eviction. */
    private static final class CachedRoute {
        final List<Location> path;
        final List<List<Long>> legRawCells;
        final Set<NavMesh.ChunkKey> crossedChunks;
        CachedRoute(List<Location> path, List<List<Long>> legRawCells, Set<NavMesh.ChunkKey> crossedChunks) {
            this.path = path;
            this.legRawCells = legRawCells;
            this.crossedChunks = crossedChunks;
        }
    }

    private record RouteKey(UUID world, long from, long goal) { }

    /** Reverse index for route-cache eviction: which cached routes cross this chunk. Kept in step
     *  with {@link #routeCache} by every method that adds or removes an entry from either side. */
    private final Map<NavMesh.ChunkKey, Set<RouteKey>> routeCacheByChunk = new HashMap<>();

    /** LRU by access order, capped at {@value ROUTE_CACHE_CAP} entries — couriers run the same
     *  container-to-container leg forever, so this is the fix for recomputing every single trip. */
    private final LinkedHashMap<RouteKey, CachedRoute> routeCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RouteKey, CachedRoute> eldest) {
            if (size() <= ROUTE_CACHE_CAP) return false;
            forgetFromChunkIndex(eldest.getKey(), eldest.getValue());
            return true;
        }
    };

    private static final class NavState {
        Location target;
        Arrival mode;
        List<Location> path;          // remaining string-pulled turn points, walked front to back
        // Raw A* cells underlying each remaining leg, index-aligned 1:1 with `path` and popped in the
        // same lockstep in followPath() -- legRawCells.get(0) is every raw cell between wherever this
        // leg started and path.get(0), which is exactly what a stall on THIS leg needs to penalise
        // (see recordFailedLeg). May be shorter than `path` or null for state built before this existed
        // in an older leg; callers treat that as "nothing more specific than the turn point itself".
        List<List<Long>> legRawCells;
        long pathBuiltAtMs;
        Location segStart;             // where the current leg of the polyline started, for drift checks
        double lastDist = Double.MAX_VALUE;
        Location lastPos;              // where the golem stood last tick, for the "is it moving at all" test
        int stallTicks;
        int recomputeAttempts;         // 0 normal, 1 = mid recompute, 2 = fallback, 3+ = give up
        PathJob job;
        boolean inFallback;
        /** Set when a search proved no route exists at all. Skips the whole stall ladder — there is
         *  nothing for a recompute or a waypoint hop to find that the search did not already rule
         *  out — and reports STUCK straight away so the role can re-home and back off. */
        boolean unreachable;
        Location fallbackAim;
        Set<Long> fallbackVisited;

        // Starved-partial handling (a search that hit unmapped chunks and gave up short of the goal).
        Set<NavMesh.ChunkKey> waitingOn;   // non-null while deliberately standing still on a starved result
        long waitingSinceMs;
        int starvedCount;                  // total starved attempts this leg, against the absolute ceiling
        int starvedStallCount;             // consecutive attempts with NO shrinkage in the unmapped count
        int lastWantedCount = Integer.MAX_VALUE; // unmapped-chunk count from the previous starved attempt

        // Set only when the current path came from a route-cache replay, so a stall mid-replay knows
        // exactly which entry to evict rather than trusting it again next leg.
        RouteKey cachedRouteKey;

        // Set for exactly one requestCompute() call, right after a stall: that call must not be served
        // from the route cache (see requestCompute) -- the whole point of a stall-triggered recompute
        // is to search again with penalizedCells in play, and a cache hit would silently skip AStar
        // altogether and hand back the identical path that just failed.
        boolean skipRouteCacheOnce;

        // Where this leg's most recent request was made FROM — used only to prioritise drainWanted()
        // (nearest-first) and is not itself part of any correctness decision.
        Location lastFrom;

        // Whether the one-time bounding-box pre-warm has already run for this leg (see requestCompute).
        boolean preWarmed;

        // Cells a walk attempt actually failed to reach, mapped to when the penalty expires. Survives
        // across legs deliberately (it's "per-golem", not "per-leg") since the short TTL below is
        // already what keeps it from mattering once the golem's moved on. See recordFailedLeg and
        // AStar's PENALTY_COST for how this is spent.
        final Map<Long, Long> failedCells = new HashMap<>();

        // The raw node path from the most recently applied search for THIS leg, kept only so the next
        // search's result can be compared against it (see applyResult) — a diagnostic for confirming a
        // stall penalty actually changed anything, not something any decision is made from.
        List<Long> lastRawPath;

        // ===== moveTo/pathfinder boundary instrumentation (see trackedMoveTo) =====
        // Which turn point the two counters below are currently tracking -- reset the instant the
        // golem is asked to walk toward a DIFFERENT point, so a refusal on the leg just finished never
        // bleeds into the next one's count.
        Location moveTrackedTarget;
        // Consecutive ticks moveTo has returned false for moveTrackedTarget. Read (and reset) from
        // tick()'s stall check exactly like stallTicks is -- a hard refusal for nav-move-refused-ticks
        // ticks in a row fires the same stall ladder the distance-based detector does, just sooner and
        // for a reason that isn't a guess.
        int moveRefusedStreak;
        // Whether moveTrackedTarget is CURRENTLY in a refusing streak, purely so trackedMoveTo can
        // trace the false->true and true->false transitions once each instead of every single tick.
        boolean moveRefusing;
    }

    public Navigation(LavaGolemPlugin plugin, NavMesh mesh) {
        this.plugin = plugin;
        this.mesh = mesh;
        int threads = Math.max(1, plugin.cfg.navMaxConcurrent);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "LavaGolem-Nav");
            t.setDaemon(true); // never hold the server open on shutdown waiting for a stray search
            return t;
        };
        this.pool = Executors.newFixedThreadPool(threads, factory);
    }

    /** Wires this class into GolemTicker's existing /golemdebug feed instead of duplicating it —
     *  see LavaGolemPlugin#onEnable, where this is called once GolemTicker actually exists. */
    public void setTracer(BiConsumer<UUID, String> tracer) {
        this.tracer = tracer != null ? tracer : (id, msg) -> { };
    }

    private void trace(UUID id, String msg) {
        tracer.accept(id, msg);
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    /** Drops all per-golem state for UUIDs that no longer resolve to a live entity — golem UUIDs
     *  change on chunk reload (HeartUseListener respawns the entity), so without this every golem
     *  that's ever been reloaded leaks its nav state for the life of the server. */
    public void sweep(Set<UUID> aliveGolemIds) {
        states.keySet().removeIf(id -> !aliveGolemIds.contains(id));
        for (Set<UUID> s : waitingGolems.values()) s.retainAll(aliveGolemIds);
        waitingGolems.values().removeIf(Set::isEmpty);
    }

    /** Drops a golem's in-progress path/job immediately, e.g. right after the courier's teleport
     *  fallback moves it somewhere the old polyline no longer starts from, or when its menu opens
     *  (a wedged nav state should never need an external reset to clear — this is that reset). */
    public void cancel(Mob golem) {
        UUID id = golem.getUniqueId();
        NavState st = states.remove(id);
        if (st != null) clearWaiting(id, st);
    }

    /** Called by NavMeshListener whenever a chunk's navmesh is invalidated — drops every cached
     *  route that crosses it. A remembered polyline through terrain that just changed is worse than
     *  no memory at all, since it would otherwise be replayed with no A* to catch the problem. */
    public void evictRoutesThrough(NavMesh.ChunkKey chunkKey) {
        Set<RouteKey> affected = routeCacheByChunk.remove(chunkKey);
        if (affected == null) return;
        for (RouteKey rk : new ArrayList<>(affected)) {
            CachedRoute r = routeCache.remove(rk);
            if (r == null) continue;
            for (NavMesh.ChunkKey other : r.crossedChunks) {
                if (other.equals(chunkKey)) continue;
                Set<RouteKey> s = routeCacheByChunk.get(other);
                if (s != null) { s.remove(rk); if (s.isEmpty()) routeCacheByChunk.remove(other); }
            }
        }
    }

    /** Builds at most {@code nav-chunks-per-tick} navmeshes from the server-wide wanted set (or up
     *  to {@code nav-chunks-per-tick-burst} while a golem is actually blocked in COMPUTING) —
     *  snapshot on the main thread (cheap), parse on the worker (the expensive part). Call once per
     *  GolemTicker logic tick. Only ever builds a mesh for a chunk that's already loaded — never
     *  force-loads one. Also drains {@code justBuilt}: the moment a chunk a golem was waiting on
     *  actually lands, that golem is freed to retry immediately rather than on the stale timer.
     *
     * Candidates are drained NEAREST-FIRST (to whichever golem's own request they came from), not in
     * whatever order the map happens to give — otherwise the frontier a fast-moving search keeps
     * pushing outward (see applyResult's "converged" case) scatters the build budget across the
     * whole wanted set instead of advancing along the one corridor that's actually being walked.
     */
    public void drainWanted() {
        NavMesh.ChunkKey builtKey;
        while ((builtKey = justBuilt.poll()) != null) {
            Set<UUID> ids = waitingGolems.remove(builtKey);
            if (ids == null) continue;
            for (UUID id : ids) {
                NavState st = states.get(id);
                if (st != null && st.waitingOn != null) {
                    clearWaiting(id, st);
                    trace(id, "chunk meshed, resuming search");
                }
            }
        }

        if (wanted.isEmpty()) return;
        boolean anyComputing = false;
        List<Location> refs = new ArrayList<>();
        for (NavState s : states.values()) {
            if (s.path == null && !s.inFallback) anyComputing = true;
            if (s.lastFrom != null) refs.add(s.lastFrom);
        }
        int budget = anyComputing
                ? Math.max(plugin.cfg.navChunksPerTick, plugin.cfg.navChunksPerTickBurst)
                : plugin.cfg.navChunksPerTick;

        List<Map.Entry<NavMesh.ChunkKey, Integer>> ordered = new ArrayList<>(wanted.entrySet());
        if (ordered.size() > 1 && !refs.isEmpty()) {
            ordered.sort(Comparator.comparingDouble(e -> nearestRefDistSq(e.getKey(), refs)));
        }

        int built = 0;
        for (Map.Entry<NavMesh.ChunkKey, Integer> e : ordered) {
            if (built >= budget) break;
            NavMesh.ChunkKey key = e.getKey();
            // Visited: drop it from the pending set regardless of outcome below, exactly like the
            // old single-pass iterator did — an unloaded chunk isn't retried until something wants
            // it again, and re-adding a still-wanted one is cheap (merge with keep-existing-anchor).
            wanted.remove(key);
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.cx(), key.cz())) {
                // Golems cannot exist in an unloaded chunk, so this is a WALL, not a "still pending"
                // gap -- recording it here is what makes AStar stop re-adding it to `wanted` every
                // single search, which is what let a genuinely impossible route grind through 30+
                // tries before this fix instead of concluding in a handful. Notify anyone registered
                // as waiting on it too, exactly like a real build would, so they retry now rather than
                // sitting out the stale-wait backstop for a chunk that will never load on its own.
                mesh.storeUnloaded(key);
                justBuilt.add(key);
                continue;
            }
            built++;
            Chunk chunk = world.getChunkAt(key.cx(), key.cz());
            ChunkSnapshot snap = NavMesh.snapshot(chunk);
            int anchorY = e.getValue();
            int minY = Math.max(world.getMinHeight(), anchorY - 32);
            int maxY = Math.min(world.getMaxHeight() - 1, anchorY + 32);
            pool.execute(() -> {
                mesh.store(key, mesh.buildColumns(snap, minY, maxY), minY);
                justBuilt.add(key);
            });
        }
    }

    private double nearestRefDistSq(NavMesh.ChunkKey k, List<Location> refs) {
        double cx = (k.cx() << 4) + 8, cz = (k.cz() << 4) + 8;
        double best = Double.MAX_VALUE;
        for (Location r : refs) {
            if (!r.getWorld().getUID().equals(k.world())) continue;
            double dx = r.getBlockX() - cx, dz = r.getBlockZ() - cz;
            double d = dx * dx + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * Advances one golem's movement toward {@code target} by one logic tick and reports what
     * happened. Never blocks: a computation in flight just leaves the golem standing (first path for
     * this leg) or walking its current one (a background refresh), and is picked up on a later tick.
     */
    public Status tick(Mob golem, Location target, Arrival mode) {
        raiseFollowRange(golem);
        UUID id = golem.getUniqueId();
        Location loc = golem.getLocation();

        double reachThresh = mode == Arrival.SPOT ? SPOT_REACH : plugin.cfg.reachDistance;
        double dist = mode == Arrival.SPOT ? loc.distance(target) : reachDist(loc, target);
        if (dist <= reachThresh) {
            NavState done = states.remove(id);
            if (done != null) clearWaiting(id, done);
            return Status.ARRIVED;
        }

        NavState st = states.computeIfAbsent(id, k -> new NavState());
        if (st.target == null || !sameBlock(st.target, target) || st.mode != mode) {
            clearWaiting(id, st);
            st.target = target.clone();
            st.mode = mode;
            st.path = null;
            st.job = null;
            st.stallTicks = 0;
            st.lastDist = dist;
            st.recomputeAttempts = 0;
            st.inFallback = false;
            st.fallbackAim = null;
            st.fallbackVisited = null;
            st.cachedRouteKey = null;
            st.preWarmed = false;
            st.lastRawPath = null;
            st.legRawCells = null;
            st.moveTrackedTarget = null;
            st.moveRefusedStreak = 0;
            st.moveRefusing = false;
            resetStarvedTracking(st);
        }

        if (st.job != null && st.job.done) {
            applyResult(id, st);
        } else if (st.job != null && !st.job.submitted) {
            trySubmit(st.job);
        }

        // Progress is judged the same way the old noProgress() did: real distance closed toward the
        // FINAL target, not toward whichever turn point is currently active, so switching turn points
        // never looks like a stall. Only genuinely PRODUCTIVE waits are exempt from the count: a job
        // actually in flight, or a registered wait on specific chunks (which has its own notify path
        // plus a stale backstop). A golem with no path, no job, and nothing registered — e.g. right
        // after a degenerate partial gets discarded — is NOT exempt, so a truly dead end still climbs
        // the ordinary stall ladder instead of silently re-requesting forever.
        boolean waitingOnFirstPath = st.job != null || (st.waitingOn != null && !st.waitingOn.isEmpty());

        // Straight-line distance to the target is not enough on its own. A route that goes AROUND
        // something -- a building, a hill, the long way to a staircase -- closes no straight-line
        // distance at all while the golem walks the detour, and judging by distance alone called that
        // a stall: we then penalised a leg the golem was walking perfectly well, recomputed, and did
        // it again, which is exactly the "walks, stops, thinks, walks again" the maintainer saw. So a
        // golem that is physically MOVING is making progress, whatever the distance says; only one
        // that is standing still (or shuffling on the spot) can be stuck.
        Location now = golem.getLocation();
        boolean moving = st.lastPos == null
                || st.lastPos.getWorld() != now.getWorld()
                || st.lastPos.distanceSquared(now) > 0.15 * 0.15;
        st.lastPos = now.clone();

        if (dist < st.lastDist - 0.25) {
            st.lastDist = dist;
            st.stallTicks = 0;
            st.recomputeAttempts = 0;
        } else if (moving) {
            st.stallTicks = 0; // walking a detour: no distance closed, but not stuck either
        } else if (!waitingOnFirstPath) {
            st.stallTicks++;
        }

        // A moveTo refusal is a hard, unambiguous "no" from vanilla, not a heuristic like "hasn't got
        // closer" -- there's no reason to make it wait out the full distance-based window, and doing
        // so blames whichever leg the timer happens to land on rather than the one actually refused.
        // This only ever fires while followPath() is the thing calling moveTo (see trackedMoveTo): the
        // waypoint-hop fallback isn't instrumented, so a stall while inFallback still only comes from
        // the distance detector below, unchanged.
        boolean moveRefused = st.moveRefusedStreak >= plugin.cfg.navMoveRefusedTicks;

        if (st.stallTicks >= plugin.cfg.golemStuckTicks || moveRefused) {
            traceStallSummary(id, golem, st, moveRefused);
            st.stallTicks = 0;
            st.moveRefusedStreak = 0;
            st.moveRefusing = false;
            st.recomputeAttempts++;
            if (st.recomputeAttempts == 1) {
                // First sign of trouble: the current path (or lack of one) isn't working — throw it
                // away and ask again from exactly where the golem is now. A path replayed from the
                // route cache that stalls here is presumed stale despite passing validation (terrain
                // can change in ways NavMeshListener doesn't catch), so drop that entry too.
                if (st.cachedRouteKey != null) { evictRoute(st.cachedRouteKey); st.cachedRouteKey = null; }
                recordFailedLeg(id, st);
                st.skipRouteCacheOnce = true;
                trace(id, "stall: recomputing fresh path");
                st.path = null;
                st.job = null;
                st.legRawCells = null;
                st.inFallback = false;
                st.preWarmed = false;
                clearWaiting(id, st);
                resetStarvedTracking(st);
            } else if (st.recomputeAttempts == 2) {
                // A* has now failed twice in a row: drop to the cheap waypoint-hop fallback rather
                // than spend a third full search on what's more likely a navmesh gap than a maze.
                trace(id, "stall: dropping to waypoint-hop fallback");
                st.inFallback = true;
                st.path = null;
                st.legRawCells = null;
                st.job = null;
                st.fallbackAim = null;
                st.fallbackVisited = new HashSet<>();
                clearWaiting(id, st);
            } else {
                trace(id, "stall: giving up (stuck)");
                clearWaiting(id, st);
                states.remove(id);
                return Status.STUCK;
            }
        }

        if (st.inFallback) {
            followFallback(golem, st);
            return Status.MOVING;
        }

        if (st.unreachable) {
            states.remove(id);
            return Status.STUCK;
        }

        if (st.path == null) {
            if (st.job != null) return Status.COMPUTING; // still running
            if (st.waitingOn != null && !st.waitingOn.isEmpty()) {
                if (System.currentTimeMillis() - st.waitingSinceMs > STALE_MS) {
                    trace(id, "stale wait backstop elapsed, retrying");
                    clearWaiting(id, st);
                } else {
                    return Status.COMPUTING; // drainWanted() will free this the moment a chunk lands
                }
            }
            requestCompute(golem, st);
            if (st.path == null) return Status.COMPUTING; // a fresh search always starts async/held
        }

        maybeRefresh(golem, st);
        followPath(golem, st, id);
        return Status.MOVING;
    }

    private void resetStarvedTracking(NavState st) {
        st.starvedCount = 0;
        st.starvedStallCount = 0;
        st.lastWantedCount = Integer.MAX_VALUE;
    }

    /** Resets only the "am I still improving" sub-counters, leaving {@code starvedCount} — the total
     *  tries against the absolute nav-starved-retries ceiling for this whole leg — untouched. Used
     *  wherever a partial gets discarded rather than walked: the ceiling has to keep counting across
     *  repeated discards or it never actually trips (see the starved/degenerate branch below), while
     *  the next attempt still deserves an honest fresh read on whether IT is improving. */
    private void resetStarvedStallOnly(NavState st) {
        st.starvedStallCount = 0;
        st.lastWantedCount = Integer.MAX_VALUE;
    }

    // ===== A* path lifecycle =====

    private void requestCompute(Mob golem, NavState st) {
        UUID id = golem.getUniqueId();
        Location from = golem.getLocation();
        Location goal = resolveGoal(st.target, st.mode);
        UUID world = from.getWorld().getUID();
        st.lastFrom = from;

        RouteKey key = routeKeyFor(world, from, goal);
        boolean skipCache = st.skipRouteCacheOnce;
        st.skipRouteCacheOnce = false;
        if (skipCache) {
            // A cache hit is only legitimate at the clean start of a leg -- never as the response to a
            // failure. Whatever's sitting under this bucket just demonstrably failed to walk (that's
            // why this recompute is happening at all), so it's evicted outright rather than merely
            // skipped: leaving it in place would only let it poison the NEXT golem, or this one again
            // on some future leg, since validateCachedRoute's live block check has no way to know a
            // route is unwalkable for reasons -- like the diagonal step-up asymmetry -- that have
            // nothing to do with whether its blocks are still standable.
            evictRoute(key);
            trace(id, "post-stall recompute: route cache bypassed " + describeRouteKey(key));
        } else {
            CachedRoute cached = routeCache.get(key); // access-order LinkedHashMap bumps recency here
            if (cached != null) {
                if (validateCachedRoute(from.getWorld(), cached)) {
                    trace(id, "route cache hit " + describeRouteKey(key) + " (" + cached.path.size() + " turn point(s))");
                    st.path = new ArrayList<>(cached.path);
                    st.legRawCells = copyLegRawCells(cached.legRawCells);
                    st.pathBuiltAtMs = System.currentTimeMillis();
                    st.segStart = from;
                    st.cachedRouteKey = key;
                    clearWaiting(id, st);
                    resetStarvedTracking(st);
                    return;
                }
                trace(id, "route cache miss (stale entry dropped) " + describeRouteKey(key));
                evictRoute(key);
            } else {
                trace(id, "route cache miss " + describeRouteKey(key));
            }
        }

        // Seed the from/goal bounding box into `wanted` before the search even runs, but only once
        // per leg — a refresh or stall-recompute for the SAME target doesn't need to re-seed it.
        if (!st.preWarmed) {
            preWarmBoundingBox(world, from, goal);
            st.preWarmed = true;
        }

        List<int[]> waypointCells = scanWaypointCells(from);
        Set<Long> penalized = activeFailedCells(st);
        PathJob job = new PathJob(id, world, from, goal, waypointCells, penalized);
        st.job = job;
        st.cachedRouteKey = null;
        trace(id, "path requested -> " + goal.getBlockX() + "," + goal.getBlockY() + "," + goal.getBlockZ());

        if (!plugin.cfg.navAsync) {
            runJob(job);
            job.done = true;
            applyResult(id, st);
            return;
        }
        trySubmit(job);
    }

    private void trySubmit(PathJob job) {
        if (inFlight.get() >= plugin.cfg.navMaxConcurrent) return; // stays queued; retried next tick
        job.submitted = true;
        inFlight.incrementAndGet();
        pool.execute(() -> {
            try {
                runJob(job);
            } finally {
                job.done = true;
                inFlight.decrementAndGet();
            }
        });
    }

    private void runJob(PathJob job) {
        job.result = AStar.search(mesh, job.world,
                job.from.getBlockX(), job.from.getBlockY(), job.from.getBlockZ(),
                job.goal.getBlockX(), job.goal.getBlockY(), job.goal.getBlockZ(),
                job.waypointCells, job.penalizedCells,
                plugin.cfg.navMaxNodes, plugin.cfg.navMaxDistance, plugin.cfg.navMaxStepDown,
                plugin.cfg.navSearchMargin);
    }

    /** Snapshot of a golem's currently non-expired failed cells, plain data safe to hand to a worker
     *  job — expired entries are pruned here rather than on a timer, so this both answers "what's
     *  penalised right now" and keeps the map from growing unbounded. */
    private Set<Long> activeFailedCells(NavState st) {
        if (st.failedCells.isEmpty()) return Set.of();
        long now = System.currentTimeMillis();
        st.failedCells.values().removeIf(expiry -> expiry < now);
        return st.failedCells.isEmpty() ? Set.of() : new HashSet<>(st.failedCells.keySet());
    }

    /** Called the moment a stall is about to throw the current path away (see tick()'s
     *  recomputeAttempts==1 branch): penalising just the target turn point is usually not enough to
     *  change what the next search finds, since a single 30-cost cell is often routed around with a
     *  near-identical path one block over. So this penalises the WHOLE failed leg instead -- the target
     *  turn point plus every raw A* cell between wherever the golem started walking this leg and that
     *  turn point (see NavState.legRawCells) -- which forces the next search to actually go a
     *  meaningfully different way, not just sidestep. A path with nothing left to walk toward
     *  (already-empty, or never built) has nothing specific to blame, so this is a no-op; a path built
     *  before legRawCells existed (or too short to have one for this leg) falls back to penalising just
     *  the target, same as before. */
    private void recordFailedLeg(UUID id, NavState st) {
        if (st.path == null || st.path.isEmpty()) return;
        Location target = st.path.get(0);
        long expiry = System.currentTimeMillis() + FAILED_CELL_TTL_MS;

        List<Long> legCells = (st.legRawCells != null && !st.legRawCells.isEmpty())
                ? st.legRawCells.get(0) : List.of(keyOf(target));
        for (long cell : legCells) st.failedCells.put(cell, expiry);

        trace(id, "penalty applied to failed leg ending at (" + target.getBlockX() + "," + target.getBlockY()
                + "," + target.getBlockZ() + ") -- " + legCells.size() + " raw cell(s), "
                + st.failedCells.size() + " cell(s) currently penalised overall");
    }

    /**
     * Applies a finished search. The key decision lives here: a STARVED result — one that neither
     * reached the goal nor ran out of things to explore, meaning it hit unmapped chunks and gave up —
     * is never walked outright. Its lowest-heuristic "best partial" node is closest as the crow flies,
     * which is precisely the wrong answer whenever the real route has to travel away from the goal
     * first (a staircase being the textbook case): walking it confidently marches the golem in the
     * wrong direction. Instead the golem sits tight (see tick()'s waitingOn branch) until either a
     * missing chunk actually lands, or the retry budget below says it's time to stop waiting.
     *
     * That budget is progress-based, not a flat attempt count: as long as the unmapped-chunk count is
     * actually SHRINKING between tries, it keeps waiting (nav-starved-stall-tries consecutive
     * non-improving tries before concluding the corridor has stopped growing), with nav-starved-
     * retries as a generous absolute ceiling purely against a corridor that never finishes at all.
     *
     * A partial that string-pulls down to fewer than 2 turn points — "go nowhere" — is treated as no
     * path at all rather than walked, whether or not it was starved: a step too short to matter just
     * burns a stall cycle before failing anyway.
     */
    private void applyResult(UUID id, NavState st) {
        PathJob job = st.job;
        st.job = null;
        if (job == null || job.result == null) return;

        AStar.Result result = job.result;
        for (Map.Entry<NavMesh.ChunkKey, Integer> e : result.wanted().entrySet()) {
            wanted.merge(e.getKey(), e.getValue(), (a, b) -> a);
        }

        List<Long> raw = result.path();
        if (raw.size() < 2) {
            trace(id, "path request produced nothing usable");
            return; // couldn't take a single step from here; let the stall ladder escalate
        }

        // Diagnostic only, no decision rides on this: confirms on the ground whether a stall penalty
        // (see recordFailedLeg) actually pushed this search toward a different answer, or whether it
        // came back with the same unwalkable path — the exact symptom that started this fix. Many
        // IDENTICAL lines from the starved-retry loop below are normal and expected (nothing there is
        // supposed to change the search) — only a recompute that followed an actual stall should differ.
        if (st.lastRawPath != null) {
            trace(id, st.lastRawPath.equals(raw)
                    ? "path search result IDENTICAL to previous attempt for this leg"
                    : "path search result differs from previous attempt (" + raw.size() + " vs "
                            + st.lastRawPath.size() + " raw node(s))");
        }
        st.lastRawPath = new ArrayList<>(raw);

        boolean reachedGoal = result.reachedGoal();
        PulledPath pulled = stringPull(job.from.getWorld(), raw);
        boolean degenerate = pulled.turnPoints().size() < 2;

        if (!reachedGoal) {
            if (!result.wanted().isEmpty()) {
                // Re-check live rather than trusting the search's own (possibly now-stale) wanted
                // set: drainWanted() runs before the per-golem tick loop each logic tick, so a search
                // that sat queued or in flight for a tick or two can come back citing chunks that have
                // ALREADY been meshed since it ran.
                Set<NavMesh.ChunkKey> stillMissing = new HashSet<>();
                for (NavMesh.ChunkKey k : result.wanted().keySet()) {
                    if (!mesh.hasMesh(k.world(), k.cx() << 4, k.cz() << 4)) stillMissing.add(k);
                }
                if (stillMissing.isEmpty()) {
                    // Everything the search was missing has SINCE been meshed (it sat queued or in
                    // flight a tick or two too long) -- retry now regardless of whether this particular
                    // result also happened to be degenerate; a fresh search against the now-complete
                    // mesh supersedes it either way, so there's nothing worth salvaging from this one.
                    trace(id, "path starved: converged (corridor caught up), retrying now");
                    resetStarvedTracking(st);
                    return; // st.job/st.path/st.waitingOn all stay null — tick() retries next tick
                }
                { // stillMissing is guaranteed non-empty here -- the empty case already returned above
                    int missingCount = stillMissing.size();
                    st.starvedCount++;
                    boolean improved = !degenerate && missingCount < st.lastWantedCount;
                    st.starvedStallCount = improved ? 0 : st.starvedStallCount + 1;
                    st.lastWantedCount = missingCount;

                    boolean stalled = st.starvedStallCount >= plugin.cfg.navStarvedStallTries;
                    boolean ceilingHit = st.starvedCount >= plugin.cfg.navStarvedRetries;
                    if (!stalled && !ceilingHit) {
                        registerWaiting(id, st, stillMissing);
                        trace(id, "path starved: waiting on " + missingCount + " chunk(s), stall "
                                + st.starvedStallCount + "/" + plugin.cfg.navStarvedStallTries
                                + ", tries " + st.starvedCount + "/" + plugin.cfg.navStarvedRetries);
                        return;
                    }
                    String reason = stalled ? "no progress" : "ceiling";
                    if (degenerate) {
                        trace(id, "still starved (" + reason + ") after " + st.starvedCount
                                + " tries: discarding degenerate partial");
                        // Deliberately NOT a full resetStarvedTracking(): st.starvedCount is the total
                        // tries against nav-starved-retries for this whole leg, not since the last
                        // discard. Resetting it here is exactly what made the absolute ceiling
                        // unreachable — every degenerate discard restarted the count at zero, so a leg
                        // could cycle through this branch forever without the ceiling ever tripping.
                        // The stall sub-counters DO reset, though, so the next attempt gets an honest
                        // fresh read on whether it's improving rather than starting pre-declared stalled.
                        resetStarvedStallOnly(st);
                        return;
                    }
                    trace(id, "still starved (" + reason + ") after " + st.starvedCount
                            + " tries: walking best partial anyway");
                }
            } else if (degenerate) {
                trace(id, "path ready: partial collapsed to " + pulled.turnPoints().size() + " turn point(s), discarding");
                resetStarvedTracking(st);
                return;
            }

            // Nothing left unmapped AND the frontier emptied before the node budget did: every cell
            // reachable from here was examined and none of them was the goal. That is a definitive
            // answer, not a "not yet", so walking the best partial is pure waste -- it marches the
            // golem to the closest reachable spot (typically straight above an unreachable chest),
            // discovers the obvious there, and starts over. Give up now instead: the role code's
            // stuck handling re-homes the load, records the reason and backs off, and the backoff is
            // what picks the route up again by itself once the player rebuilds the way through.
            if (result.wanted().isEmpty() && result.frontierExhausted()) {
                trace(id, "no route within the search area: explored it all, giving up on this leg");
                st.unreachable = true;
                clearWaiting(id, st);
                return;
            }

            // Reaching here means a non-degenerate partial is about to be walked (the starved ceiling
            // gave up waiting, or the node budget ran out before the frontier did) --
            // but a partial toward an unreachable goal can easily end up closer to WRONG than to right,
            // which is exactly what marched the golem far from base in the reported trace: 16 turn
            // points toward a goal it never got meaningfully closer to. Only walk it if the endpoint is
            // a REAL step toward the goal, not just A path that happens to exist.
            double startDist = job.from.distance(job.goal);
            double endDist = pulled.turnPoints().get(pulled.turnPoints().size() - 1).distance(job.goal);
            boolean progresses = endDist <= startDist * 0.75;
            trace(id, "partial progress check: start " + fmt1(startDist) + " -> end " + fmt1(endDist)
                    + " (need <= " + fmt1(startDist * 0.75) + " to walk it): "
                    + (progresses ? "walking" : "discarding, no real progress"));
            if (!progresses) {
                resetStarvedStallOnly(st);
                return;
            }
        }

        resetStarvedTracking(st);
        clearWaiting(id, st);
        st.path = pulled.turnPoints();
        st.legRawCells = pulled.legRawCells();
        st.pathBuiltAtMs = System.currentTimeMillis();
        st.segStart = job.from;
        st.cachedRouteKey = null; // this came from a fresh search, not a cache replay

        if (reachedGoal) {
            RouteKey key = routeKeyFor(job.world, job.from, job.goal);
            Set<NavMesh.ChunkKey> crossed = new HashSet<>();
            for (long nodeId : raw) {
                crossed.add(NavMesh.ChunkKey.of(job.world, AStar.unpackX(nodeId), AStar.unpackZ(nodeId)));
            }
            routeCache.put(key, new CachedRoute(new ArrayList<>(st.path), copyLegRawCells(st.legRawCells), crossed));
            for (NavMesh.ChunkKey ck : crossed) {
                routeCacheByChunk.computeIfAbsent(ck, k -> new HashSet<>()).add(key);
            }
            trace(id, "path ready: reached goal, " + st.path.size() + " turn point(s)");
        } else if (result.wanted().isEmpty()) {
            // Fully explored with nothing missing and still short of the goal -- genuinely blocked
            // (or the node budget ran out first) rather than waiting on the map, so this walks
            // immediately and leans on the ordinary stall ladder if it turns out to be a dead end.
            trace(id, "path ready: partial (fully explored, no route found), " + st.path.size() + " turn point(s)");
        } else {
            trace(id, "path ready: partial, " + st.path.size() + " turn point(s), "
                    + result.wanted().size() + " chunk(s) unmapped");
        }
    }

    private static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Recomputes in the background (current walk continues uninterrupted) when the polyline has
     *  drifted too far from where the golem actually is, or is old enough that the world it was
     *  planned against may have changed underfoot. This is also how a partial path — one that gave up
     *  short because a chunk further along wasn't meshed yet — gets extended: by the time it goes
     *  stale, drainWanted() has likely filled in more of the corridor (and if a chunk it was actually
     *  waiting on lands first, tick()'s waitingOn branch already reacted long before this timer would). */
    private void maybeRefresh(Mob golem, NavState st) {
        if (st.job != null) return; // one at a time per golem
        boolean stale = System.currentTimeMillis() - st.pathBuiltAtMs > STALE_MS;
        boolean drifted = st.path.isEmpty() || st.segStart == null
                ? false : distanceToSegment(golem.getLocation(), st.segStart, st.path.get(0)) > DRIFT_LIMIT;
        if (stale || drifted) {
            trace(golem.getUniqueId(), "refresh triggered (" + (drifted ? "drift" : "stale") + ")");
            requestCompute(golem, st);
        }
    }

    private void followPath(Mob golem, NavState st, UUID id) {
        List<Location> path = st.path;
        if (path.isEmpty()) return; // final point already consumed; the top-of-tick arrival check settles it
        Location next = path.get(0);
        if (golem.getLocation().distance(next) <= 1.2) {
            st.segStart = next;
            path.remove(0);
            // Kept in lockstep with `path`: legRawCells.get(0) always describes the leg CURRENTLY being
            // walked (path.get(0)), so a stall right after this pop still penalises the right raw cells.
            if (st.legRawCells != null && !st.legRawCells.isEmpty()) st.legRawCells.remove(0);
            if (!path.isEmpty()) trackedMoveTo(golem, st, id, path.get(0));
            return;
        }
        trackedMoveTo(golem, st, id, next);
    }

    /** The only place moveTo() is actually called for the navmesh-planned path -- everything about
     *  whether vanilla accepts our turn points funnels through here, which is what makes it possible
     *  to trace the boundary A* and NavMesh have no visibility across at all. A search producing a
     *  complete, goal-reaching path proves nothing about whether vanilla's own short-range steering
     *  will actually walk it; this is the instrumentation that tells the two apart.
     *
     * Traced only on TRANSITIONS (moveTo's very first refusal for this turn point, and its first
     * acceptance after a run of refusals) rather than every tick, so a genuinely stuck golem doesn't
     * flood chat/log for the entire golem-stuck-ticks (or nav-move-refused-ticks) window it now spends
     * being tracked -- the full picture for that window is exactly what {@link #traceStallSummary}
     * reports once, at the moment the stall actually fires. */
    private boolean trackedMoveTo(Mob golem, NavState st, UUID id, Location target) {
        if (st.moveTrackedTarget == null || !sameBlock(st.moveTrackedTarget, target)) {
            st.moveTrackedTarget = target;
            st.moveRefusedStreak = 0;
            st.moveRefusing = false;
        }
        boolean ok = golem.getPathfinder().moveTo(target, 1.0);
        if (!ok) {
            st.moveRefusedStreak++;
            if (!st.moveRefusing) {
                st.moveRefusing = true;
                trace(id, "moveTo refused turn point (" + fmtLoc(target) + ") from ("
                        + fmtLoc(golem.getLocation()) + ")");
            }
        } else {
            if (st.moveRefusing) {
                trace(id, "moveTo accepted turn point (" + fmtLoc(target) + ") again after "
                        + st.moveRefusedStreak + " refusal(s)");
            }
            st.moveRefusing = false;
            st.moveRefusedStreak = 0;
        }
        return ok;
    }

    /** The one line a tester should watch: fired exactly once, at the moment a stall trips (either
     *  detector), with everything needed to tell apart the three things that could actually be wrong
     *  at the moveTo boundary -- vanilla refuses to plan at all, vanilla plans short of our turn point,
     *  or vanilla plans correctly and the golem still doesn't move (which points at the entity itself:
     *  AI cleanup, a blocked hitbox, the menu/pause path). MAIN THREAD ONLY: getCurrentPath() and the
     *  golem's own location are both live-world reads, never available to (or needed by) the worker
     *  pool that runs AStar. */
    private void traceStallSummary(UUID id, Mob golem, NavState st, boolean moveRefusedTrigger) {
        Location loc = golem.getLocation();
        Location aim = (st.path != null && !st.path.isEmpty()) ? st.path.get(0)
                : (st.inFallback && st.fallbackAim != null) ? st.fallbackAim
                : st.target;
        double distToAim = aim != null ? loc.distance(aim) : Double.NaN;

        var currentPath = golem.getPathfinder().getCurrentPath();
        String vanillaDesc;
        if (currentPath == null) {
            vanillaDesc = "vanilla has no current path at all";
        } else {
            Location finalPoint = currentPath.getFinalPoint();
            if (finalPoint == null) {
                vanillaDesc = "vanilla's path reports no final point";
            } else {
                double gap = aim != null ? finalPoint.distance(aim) : Double.NaN;
                vanillaDesc = (aim != null && gap <= 0.6)
                        ? "vanilla's path ends AT our turn point"
                        : "vanilla's path ends " + fmt1(gap) + " block(s) short of our turn point, at ("
                                + fmtLoc(finalPoint) + ")";
            }
        }

        trace(id, "stall summary [" + (moveRefusedTrigger ? "move-refused" : "no-progress") + "]: golem at ("
                + fmtLoc(loc) + "), aiming at " + (aim != null ? "(" + fmtLoc(aim) + ")" : "nothing")
                + (aim != null ? ", " + fmt1(distToAim) + " block(s) away" : "")
                + " -- moveTo refused " + st.moveRefusedStreak + " of the last ticks -- " + vanillaDesc);
    }

    private static String fmtLoc(Location l) {
        return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    // ===== waypoint-hop fallback (cheap, last resort before giving up) =====

    /** Walks toward the nearest not-yet-visited [Waypoint] sign that's closest to the real target,
     *  chosen once per hop with a plain sort — no per-candidate pathfinding query, which is exactly
     *  the cost the old courierHop/pathReaches pair used to pay (up to 16 full vanilla searches per
     *  hop). If no waypoint is in range at all, it just walks straight at the target with vanilla's
     *  own steering, which is no worse than what every role did before this feature existed. */
    private void followFallback(Mob golem, NavState st) {
        Location loc = golem.getLocation();
        if (st.fallbackAim == null || loc.distance(st.fallbackAim) <= 1.3) {
            if (st.fallbackAim != null) st.fallbackVisited.add(keyOf(st.fallbackAim));
            List<Location> wps = scanWaypoints(loc);
            Location best = null;
            double bestDist = Double.MAX_VALUE;
            for (Location w : wps) {
                if (st.fallbackVisited.contains(keyOf(w))) continue;
                double d = w.distanceSquared(st.target);
                if (d < bestDist) { bestDist = d; best = w; }
            }
            st.fallbackAim = best != null ? best : st.target;
        }
        golem.getPathfinder().moveTo(st.fallbackAim, 1.0);
    }

    private long keyOf(Location l) {
        return AStar.pack(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    // ===== missing-chunk waiting bookkeeping =====

    /** Remembers which chunks a starved search is waiting on, so drainWanted() can free this golem
     *  the instant one of them is meshed instead of on the stale timer. */
    private void registerWaiting(UUID id, NavState st, Set<NavMesh.ChunkKey> keys) {
        clearWaiting(id, st);
        st.waitingOn = new HashSet<>(keys);
        st.waitingSinceMs = System.currentTimeMillis();
        for (NavMesh.ChunkKey k : keys) {
            waitingGolems.computeIfAbsent(k, kk -> ConcurrentHashMap.newKeySet()).add(id);
        }
    }

    /** Deregisters a golem from every chunk it was waiting on, if any. Called on every path out of
     *  the waiting state (chunk landed, backstop elapsed, target changed, stall, arrival, cancel). */
    private void clearWaiting(UUID id, NavState st) {
        if (st.waitingOn == null) return;
        for (NavMesh.ChunkKey k : st.waitingOn) {
            Set<UUID> s = waitingGolems.get(k);
            if (s != null) { s.remove(id); if (s.isEmpty()) waitingGolems.remove(k, s); }
        }
        st.waitingOn = null;
    }

    // ===== route memory =====

    /** Snaps only the START of a route key to a coarse grid — the golem stops wherever it happens to
     *  stop beside a source container, never the exact same block twice, so an exact key would never
     *  hit. The GOAL stays exact: it's a container block, always identical trip to trip. A slightly
     *  different real start against a snapped key is fine — the first turn point is reachable from
     *  anywhere in that neighbourhood, validateCachedRoute re-checks the polyline live before it's
     *  trusted, and a bad replay drops the entry through the ordinary stall path in tick(). */
    private RouteKey routeKeyFor(UUID world, Location from, Location goal) {
        return new RouteKey(world,
                AStar.pack(snap(from.getBlockX()), snap(from.getBlockY()), snap(from.getBlockZ())),
                AStar.pack(goal.getBlockX(), goal.getBlockY(), goal.getBlockZ()));
    }

    private static int snap(int v) {
        return Math.floorDiv(v, ROUTE_SNAP) * ROUTE_SNAP;
    }

    private String describeRouteKey(RouteKey key) {
        return "(" + AStar.unpackX(key.from()) + "," + AStar.unpackY(key.from()) + "," + AStar.unpackZ(key.from())
                + ")->(" + AStar.unpackX(key.goal()) + "," + AStar.unpackY(key.goal()) + "," + AStar.unpackZ(key.goal()) + ")";
    }

    private void forgetFromChunkIndex(RouteKey key, CachedRoute route) {
        for (NavMesh.ChunkKey ck : route.crossedChunks) {
            Set<RouteKey> s = routeCacheByChunk.get(ck);
            if (s != null) { s.remove(key); if (s.isEmpty()) routeCacheByChunk.remove(ck); }
        }
    }

    private void evictRoute(RouteKey key) {
        CachedRoute r = routeCache.remove(key);
        if (r != null) forgetFromChunkIndex(key, r);
    }

    /** Deep-copies a leg/raw-cells structure so the LIVE list a golem's followPath() pops from
     *  (element by element, as each leg completes) never shares storage with what's sitting in
     *  {@link #routeCache} or was just read back out of it. */
    private static List<List<Long>> copyLegRawCells(List<List<Long>> src) {
        List<List<Long>> out = new ArrayList<>(src.size());
        for (List<Long> leg : src) out.add(new ArrayList<>(leg));
        return out;
    }

    /** Cheap replay-time check: every turn point must still be a place a golem could stand, read
     *  live rather than through the (TTL-bounded) navmesh so a route reused minutes apart isn't
     *  rejected just because nobody happened to walk that chunk recently. This is a belt-and-braces
     *  check on top of evictRoutesThrough's proactive, event-driven invalidation — it exists for
     *  terrain changes no listener catches, same as the mid-replay-stall eviction in tick(). */
    private boolean validateCachedRoute(World world, CachedRoute cached) {
        for (Location p : cached.path) {
            var feet = world.getBlockAt(p.getBlockX(), p.getBlockY(), p.getBlockZ()).getBlockData();
            var head = world.getBlockAt(p.getBlockX(), p.getBlockY() + 1, p.getBlockZ()).getBlockData();
            Material below = world.getBlockAt(p.getBlockX(), p.getBlockY() - 1, p.getBlockZ()).getType();
            if (!NavMesh.standableApprox(feet, head, below)) return false;
        }
        return true;
    }

    // ===== corridor pre-warming =====

    /** Seeds every currently-loaded, not-yet-meshed chunk in the bounding box between {@code from}
     *  and {@code goal}, expanded {@value PREWARM_MARGIN_CHUNKS} chunks on every side, capped at
     *  nav-prewarm-max-chunks. A straight-line corridor is wrong whenever the real route detours —
     *  down a staircase, around a wall — since that's substantially off the direct line and a
     *  straight seed would miss it entirely, discovering the real route one frontier at a time
     *  anyway. The bounding box costs more keys up front but actually covers the route that exists,
     *  and the now-long navmesh TTL means that cost is a one-time toll for the area, not per trip. */
    private void preWarmBoundingBox(UUID world, Location from, Location goal) {
        World w = Bukkit.getWorld(world);
        if (w == null) return;
        int x0 = (Math.min(from.getBlockX(), goal.getBlockX()) >> 4) - PREWARM_MARGIN_CHUNKS;
        int x1 = (Math.max(from.getBlockX(), goal.getBlockX()) >> 4) + PREWARM_MARGIN_CHUNKS;
        int z0 = (Math.min(from.getBlockZ(), goal.getBlockZ()) >> 4) - PREWARM_MARGIN_CHUNKS;
        int z1 = (Math.max(from.getBlockZ(), goal.getBlockZ()) >> 4) + PREWARM_MARGIN_CHUNKS;
        int anchorY = (from.getBlockY() + goal.getBlockY()) / 2;
        int cap = plugin.cfg.navPrewarmMaxChunks;
        int seeded = 0;
        for (int cx = x0; cx <= x1 && seeded < cap; cx++) {
            for (int cz = z0; cz <= z1 && seeded < cap; cz++) {
                if (!w.isChunkLoaded(cx, cz)) continue; // never force-load
                if (mesh.hasMesh(world, cx << 4, cz << 4)) continue; // already covered
                wanted.merge(new NavMesh.ChunkKey(world, cx, cz), anchorY, (a, b) -> a);
                seeded++;
            }
        }
    }

    // ===== main-thread helpers (safe to call live Bukkit here — nothing below runs off-thread) =====

    /** Raises FOLLOW_RANGE toward this golem's configured search distance so vanilla's own local
     *  steering (used both for the hop between two turn points and for the fallback ladder) can
     *  actually reach a turn point that lands outside its small default range. Applied every tick so
     *  existing golems pick it up without a reload — mirrors the courier's own long-standing fix for
     *  exactly this, now needed by every role since every role can walk a long navmesh route. */
    private void raiseFollowRange(Mob golem) {
        try {
            var attr = golem.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE);
            if (attr != null) {
                double want = Math.min(plugin.cfg.navMaxDistance, 128);
                if (attr.getBaseValue() < want) attr.setBaseValue(want);
            }
        } catch (Throwable ignored) { /* attribute unavailable on this version */ }
    }

    private Location resolveGoal(Location target, Arrival mode) {
        if (mode == Arrival.SPOT) return target.clone();
        World w = target.getWorld();
        int bx = target.getBlockX(), by = target.getBlockY(), bz = target.getBlockZ();
        int[][] off = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy = 0; dy >= -1; dy--) {
            for (int[] o : off) {
                int x = bx + o[0], y = by + dy, z = bz + o[1];
                var feet = w.getBlockAt(x, y, z).getBlockData();
                var head = w.getBlockAt(x, y + 1, z).getBlockData();
                Material below = w.getBlockAt(x, y - 1, z).getType();
                if (NavMesh.standableApprox(feet, head, below)) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return new Location(w, bx + 0.5, by + 1, bz + 0.5);
    }

    /** Line-of-sight check for string-pulling: are every quarter-block sample between {@code a} and
     *  {@code b} a place a golem could stand? Runs on the main thread (this is called only from
     *  applyResult, never from the worker), so it reads live blocks rather than the approximate
     *  navmesh — more accurate, and the mesh for far-apart turn points may not even be cached yet. */
    private boolean clearLine(World world, Location a, Location b) {
        double dist = a.distance(b);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.5));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(a.getX() + (b.getX() - a.getX()) * t);
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            int z = (int) Math.floor(a.getZ() + (b.getZ() - a.getZ()) * t);
            var feet = world.getBlockAt(x, y, z).getBlockData();
            var head = world.getBlockAt(x, y + 1, z).getBlockData();
            Material below = world.getBlockAt(x, y - 1, z).getType();
            if (!NavMesh.standableApprox(feet, head, below)) return false;
        }
        return true;
    }

    /** stringPull's output: the collapsed turn points, plus — index-aligned 1:1 with them — the raw
     *  A* cells underlying each hop (from the previous turn point, or the search start for the first
     *  one, up to and including this one). The raw cells exist purely so a stall on a given leg can
     *  penalise the whole thing (see Navigation.recordFailedLeg) rather than just its one endpoint. */
    private record PulledPath(List<Location> turnPoints, List<List<Long>> legRawCells) { }

    /** Collapses the raw grid path down to the handful of turn points a straight walk can't skip —
     *  these ARE the waypoints a player would place by hand, just generated instead of built — and
     *  then re-splits any leg that came out longer than nav-max-leg-blocks (see subdivideLeg). Over a
     *  long clear stretch string-pulling alone can leave two turn points dozens of blocks apart, which
     *  is far enough that vanilla's moveTo re-derives its own route across the whole gap instead of
     *  doing the short local steering it's actually meant for here. */
    private PulledPath stringPull(World world, List<Long> raw) {
        List<Location> pts = new ArrayList<>(raw.size());
        for (long id : raw) {
            pts.add(new Location(world, AStar.unpackX(id) + 0.5, AStar.unpackY(id), AStar.unpackZ(id) + 0.5));
        }
        if (pts.size() <= 1) return new PulledPath(new ArrayList<>(), new ArrayList<>()); // start only

        List<Integer> keptIdx;
        if (pts.size() == 2) {
            keptIdx = subdivideLeg(pts, 0, 1);
        } else {
            List<Integer> anchors = new ArrayList<>();
            int anchor = 0; // index into pts of the last point we committed to walk FROM
            for (int i = 2; i < pts.size(); i++) {
                if (!clearLine(world, pts.get(anchor), pts.get(i))) {
                    anchors.add(i - 1);
                    anchor = i - 1;
                }
            }
            anchors.add(pts.size() - 1);

            keptIdx = new ArrayList<>();
            int prev = 0;
            for (int idx : anchors) {
                keptIdx.addAll(subdivideLeg(pts, prev, idx));
                prev = idx;
            }
        }

        // Every kept index maps straight back to a raw-path index (pts and raw are index-aligned, one
        // Location per node) -- so the raw cells for the leg ending at keptIdx.get(n) are simply raw's
        // slice from the previous kept index (inclusive) up to this one (inclusive too).
        List<Location> turnPoints = new ArrayList<>(keptIdx.size());
        List<List<Long>> legRawCells = new ArrayList<>(keptIdx.size());
        int prevIdx = 0;
        for (int idx : keptIdx) {
            turnPoints.add(pts.get(idx));
            legRawCells.add(new ArrayList<>(raw.subList(prevIdx, idx + 1)));
            prevIdx = idx;
        }
        return new PulledPath(turnPoints, legRawCells);
    }

    /** Ensures the leg from {@code pts[fromIdx]} to {@code pts[toIdx]} (a string-pulled turn point
     *  pair) never asks vanilla to bridge more than nav-max-leg-blocks in one hop, by re-inserting
     *  turn points from the ORIGINAL raw node list in between — never by interpolating a straight
     *  line through space, since every point already on the raw path is known-walkable per our model
     *  and an interpolated one isn't. Returns pts-INDICES rather than Locations, since the caller
     *  needs them to slice the matching raw cells out too. The final index is always {@code toIdx}
     *  itself; the ones before it are picked greedily as soon as the accumulated distance would exceed
     *  the cap, so a leg can overshoot the cap by at most one raw grid step (about a block and a half)
     *  — not worth chasing tighter given the cap is itself a rule-of-thumb over vanilla's own follow
     *  behaviour. */
    private List<Integer> subdivideLeg(List<Location> pts, int fromIdx, int toIdx) {
        List<Integer> out = new ArrayList<>();
        double maxLeg = plugin.cfg.navMaxLegBlocks;
        Location legStart = pts.get(fromIdx);
        for (int i = fromIdx + 1; i < toIdx; i++) {
            if (legStart.distance(pts.get(i)) >= maxLeg) {
                out.add(i);
                legStart = pts.get(i);
            }
        }
        out.add(toIdx);
        return out;
    }

    private List<int[]> scanWaypointCells(Location origin) {
        List<int[]> out = new ArrayList<>();
        for (Location l : scanWaypoints(origin)) {
            out.add(new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        }
        return out;
    }

    /** Cube scan for [Waypoint] signs, same shape (and cost) as the courier's old container scan —
     *  bounded by courierSearchRadius since that's the existing, already-accepted budget for exactly
     *  this kind of search, reused here rather than adding a brand new radius setting. */
    private List<Location> scanWaypoints(Location origin) {
        List<Location> out = new ArrayList<>();
        World world = origin.getWorld();
        int r = plugin.cfg.courierSearchRadius;
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (Tag.ALL_SIGNS.isTagged(b.getType()) && b.getState() instanceof Sign sign
                            && signMatchesWaypoint(sign)) {
                        out.add(b.getLocation().add(0.5, 0, 0.5));
                    }
                }
            }
        }
        return out;
    }

    private boolean signMatchesWaypoint(Sign sign) {
        String needle = plugin.cfg.waypointSignText;
        for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
            for (net.kyori.adventure.text.Component line : sign.getSide(side).lines()) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(line);
                if (plain.equalsIgnoreCase(needle)) return true;
            }
        }
        return false;
    }

    private double reachDist(Location from, Location blockLoc) {
        double dx = from.getX() - (blockLoc.getBlockX() + 0.5);
        double dy = from.getY() - blockLoc.getBlockY();
        double dz = from.getZ() - (blockLoc.getBlockZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld() && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private double distanceToSegment(Location p, Location a, Location b) {
        org.bukkit.util.Vector ap = p.toVector().subtract(a.toVector());
        org.bukkit.util.Vector ab = b.toVector().subtract(a.toVector());
        double abLenSq = ab.lengthSquared();
        double t = abLenSq < 1.0e-6 ? 0 : ap.dot(ab) / abLenSq;
        t = Math.max(0, Math.min(1, t));
        org.bukkit.util.Vector closest = a.toVector().add(ab.multiply(t));
        return p.toVector().distance(closest);
    }
}
