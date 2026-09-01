package io.github.pushkindesu.lavagolem.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * The search itself. Pure data in, path out — nothing in here touches Bukkit, which is what lets
 * Navigation run it on a worker thread. A node is a packed block position (see {@link #pack}); the
 * mesh it searches over is {@link NavMesh}, whose reads are themselves worker-thread safe.
 */
public final class AStar {
    private AStar() {}

    // x occupies the top 26 bits, y the middle 12, z the bottom 26 — chosen so x falls on the sign
    // bit boundary of the long and needs no masking to sign-extend back out.
    private static final int Y_BITS = 12, Z_BITS = 26;
    private static final int Y_SHIFT = Z_BITS, X_SHIFT = Z_BITS + Y_BITS;
    private static final long Y_MASK = (1L << Y_BITS) - 1, Z_MASK = (1L << Z_BITS) - 1;

    public static long pack(int x, int y, int z) {
        return ((long) x << X_SHIFT) | ((y & Y_MASK) << Y_SHIFT) | (z & Z_MASK);
    }

    public static int unpackX(long id) { return (int) (id >> X_SHIFT); }
    public static int unpackY(long id) { return (int) ((id << (64 - X_SHIFT)) >> (64 - Y_BITS)); }
    public static int unpackZ(long id) { return (int) ((id << (64 - Z_BITS)) >> (64 - Z_BITS)); }

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** A cell that's recently caused a stall gets this added to the cost of entering it, on top of
     *  its ordinary terrain cost — large enough that a search happily detours dozens of blocks around
     *  it rather than hand the golem the exact same unwalkable step again, but not infinite: a cell
     *  that's genuinely the only way through (the one door in a wall) must still be usable if nothing
     *  else works. See Navigation's failedCells bookkeeping for how a cell earns this. */
    private static final double PENALTY_COST = 30.0;

    /** Candidate Y's for a horizontal step, tried in this exact order: same level first, then (for an
     *  orthogonal move only) a step up, then down as far as {@code maxStepDown} allows. A vanilla mob
     *  can only step up when moving orthogonally — it cannot jump diagonally onto a block, though it
     *  will happily drop down a diagonal — so {@code allowStepUp} must be false for the 4 diagonal
     *  directions, and the outbound-vs-return asymmetry that produced without this (a descending route
     *  works, the same route in reverse doesn't) is exactly why this exists. */
    private static int[] buildYTry(int maxStepDown, boolean allowStepUp) {
        int[] yTry = new int[maxStepDown + (allowStepUp ? 2 : 1)];
        int i = 0;
        yTry[i++] = 0;
        if (allowStepUp) yTry[i++] = 1;
        for (int d = 0; d < maxStepDown; d++) yTry[i++] = -(d + 1);
        return yTry;
    }

    /** @param path       node ids from start to the end reached, inclusive of both; empty if not even
     *                    one step could be taken.
     *  @param reachedGoal true if the path actually ends at the goal cell, false if it's the best
     *                    partial progress the budget allowed.
     *  @param wanted     chunks the search wanted a navmesh for but didn't have one, each mapped to
     *                    an anchor Y (the height the search was at when it hit the gap) so the chunk
     *                    that eventually gets built scans a window centred on where it's needed.
     *  @param frontierExhausted the open set emptied before the node budget did, so every cell
     *                    reachable from the start was actually examined. Together with an empty
     *                    {@code wanted} that makes "no route" a DEFINITIVE answer rather than "not
     *                    yet" — nothing was left unmapped and nothing was left unexplored. A search
     *                    stopped by the node budget proves nothing either way. */
    public record Result(List<Long> path, boolean reachedGoal,
                         Map<NavMesh.ChunkKey, Integer> wanted, boolean frontierExhausted) {
        static Result empty() { return new Result(List.of(), false, Map.of(), false); }
    }

    private static final class Open implements Comparable<Open> {
        final long id; final double f;
        Open(long id, double f) { this.id = id; this.f = f; }
        public int compareTo(Open o) { return Double.compare(f, o.f); }
    }

    /**
     * @param waypointCells block x/y/z of every [Waypoint] sign the caller found near the start —
     *                      plain data, gathered on the main thread before this ever runs. A cell
     *                      within 2 blocks of one gets its edge cost cut to x0.7, so a search
     *                      naturally prefers a player-built road over scrambling across raw terrain.
     * @param penalizedCells cells a recent walk attempt actually failed to reach — plain packed node
     *                      ids gathered on the main thread from Navigation's per-golem, short-lived
     *                      failure memory. Entering one costs extra (see {@link #PENALTY_COST}) so a
     *                      recompute after a stall is pushed toward a genuinely different route instead
     *                      of confidently handing back the exact path that just failed.
     * @param maxStepDown   how many blocks a horizontal move may step down by — see nav-max-step-down;
     *                      vanilla's own local steering won't voluntarily walk a mob off anything
     *                      bigger, so a value here more permissive than that just produces turn points
     *                      moveTo refuses to reach.
     */
    public static Result search(NavMesh mesh, UUID world,
                                 int sx, int sy, int sz, int gx, int gy, int gz,
                                 List<int[]> waypointCells, Set<Long> penalizedCells,
                                 int maxNodes, double maxDistance, int maxStepDown, double searchMargin) {
        double startToGoal = Math.sqrt(sq(sx - gx) + sq(sy - gy) + sq(sz - gz));
        if (startToGoal > maxDistance) return Result.empty(); // hard cap: fail fast, let the caller fall back

        // How far from the goal the search may roam, horizontally. Generous enough to walk right
        // around a building or a hill between the two ends, tight enough that the region is finite.
        double maxFromGoal = Math.sqrt(sq(sx - gx) + sq(sz - gz)) + searchMargin;

        // Two candidate-Y tables: orthogonal moves may step up, diagonal moves never do (see
        // buildYTry) — both still allow the same step-down range, since dropping diagonally is fine.
        int[] orthoYTry = buildYTry(maxStepDown, true);
        int[] diagYTry = buildYTry(maxStepDown, false);
        long startId = pack(sx, sy, sz);
        long goalId = pack(gx, gy, gz);

        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Map<NavMesh.ChunkKey, Integer> wanted = new HashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>();

        gScore.put(startId, 0.0);
        open.add(new Open(startId, heuristic(sx, sy, sz, gx, gy, gz)));

        long bestNode = startId;
        double bestH = heuristic(sx, sy, sz, gx, gy, gz);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Open cur = open.poll();
            if (closed.contains(cur.id)) continue;
            closed.add(cur.id);
            expanded++;

            int cx = unpackX(cur.id), cy = unpackY(cur.id), cz = unpackZ(cur.id);
            double h = heuristic(cx, cy, cz, gx, gy, gz);
            if (h < bestH) { bestH = h; bestNode = cur.id; }
            if (cur.id == goalId) return buildResult(parent, goalId, true, wanted, false);

            for (int[] dir : DIRS) {
                boolean diagonal = dir[0] != 0 && dir[1] != 0;
                int nx = cx + dir[0], nz = cz + dir[1];

                if (diagonal) {
                    // No corner cutting: both orthogonal neighbours must themselves offer a standable
                    // step, or the diagonal is refused even if its own column looks fine. This is
                    // checking an actual orthogonal move each corner cell would take, so it keeps the
                    // orthogonal step-up allowance even though the diagonal move itself never gets one.
                    if (findStep(mesh, world, cx + dir[0], cy, cz, wanted, orthoYTry) == Integer.MIN_VALUE) continue;
                    if (findStep(mesh, world, cx, cy, cz + dir[1], wanted, orthoYTry) == Integer.MIN_VALUE) continue;
                }

                // Keep the search inside a ball around the GOAL. Without this the frontier spreads
                // outward with nothing to stop it: the node budget gets spent exploring terrain that
                // leads away from the target, and "the open set emptied" — the only honest proof that
                // no route exists — can never happen outdoors, where the reachable world is endless.
                // Bounded, exhausting the frontier means something, and it stops a doomed search from
                // marching the golem's best-partial dozens of blocks off in the wrong direction.
                if (Math.sqrt(sq(nx - gx) + sq(nz - gz)) > maxFromGoal) continue;

                NavMesh.Column col = mesh.columnAt(world, nx, nz);
                if (col == null) { wanted.merge(NavMesh.ChunkKey.of(world, nx, nz), cy, (a, b) -> a); continue; }
                int ny = findStandableY(col, cy, diagonal ? diagYTry : orthoYTry);
                if (ny == Integer.MIN_VALUE) continue;

                if (ny > cy && !mesh.isPassableAt(world, cx, cy + 2, cz)) {
                    // Standable at the TARGET only means the destination has floor/feet/head clearance
                    // -- it says nothing about whether the golem has room to jump UP into it from here,
                    // which needs the block above its own current head (source Y+2) to be clear too.
                    // Diagonal step-ups can't reach this point at all (diagYTry never contains +1), so
                    // this only ever fires for the 4 orthogonal directions, exactly where vanilla can
                    // actually attempt a jump.
                    continue;
                }

                long nid = pack(nx, ny, nz);
                int idx = col.indexOf(ny);
                byte flags = col.flags[idx];
                double cost = diagonal ? 1.414 : 1.0;
                if (ny > cy) cost += 0.5; // step up
                if ((flags & NavMesh.FLAG_WATER) != 0) cost += 2.0;
                if ((flags & NavMesh.FLAG_LAVA_ADJACENT) != 0) cost += 8.0;
                if (nearWaypoint(nx, ny, nz, waypointCells)) cost *= 0.7;
                if (penalizedCells.contains(nid)) cost += PENALTY_COST;

                double tentativeG = gScore.get(cur.id) + cost;
                Double existing = gScore.get(nid);
                if (existing == null || tentativeG < existing) {
                    gScore.put(nid, tentativeG);
                    parent.put(nid, cur.id);
                    double f = tentativeG + 1.15 * heuristic(nx, ny, nz, gx, gy, gz);
                    open.add(new Open(nid, f));
                }
            }
        }
        // Budget spent (or frontier exhausted) without reaching the goal: the best partial path beats
        // reporting failure outright, and it reproduces the old stepping-stone fallback for free.
        return buildResult(parent, bestNode, false, wanted, open.isEmpty());
    }

    /** Standable Y in the given column nearest in climbing-priority to {@code fromY} (same level,
     *  then up one, then down as far as {@code yTry} allows), or MIN_VALUE if none of those exist. */
    private static int findStandableY(NavMesh.Column col, int fromY, int[] yTry) {
        for (int dy : yTry) {
            int y = fromY + dy;
            if (col.indexOf(y) >= 0) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Same lookup as {@link #findStandableY}, but resolves the column itself first — used only for
     *  the diagonal corner-cutting check, where a missing chunk is recorded exactly like any other
     *  lookup miss but the step is simply refused (the diagonal already failed on other grounds too). */
    private static int findStep(NavMesh mesh, UUID world, int x, int fromY, int z, Map<NavMesh.ChunkKey, Integer> wanted, int[] yTry) {
        NavMesh.Column col = mesh.columnAt(world, x, z);
        if (col == null) { wanted.merge(NavMesh.ChunkKey.of(world, x, z), fromY, (a, b) -> a); return Integer.MIN_VALUE; }
        return findStandableY(col, fromY, yTry);
    }

    private static boolean nearWaypoint(int x, int y, int z, List<int[]> waypointCells) {
        for (int[] w : waypointCells) {
            if (sq(x - w[0]) + sq(y - w[1]) + sq(z - w[2]) <= 4.0) return true; // within 2 blocks
        }
        return false;
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        return octile(x, z, gx, gz) + Math.abs(y - gy);
    }

    /** Octile distance: exact cost of a horizontal move mixing straight (1.0) and diagonal (1.414)
     *  steps, weighted 1.15 by the caller — slightly suboptimal paths in exchange for a much smaller
     *  frontier, which is what makes a 256-block search finish inside the node budget. */
    private static double octile(int x, int z, int gx, int gz) {
        double dx = Math.abs(x - gx), dz = Math.abs(z - gz);
        return Math.max(dx, dz) + 0.414 * Math.min(dx, dz);
    }

    private static double sq(double v) { return v * v; }

    private static Result buildResult(Map<Long, Long> parent, long end, boolean reachedGoal,
                                       Map<NavMesh.ChunkKey, Integer> wanted, boolean frontierExhausted) {
        List<Long> path = new ArrayList<>();
        long cur = end;
        path.add(cur);
        while (parent.containsKey(cur)) {
            cur = parent.get(cur);
            path.add(cur);
        }
        java.util.Collections.reverse(path);
        return new Result(path, reachedGoal, wanted, frontierExhausted);
    }
}
