package io.github.pushkindesu.lavagolem.nav;

import org.bukkit.Location;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One pathing request for one golem, from creation through to its result landing. Navigation
 * creates this on the main thread (where {@code from}/{@code goal}/{@code waypointCells} are read
 * off the live world), hands it to the worker pool (or runs it inline when nav-async is off), and
 * polls {@code done} from later ticks — it never blocks waiting on one.
 */
public final class PathJob {
    public final UUID golemId;
    public final UUID world;
    public final Location from;
    public final Location goal;
    /** Block x/y/z of every [Waypoint] sign found near {@code from} when this job was built — plain
     *  data handed to AStar so the search never has to touch Bukkit for its cost-bias rule. */
    public final List<int[]> waypointCells;
    /** Packed cell ids a recent walk attempt for this golem actually failed to reach — a snapshot
     *  copy of Navigation's per-golem failedCells at the moment this job was built, plain data like
     *  everything else here so AStar can penalise them without ever touching Bukkit. */
    public final Set<Long> penalizedCells;

    /** Flips true once the worker pool actually accepted this job. Until then it's just sitting in
     *  Navigation waiting for a free slot under nav-max-concurrent, and gets retried next tick. */
    public volatile boolean submitted;
    /** The only field the main thread needs to poll: true once a result (success, partial, or an
     *  outright failure) exists. */
    public volatile boolean done;
    /** Valid only once {@code done} is true. */
    public volatile AStar.Result result;

    public PathJob(UUID golemId, UUID world, Location from, Location goal, List<int[]> waypointCells,
                   Set<Long> penalizedCells) {
        this.golemId = golemId;
        this.world = world;
        this.from = from;
        this.goal = goal;
        this.waypointCells = waypointCells;
        this.penalizedCells = penalizedCells;
    }
}
