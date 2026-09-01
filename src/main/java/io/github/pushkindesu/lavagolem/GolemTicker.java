package io.github.pushkindesu.lavagolem;

import io.github.pushkindesu.lavagolem.nav.Navigation;
import io.papermc.paper.world.WeatheringCopperState;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Furnace;
import org.bukkit.block.Sign;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GolemTicker extends BukkitRunnable {

    private final LavaGolemPlugin plugin;
    private final NamespacedKey stateKey;
    private final NamespacedKey targetKey;
    private final NamespacedKey nextSearchTickKey;
    private final NamespacedKey jobFurnaceKey;
    private final NamespacedKey jobAmountKey;
    private final NamespacedKey jobMaterialKey;
    private final NamespacedKey courierActiveKey;
    private final NamespacedKey courierDestKey;
    private final NamespacedKey courierRrKey;
    private final NamespacedKey courierBackoffKey;
    private final NamespacedKey alchemyJobKey;
    private final NamespacedKey biteTickKey;
    private int tickCount = 0;

    /** Short-lived cache of nearby storage containers per courier, so the expensive radius cube
     *  scan runs at most every ~10s (per stationary golem) instead of on every decision. */
    private final Map<java.util.UUID, ContainerCache> courierContainerCache = new HashMap<>();
    private static final class ContainerCache {
        long expiry; int cx, cz; List<Block> list;
    }

    /** Navigation v2 now owns all stall/progress tracking for the actual walk internally, so nothing
     *  writes to this map any more. It's kept only because abortAlchemy/abortFisher/abortForCrash —
     *  which this refactor must not touch — still call stuckProgress.remove(...) on give-up; those
     *  removes are harmless no-ops against an always-empty map. */
    private final Map<java.util.UUID, double[]> stuckProgress = new HashMap<>();

    /** Last System.currentTimeMillis() a tick-crash was logged for a golem, so one wedged golem that
     *  throws every tick doesn't spam the console — see run(). */
    private final Map<java.util.UUID, Long> lastErrorLog = new HashMap<>();

    /** Why a golem most recently gave up its search — feeds /golemdebug's live trace. Nothing else
     *  reads this yet; it exists as a foundation for future diagnostics. */
    private final Map<java.util.UUID, String> lastProblem = new HashMap<>();

    /** Fuel capacity in "furnace item slots" per fuel unit (burn_ticks / 200), hardcoded per spec. */
    private static final Map<Material, Integer> FUEL_CAPACITY = new HashMap<>();
    static {
        FUEL_CAPACITY.put(Material.LAVA_BUCKET, 100);
        FUEL_CAPACITY.put(Material.COAL_BLOCK, 80);
        FUEL_CAPACITY.put(Material.DRIED_KELP_BLOCK, 20);
        FUEL_CAPACITY.put(Material.BLAZE_ROD, 12);
        FUEL_CAPACITY.put(Material.COAL, 8);
        FUEL_CAPACITY.put(Material.CHARCOAL, 8);
    }

    public GolemTicker(LavaGolemPlugin plugin) {
        this.plugin = plugin;
        this.stateKey          = new NamespacedKey(plugin, "state");
        this.targetKey         = new NamespacedKey(plugin, "target");
        this.nextSearchTickKey = new NamespacedKey(plugin, "next_search_tick");
        this.jobFurnaceKey     = new NamespacedKey(plugin, "job_furnace");
        this.jobAmountKey      = new NamespacedKey(plugin, "job_amount");
        this.jobMaterialKey    = new NamespacedKey(plugin, "job_material");
        this.courierActiveKey  = new NamespacedKey(plugin, "courier_active");
        this.courierDestKey    = new NamespacedKey(plugin, "courier_dest");
        this.courierRrKey      = new NamespacedKey(plugin, "courier_rr");
        this.courierBackoffKey = new NamespacedKey(plugin, "courier_backoff");
        this.alchemyJobKey     = new NamespacedKey(plugin, "alchemy_job");
        this.biteTickKey       = new NamespacedKey(plugin, "bite_tick");
    }

    @Override
    public void run() {
        tickCount++;

        // Both of these run once per tick, OUTSIDE the per-golem try/catch below, so each gets its own
        // guard — an exception here must not freeze every golem's tick the way it would if it escaped
        // this method, which is exactly the failure mode that try/catch exists to prevent per-golem.
        try {
            // Builds a few more chunk navmeshes from whatever routes have asked for one this tick — see
            // Navigation's class doc on the missing-chunk protocol. Runs once per logic tick regardless
            // of how many golems there are, since the budget (nav-chunks-per-tick) is server-wide.
            plugin.navigation.drainWanted();
        } catch (Throwable t) {
            plugin.getLogger().warning("[LG] navigation.drainWanted() threw: " + t);
        }
        try {
            // Golem UUIDs change on chunk reload (HeartUseListener respawns the entity), so every map
            // here keyed by UUID would otherwise grow forever. Sweeping every ~30s is cheap next to the
            // per-tick cost of running every golem, and keeps this from being a slow leak long-term.
            if (tickCount % sweepEveryLogicTicks() == 0) sweepDeadGolems();
        } catch (Throwable t) {
            plugin.getLogger().warning("[LG] sweepDeadGolems() threw: " + t);
        }

        for (World world : Bukkit.getWorlds()) {
            // Golems are always copper golems, so scanning that narrower type instead of Mob skips a
            // PDC lookup for every other mob in the world (players' zombies, farm animals, and so on).
            // The PDC check below stays the authoritative filter — it's what tells ours apart from a
            // plain vanilla copper golem, which this class would otherwise also match.
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                if (golem.getPersistentDataContainer()
                        .get(plugin.golemEntityKey, PersistentDataType.BYTE) == null) continue;

                // Reset weathering state every ~5 seconds
                if (tickCount % 10 == 0) {
                    golem.setWeatheringState(WeatheringCopperState.UNAFFECTED);
                }

                try {
                    tickGolem(golem);
                } catch (Throwable t) {
                    // A single misbehaving golem (a corrupt PDC, a station torn down mid-job) must never
                    // abort the tick for every other golem on the server. Rate-limited per golem so one
                    // stuck in a throwing loop doesn't flood the console twice a second.
                    java.util.UUID id = golem.getUniqueId();
                    long now = System.currentTimeMillis();
                    Long last = lastErrorLog.get(id);
                    if (last == null || now - last >= 60_000L) {
                        lastErrorLog.put(id, now);
                        String role = golem.getPersistentDataContainer().getOrDefault(
                                plugin.roleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);
                        plugin.getLogger().warning("[LG] Golem " + id + " (role=" + role
                                + ") threw during its tick; recovering it to idle: " + t);
                    }
                    abortForCrash(golem);
                }
            }
        }
    }

    /** How many logic ticks make up ~30 seconds at the configured tick-period, used to space out
     *  sweepDeadGolems() — computed from config rather than hardcoded since tick-period is itself
     *  configurable and a fixed logic-tick count would drift on a server that's changed it. */
    private long sweepEveryLogicTicks() {
        long msPerLogicTick = plugin.cfg.tickPeriod * 50L;
        return Math.max(1, 30_000L / msPerLogicTick);
    }

    /** Drops per-golem transient state for any UUID that no longer resolves to a live golem. Golem
     *  UUIDs change on chunk reload (HeartUseListener respawns the entity on EntitiesLoadEvent), so
     *  without this every map here keyed by UUID grows for as long as the server stays up. */
    private void sweepDeadGolems() {
        java.util.Set<java.util.UUID> alive = new java.util.HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                if (golem.getPersistentDataContainer().has(plugin.golemEntityKey, PersistentDataType.BYTE)) {
                    alive.add(golem.getUniqueId());
                }
            }
        }
        stuckProgress.keySet().removeIf(id -> !alive.contains(id));
        lastProblem.keySet().removeIf(id -> !alive.contains(id));
        lastErrorLog.keySet().removeIf(id -> !alive.contains(id));
        courierContainerCache.keySet().removeIf(id -> !alive.contains(id));
        // gdebug no longer drops a debugWatchers entry just because the watching player is offline
        // (see gdebugInternal) -- file tracing needs to survive exactly that. This is now the only
        // thing that ever prunes it, for the one case that still needs pruning: the golem itself is
        // gone (despawned, or reloaded to a new UUID) and nobody can ever toggle that old id off again.
        plugin.debugWatchers.keySet().removeIf(id -> !alive.contains(id));
        plugin.navigation.sweep(alive);
    }

    /** Best-effort recovery once tickGolem has thrown: whatever broke, the golem must not stay wedged
     *  on a half-finished job forever. Reuses each role's own abort helper where one exists; the hauler
     *  and smelter have none, so their job state is unwound directly to the equivalent idle state. */
    private void abortForCrash(Mob golem) {
        try {
            String role = golem.getPersistentDataContainer().getOrDefault(
                    plugin.roleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);
            stuckProgress.remove(golem.getUniqueId());
            plugin.navigation.cancel(golem); // whatever it was mid-walk toward, start clean next tick
            if (LavaGolemPlugin.ROLE_SMELTER.equals(role)) {
                clearTarget(golem);
                setSmelterState(golem, "SMELTER_IDLE");
            } else if (LavaGolemPlugin.ROLE_COURIER.equals(role)) {
                abortCourier(golem);
            } else if (LavaGolemPlugin.ROLE_ALCHEMIST.equals(role)) {
                abortAlchemy(golem);
            } else if (LavaGolemPlugin.ROLE_FISHER.equals(role)) {
                abortFisher(golem);
            } else {
                clearTarget(golem);
                setState(golem, "SEEKING_BUCKET");
            }
        } catch (Throwable ignored) {
            // The recovery path touches the same PDC that just misbehaved; if it throws too, there's
            // nothing more we can safely do this tick — the golem just sits until the next one.
        }
    }

    private void tickGolem(Mob golem) {
        // Hold a golem still, whatever its role, while a player has its menu open — so it doesn't
        // walk away, and a courier never starts on a half-configured route (movement is halted
        // once, on open, in markMenuOpen). It resumes on the next tick after the menu closes.
        if (plugin.isMenuOpen(golem.getUniqueId())) return;

        // Switched off from its menu: park it until a player switches it back on.
        if (plugin.isPaused(golem)) return;

        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        String role = pdc.getOrDefault(plugin.roleKey, PersistentDataType.STRING,
                LavaGolemPlugin.ROLE_HAULER);

        // A role switched off in the config sits inert until it's switched back on.
        if (!plugin.isRoleEnabled(role)) return;

        if (LavaGolemPlugin.ROLE_SMELTER.equals(role)) {
            tickSmelter(golem, pdc);
        } else if (LavaGolemPlugin.ROLE_COURIER.equals(role)) {
            tickCourier(golem, pdc);
        } else if (LavaGolemPlugin.ROLE_ALCHEMIST.equals(role)) {
            tickAlchemist(golem, pdc);
        } else if (LavaGolemPlugin.ROLE_FISHER.equals(role)) {
            tickFisher(golem, pdc);
        } else {
            tickHauler(golem, pdc);
        }
    }

    // ===== LAVA HAULER (unchanged behavior) =====

    private void tickHauler(Mob golem, PersistentDataContainer pdc) {
        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "SEEKING_BUCKET");

        switch (state) {
            case "SEEKING_BUCKET"       -> seekBucket(golem);
            case "MOVING_TO_BUCKET"     -> moveViaHauler(golem, this::onReachBucketChest);
            case "SEEKING_CAULDRON"     -> seekCauldron(golem);
            case "MOVING_TO_CAULDRON"   -> moveViaHauler(golem, this::onReachCauldron);
            case "SEEKING_LAVA_CHEST"   -> seekLavaChest(golem);
            case "MOVING_TO_LAVA_CHEST" -> moveViaHauler(golem, this::onReachLavaChest);
        }
    }

    // ===== SMELTER =====

    private void tickSmelter(Mob golem, PersistentDataContainer pdc) {
        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "SMELTER_IDLE");

        switch (state) {
            case "SMELTER_IDLE"              -> smelterDecide(golem);
            case "SMELTER_COLLECT_FURNACE"   -> moveViaSmelter(golem, this::onReachCollectFurnace);
            case "SMELTER_TO_OUTPUT"         -> moveViaSmelter(golem, this::onReachOutputChest);
            case "SMELTER_RETRIEVE_FURNACE"  -> moveViaSmelter(golem, this::onReachRetrieveFurnace);
            case "SMELTER_RETURN_BUCKET"     -> moveViaSmelter(golem, this::onReachReturnBucket);
            case "SMELTER_FUEL_CHEST"        -> moveViaSmelter(golem, this::onReachFuelChest);
            case "SMELTER_FUEL_FURNACE"      -> moveViaSmelter(golem, this::onReachFuelFurnace);
            case "SMELTER_INPUT_CHEST"       -> moveViaSmelter(golem, this::onReachInputChest);
            case "SMELTER_INPUT_FURNACE"     -> moveViaSmelter(golem, this::onReachInputFurnace);
            default -> setSmelterState(golem, "SMELTER_IDLE");
        }
    }

    // ---- debug tracing (toggled per-golem, per-role, or server-wide by /golemdebug) ----

    /** "HH:mm:ss" shared by every line GolemTicker sends to golemdebug.log, matching the on/off
     *  markers LavaGolemPlugin writes for the same file (see GolemDebugLog.timestamp()). */
    private static final java.time.format.DateTimeFormatter DEBUG_TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ROOT);

    /** Whether this golem is traced under ANY of /golemdebug's three modes: one specific golem
     *  (debugWatchers), every golem (debugAllWatcher), or its whole role (debugRoleWatchers). The
     *  common "nobody's debugging anything" case costs two null/empty checks; role only gets looked
     *  at once neither of the cheaper two already matched. */
    private boolean isDebug(Mob golem) {
        if (plugin.debugWatchers.containsKey(golem.getUniqueId())) return true;
        if (plugin.debugAllWatcher != null) return true;
        return !plugin.debugRoleWatchers.isEmpty() && plugin.debugRoleWatchers.containsKey(roleOf(golem));
    }

    private String roleOf(Mob golem) {
        return golem.getPersistentDataContainer().getOrDefault(
                plugin.roleKey, PersistentDataType.STRING, LavaGolemPlugin.ROLE_HAULER);
    }

    private void gdebug(Mob golem, String msg) {
        gdebugInternal(golem.getUniqueId(), golem, msg);
    }

    /** Same trace as {@link #gdebug(Mob, String)}, but by UUID — the shape Navigation's callback
     *  needs, since it only ever has the golem's id, not the live entity, when it wants to trace. */
    private void gdebug(java.util.UUID golemId, String msg) {
        gdebugInternal(golemId, null, msg);
    }

    /**
     * Single funnel for every /golemdebug trace line. Whether the line was raised with a live Mob
     * (most call sites) or only a UUID (Navigation's tracer — see its class doc on why it never holds
     * a live reference), this is what decides whether ANYONE is watching and, if so, where the line
     * goes: the gate on "is anyone watching" has always lived here rather than at each call site, and
     * that now also covers the all-golems and per-role modes, not just a single targeted golem.
     *
     * Chat and file are independent. An offline chat watcher silences only the chat half — with FILE
     * or BOTH output the entry stays in debugWatchers/debugAllWatcher/debugRoleWatchers regardless, so
     * leaving a courier tracing and reading the log after logging back in actually works, which is the
     * whole reason file output exists.
     */
    private void gdebugInternal(java.util.UUID golemId, Mob golemHint, String msg) {
        java.util.UUID chatWatcher = plugin.debugWatchers.get(golemId);
        boolean traced = chatWatcher != null;
        if (!traced && plugin.debugAllWatcher != null) {
            chatWatcher = plugin.debugAllWatcher;
            traced = true;
        }
        Mob golem = golemHint;
        if (!traced && !plugin.debugRoleWatchers.isEmpty()) {
            // Only path that ever needs to resolve a live entity from a bare UUID — deliberately
            // rare, since it only runs once neither of the two cheaper checks above already matched.
            if (golem == null && Bukkit.getEntity(golemId) instanceof Mob m) golem = m;
            if (golem != null) {
                java.util.UUID rw = plugin.debugRoleWatchers.get(roleOf(golem));
                if (rw != null) { chatWatcher = rw; traced = true; }
            }
        }
        if (!traced) return;

        PluginConfig.DebugOutput out = plugin.cfg.golemdebugOutput;
        if (out != PluginConfig.DebugOutput.FILE) {
            org.bukkit.entity.Player p = chatWatcher != null ? Bukkit.getPlayer(chatWatcher) : null;
            if (p != null) {
                p.sendMessage(net.kyori.adventure.text.Component.text("[G] " + msg,
                        net.kyori.adventure.text.format.NamedTextColor.AQUA));
            }
        }
        if (out != PluginConfig.DebugOutput.CHAT) {
            if (golem == null && Bukkit.getEntity(golemId) instanceof Mob m) golem = m;
            String role = golem != null ? roleOf(golem) : "?";
            String shortId = golemId.toString().substring(0, 8);
            plugin.debugLog.enqueue("[" + DEBUG_TIME_FMT.format(java.time.LocalTime.now()) + "] ["
                    + shortId + "/" + role + "] " + msg);
        }
    }

    /** Navigation's window into /golemdebug — wired once from LavaGolemPlugin#onEnable via
     *  {@code navigation.setTracer(golemTicker::traceFromNav)} so the nav layer traces through the
     *  existing per-golem watch mechanism instead of duplicating it. The courier is the role that
     *  most needs this: it's the one whose walk is a background search plus a fallback ladder rather
     *  than a single vanilla moveTo, and until now nothing about that was visible from chat at all. */
    public void traceFromNav(java.util.UUID golemId, String msg) {
        gdebug(golemId, msg);
    }

    /** Why this golem last gave up its search — "stuck", "no [Lava] container in range", and so on.
     *  Nothing consumes this yet besides gdebug's own trace; it exists so a future diagnostic (or a
     *  GUI tooltip) has somewhere ready-made to read from. */
    public String lastProblem(Mob golem) {
        return lastProblem.get(golem.getUniqueId());
    }

    private void setLastProblem(Mob golem, String reason) {
        lastProblem.put(golem.getUniqueId(), reason);
        if (isDebug(golem)) gdebug(golem, "idle: " + reason);
    }

    private void clearLastProblem(Mob golem) {
        lastProblem.remove(golem.getUniqueId());
    }

    /** Core decision engine: picks the single best action for an idle smelter golem. */
    private void smelterDecide(Mob golem) {
        if (!canSearch(golem)) return;
        Location origin = golem.getLocation();
        boolean dbg = isDebug(golem);

        // Safety net: if we're still carrying an item (a delivery hit a full chest, a target
        // slot was occupied, or a station lacks an [Output]), deliver it BEFORE starting any new
        // job — otherwise the next job's setItemInMainHand would overwrite and destroy it.
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (held != null && held.getType() != Material.AIR) {
            Block dest = findTaggedContainer(origin, plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT), false);
            if (dest == null) dest = findTaggedContainer(origin, plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BUCKETS), false);
            if (dest != null) {
                clearLastProblem(golem);
                clearSearchCooldown(golem);
                setTarget(golem, dest.getLocation());
                setSmelterState(golem, "SMELTER_TO_OUTPUT");
            } else {
                setLastProblem(golem, "no " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT)
                        + " or " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BUCKETS) + " to hold its carried item");
                delayNextSearch(golem); // nowhere to put it yet — hold and wait
            }
            return;
        }

        StationScan scan = scanStation(golem, origin);
        List<Block> furnaces = scan.furnaces;
        if (furnaces.isEmpty()) { setLastProblem(golem, "no furnaces in range"); delayNextSearch(golem); return; }

        Block outputChest = scan.outputChest;
        Block smeltChest = scan.smeltChest;
        Block fuelChest = scan.fuelChest;

        // Work mode (set via the golem's GUI): gates which task groups this golem performs.
        String mode = golem.getPersistentDataContainer()
                .getOrDefault(plugin.modeKey, PersistentDataType.STRING, LavaGolemPlugin.MODE_BALANCED);
        boolean canLoad = !LavaGolemPlugin.MODE_COLLECT_ONLY.equals(mode);
        boolean canCollect = !LavaGolemPlugin.MODE_LOAD_ONLY.equals(mode);
        if (dbg) gdebug(golem, "decide mode=" + mode + " furnaces=" + furnaces.size()
                + " smelt=" + (smeltChest != null) + " fuel=" + (fuelChest != null)
                + " output=" + (outputChest != null));

        boolean canDeliver = canCollect && outputChest != null
                && outputChest.getState() instanceof Container oc && !isContainerFull(oc);

        // 1a) COLLECT (stalled only) — a furnace whose result slot is completely full has
        //     stopped smelting, so clearing it first preserves throughput. General result
        //     collection is deprioritized below loading (see 4).
        if (canDeliver) {
            Block stalled = selectCollectFurnace(furnaces, origin, true);
            if (stalled != null) { startCollect(golem, stalled); return; }
        }

        // 1b) RETRIEVE — a furnace whose fuel slot holds a spent non-fuel leftover
        //     (e.g. the empty BUCKET returned after a lava bucket burns). Left in place
        //     it permanently blocks refueling, so pull it out and drop it in [Output].
        {
            Block bestRetrieve = null;
            double bestDist = Double.MAX_VALUE;
            for (Block f : furnaces) {
                if (!(f.getState() instanceof Furnace furnace)) continue;
                ItemStack fuel = furnace.getInventory().getFuel();
                if (fuel == null || fuel.getType() == Material.AIR) continue;
                if (fuel.getType().isFuel()) continue; // still a usable fuel, leave it
                // A spent lava bucket appears in the fuel slot AT IGNITION and stays for the
                // whole ~1000s burn, so we must retrieve it regardless of burn state — the
                // active burn no longer depends on this slot's item.
                double d = f.getLocation().distanceSquared(origin);
                if (d < bestDist) { bestDist = d; bestRetrieve = f; }
            }
            if (bestRetrieve != null) {
                clearLastProblem(golem);
                clearSearchCooldown(golem);
                setJobFurnace(golem, bestRetrieve.getLocation());
                setTarget(golem, bestRetrieve.getLocation());
                setSmelterState(golem, "SMELTER_RETRIEVE_FURNACE");
                return;
            }
        }

        // 2) INPUT — furnace with empty input slot, [Smelt] has a smeltable material
        if (dbg && !canLoad) gdebug(golem, "step2 skipped: canLoad=false (collect-only mode)");
        if (dbg && canLoad && smeltChest == null) gdebug(golem, "step2 skipped: no [Smelt] chest found");
        if (canLoad && smeltChest != null && smeltChest.getState() instanceof Container smeltChestState) {
            Inventory smeltInv = smeltChestState.getInventory();
            Map<Material, Integer> counts = new HashMap<>();
            for (ItemStack stack : smeltInv.getContents()) {
                if (stack == null || stack.getType() == Material.AIR) continue;
                Material mt = stack.getType();
                if (!plugin.smeltableInputs.contains(mt)
                        && !plugin.blastableInputs.contains(mt)
                        && !plugin.smokableInputs.contains(mt)) continue;
                counts.merge(mt, stack.getAmount(), Integer::sum);
            }
            if (dbg) gdebug(golem, "step2 smeltable-in-chest=" + counts);

            if (!counts.isEmpty()) {
                // Pick the nearest idle furnace that can smelt something we have, together with
                // that furnace's most-abundant acceptable material. furnaceAccepts routes ores to
                // blast furnaces and food to smokers (and keeps e.g. sand out of a smoker).
                Block bestFurnace = null;
                Material bestMaterial = null;
                double bestDist = Double.MAX_VALUE;
                for (Block f : furnaces) {
                    if (!(f.getState() instanceof Furnace furnace)) continue;
                    ItemStack smelting = furnace.getInventory().getSmelting();
                    if (smelting != null && smelting.getType() != Material.AIR) continue;
                    Material m = null;
                    int mc = 0;
                    for (var e : counts.entrySet()) {
                        if (e.getValue() > mc && furnaceAccepts(f.getType(), e.getKey())) {
                            mc = e.getValue();
                            m = e.getKey();
                        }
                    }
                    if (m == null) continue;
                    double d = f.getLocation().distanceSquared(origin);
                    if (d < bestDist) { bestDist = d; bestFurnace = f; bestMaterial = m; }
                }

                if (bestFurnace != null && bestFurnace.getState() instanceof Furnace furnace) {
                    int a = counts.get(bestMaterial);
                    // Spread evenly across all idle furnaces that ALSO accept this material, so
                    // they run in parallel (for big piles this share is capped at 64 each).
                    int emptyCount = 0;
                    for (Block f : furnaces) {
                        if (!(f.getState() instanceof Furnace ff)) continue;
                        ItemStack sm = ff.getInventory().getSmelting();
                        if (sm != null && sm.getType() != Material.AIR) continue;
                        if (furnaceAccepts(f.getType(), bestMaterial)) emptyCount++;
                    }
                    int share = Math.max(1, (int) Math.ceil(a / (double) Math.max(1, emptyCount)));
                    ItemStack existingFuel = furnace.getInventory().getFuel();
                    int n;
                    if (existingFuel != null && existingFuel.getType() != Material.AIR
                            && existingFuel.getType().isFuel()) {
                        int cap = fuelCapacity(existingFuel.getType());
                        int capRem = cap * existingFuel.getAmount();
                        n = Math.min(Math.min(share, capRem), 64);
                        if (n <= 0) {
                            setLastProblem(golem, "furnace's existing fuel has no capacity left for more input");
                            delayNextSearch(golem);
                            return;
                        }
                        clearLastProblem(golem);
                        clearSearchCooldown(golem);
                        setJobFurnace(golem, bestFurnace.getLocation());
                        setJobAmount(golem, n);
                        setJobMaterial(golem, bestMaterial);
                        setTarget(golem, smeltChest.getLocation());
                        setSmelterState(golem, "SMELTER_INPUT_CHEST");
                        return;
                    } else if (fuelChest != null) {
                        // Even share across idle furnaces, capped at a full stack; the FUEL
                        // step brings exactly ceil(input / capacity) fuel units, so no waste.
                        n = Math.min(share, 64);
                        // Choose fuel for the ACTUAL batch size, not the whole pile, so a small
                        // per-furnace share doesn't needlessly commit a lava bucket.
                        Material chosenFuel = chooseFuel(fuelChest, n);
                        if (dbg) gdebug(golem, "step2 furnace ok, batch=" + n + " material=" + bestMaterial
                                + " chosenFuel=" + chosenFuel);
                        if (chosenFuel != null) {
                            clearLastProblem(golem);
                            clearSearchCooldown(golem);
                            setJobFurnace(golem, bestFurnace.getLocation());
                            setJobAmount(golem, n);
                            setJobMaterial(golem, bestMaterial);
                            setTarget(golem, smeltChest.getLocation());
                            setSmelterState(golem, "SMELTER_INPUT_CHEST");
                            return;
                        }
                    } else if (dbg) {
                        gdebug(golem, "step2 STALL: furnace has no fuel and no [Fuel] chest found");
                    }
                } else if (dbg) {
                    gdebug(golem, "step2: no idle furnace accepts the available material");
                }
            }
        }

        // 3) FUEL — furnace with non-empty input, not burning, empty fuel slot; fuel chest has fuel
        if (canLoad && fuelChest != null && fuelChest.getState() instanceof Container) {
            Block bestFurnace = null;
            double bestDist = Double.MAX_VALUE;
            for (Block f : furnaces) {
                if (!(f.getState() instanceof Furnace furnace)) continue;
                ItemStack smelting = furnace.getInventory().getSmelting();
                if (smelting == null || smelting.getType() == Material.AIR) continue;
                if (furnace.getBurnTime() > 0) continue;
                ItemStack fuel = furnace.getInventory().getFuel();
                if (fuel != null && fuel.getType() != Material.AIR) continue;
                double d = f.getLocation().distanceSquared(origin);
                if (d < bestDist) { bestDist = d; bestFurnace = f; }
            }

            if (bestFurnace != null && bestFurnace.getState() instanceof Furnace furnace) {
                ItemStack smelting = furnace.getInventory().getSmelting();
                int itemsInInput = (smelting != null) ? smelting.getAmount() : 0;
                Material chosenFuel = chooseFuel(fuelChest, Math.max(itemsInInput, 1));
                if (chosenFuel != null) {
                    int cap = fuelCapacity(chosenFuel);
                    int units = Math.max(1, (int) Math.ceil(itemsInInput / (double) cap));
                    clearLastProblem(golem);
                    clearSearchCooldown(golem);
                    setJobFurnace(golem, bestFurnace.getLocation());
                    setJobAmount(golem, units);
                    setJobMaterial(golem, chosenFuel);
                    setTarget(golem, fuelChest.getLocation());
                    setSmelterState(golem, "SMELTER_FUEL_CHEST");
                    return;
                }
            }
        }

        // 4) COLLECT (any) — lowest priority: only haul finished products once there is
        //    nothing left to load/fuel, so furnaces stay busy instead of idling while the
        //    golem shuttles single results to the chest.
        if (canDeliver) {
            Block any = selectCollectFurnace(furnaces, origin, false);
            if (any != null) { startCollect(golem, any); return; }
        }

        // 5) Nothing to do
        setLastProblem(golem, "nothing to do this cycle");
        delayNextSearch(golem);
    }

    /** Nearest furnace with a result to collect; if {@code onlyFull}, only fully-stacked (stalled) ones. */
    private Block selectCollectFurnace(List<Block> furnaces, Location origin, boolean onlyFull) {
        Block best = null;
        int bestAmount = -1;
        double bestDist = Double.MAX_VALUE;
        for (Block f : furnaces) {
            if (!(f.getState() instanceof Furnace furnace)) continue;
            ItemStack result = furnace.getInventory().getResult();
            if (result == null || result.getType() == Material.AIR) continue;
            if (onlyFull && result.getAmount() < result.getMaxStackSize()) continue;
            int amt = result.getAmount();
            double d = f.getLocation().distanceSquared(origin);
            // Prefer the LARGEST backlog, tie-break nearest. Picking nearest alone lets a cluster
            // of fast, close furnaces starve a farther one (e.g. a lone smoker) forever; the
            // neglected furnace's backlog grows until it wins, keeping collection fair.
            if (amt > bestAmount || (amt == bestAmount && d < bestDist)) {
                bestAmount = amt;
                bestDist = d;
                best = f;
            }
        }
        return best;
    }

    private void startCollect(Mob golem, Block furnace) {
        clearLastProblem(golem);
        clearSearchCooldown(golem);
        setJobFurnace(golem, furnace.getLocation());
        setTarget(golem, furnace.getLocation());
        setSmelterState(golem, "SMELTER_COLLECT_FURNACE");
    }

    /**
     * Picks the fuel material in the given chest that best fits amount {@code a}:
     * prefers LAVA_BUCKET only when a >= 32, otherwise minimizes wasted capacity
     * (cap * ceil(min(a,64)/cap) - min(a,64)), tie-breaking on higher capacity.
     */
    private Material chooseFuel(Block fuelChestBlock, int a) {
        if (!(fuelChestBlock.getState() instanceof Container fuelChest)) return null;
        Inventory inv = fuelChest.getInventory();

        Map<Material, Integer> available = new HashMap<>();
        for (ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (!stack.getType().isFuel()) continue;
            available.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        if (available.isEmpty()) return null;

        if (a >= 32 && available.containsKey(Material.LAVA_BUCKET)) {
            return Material.LAVA_BUCKET;
        }

        int target = Math.min(a, 64);
        Material best = null;
        int bestWaste = Integer.MAX_VALUE;
        int bestCap = -1;
        for (Material mat : available.keySet()) {
            if (mat == Material.LAVA_BUCKET) continue; // preferred only when a >= 32 (above)
            int cap = fuelCapacity(mat);
            int waste = cap * (int) Math.ceil(target / (double) cap) - target;
            if (waste < bestWaste || (waste == bestWaste && cap > bestCap)) {
                bestWaste = waste;
                bestCap = cap;
                best = mat;
            }
        }
        // Lava is skipped above for small batches to avoid wasting a whole bucket — but if it's the
        // ONLY fuel available, use it anyway. Otherwise a lava-only station stalls forever: the load
        // step won't commit input it can't fuel, so nothing ever gets smelted.
        if (best == null && available.containsKey(Material.LAVA_BUCKET)) {
            return Material.LAVA_BUCKET;
        }
        return best;
    }

    /**
     * Fuel capacity (items smeltable per fuel unit = burn_ticks / 200).
     * Logs/planks/wood-family fuels and anything else not explicitly mapped
     * default to 1 (usable but low priority), per spec.
     */
    private int fuelCapacity(Material mat) {
        return FUEL_CAPACITY.getOrDefault(mat, 1);
    }

    // ===== SMELTER ON REACH =====

    private void onReachCollectFurnace(Mob golem, Block block) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        boolean handEmpty = (hand == null || hand.getType() == Material.AIR);

        if (block.getState() instanceof Furnace furnace) {
            FurnaceInventory inv = furnace.getInventory();
            ItemStack result = inv.getResult();
            // Merge this furnace's result into the hand stack (same type only), up to a full stack.
            if (result != null && result.getType() != Material.AIR
                    && (handEmpty || hand.getType() == result.getType())) {
                int max = result.getMaxStackSize();
                int have = handEmpty ? 0 : hand.getAmount();
                int take = Math.min(max - have, result.getAmount());
                if (take > 0) {
                    ItemStack newHand = result.clone();
                    newHand.setAmount(have + take);
                    golem.getEquipment().setItemInMainHand(newHand);
                    result.setAmount(result.getAmount() - take);
                    inv.setResult(result.getAmount() <= 0 ? null : result);
                    hand = newHand;
                    handEmpty = false;
                }
            }
        }

        // Nothing collected and nothing carried — nothing to do.
        if (handEmpty) {
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }

        // Chain: if the hand isn't full yet, keep collecting the SAME product from other
        // furnaces before making the trip to the chest (fewer round trips on big builds).
        if (hand.getAmount() < hand.getMaxStackSize()) {
            Block next = findResultFurnace(golem.getLocation(), hand.getType(), block.getLocation());
            if (next != null) {
                setJobFurnace(golem, next.getLocation());
                setTarget(golem, next.getLocation());
                setSmelterState(golem, "SMELTER_COLLECT_FURNACE");
                return;
            }
        }

        // Hand full or no more same-type results nearby — deliver.
        Block outputChest = findTaggedContainer(golem.getLocation(),
                plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT), false);
        if (outputChest == null) {
            // No output chest (misconfigured station) — keep the item and idle.
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        setTarget(golem, outputChest.getLocation());
        setSmelterState(golem, "SMELTER_TO_OUTPUT");
    }

    /** Nearest FURNACE (excluding {@code excludeLoc}) whose result slot holds {@code type}. */
    private Block findResultFurnace(Location origin, Material type, Location excludeLoc) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;

        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (!isFurnaceMaterial(b.getType())) continue;
                    if (excludeLoc != null && b.getLocation().equals(excludeLoc)) continue;
                    if (!(b.getState() instanceof Furnace furnace)) continue;
                    ItemStack result = furnace.getInventory().getResult();
                    if (result == null || result.getType() != type) continue;
                    double d = b.getLocation().distanceSquared(origin);
                    if (d < bestDist) { bestDist = d; best = b; }
                }
            }
        }
        return best;
    }

    private void onReachOutputChest(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) {
            clearSearchCooldown(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        var leftover = chest.getInventory().addItem(hand.clone());
        if (!leftover.isEmpty()) {
            // Chest is full — re-arm the target so we keep retrying (moveToTargetSmelter cleared
            // it on arrival; without this we'd fall to IDLE and lose the carried stack).
            setTarget(golem, block.getLocation());
            return;
        }
        // Update stats
        int smelted = golem.getPersistentDataContainer()
                .getOrDefault(plugin.itemsSmeltedKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer()
                .set(plugin.itemsSmeltedKey, PersistentDataType.INTEGER, smelted + hand.getAmount());
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
        clearJobFurnace(golem);
        setSmelterState(golem, "SMELTER_IDLE");
    }

    private void onReachRetrieveFurnace(Mob golem, Block block) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        boolean handEmpty = (hand == null || hand.getType() == Material.AIR);

        if (block.getState() instanceof Furnace furnace) {
            FurnaceInventory inv = furnace.getInventory();
            ItemStack fuel = inv.getFuel();
            // Only grab a spent non-fuel leftover (e.g. empty bucket), merging into the hand.
            if (fuel != null && fuel.getType() != Material.AIR && !fuel.getType().isFuel()
                    && (handEmpty || hand.getType() == fuel.getType())) {
                int max = fuel.getMaxStackSize();
                int have = handEmpty ? 0 : hand.getAmount();
                int take = Math.min(max - have, fuel.getAmount());
                if (take > 0) {
                    ItemStack newHand = fuel.clone();
                    newHand.setAmount(have + take);
                    golem.getEquipment().setItemInMainHand(newHand);
                    fuel.setAmount(fuel.getAmount() - take);
                    inv.setFuel(fuel.getAmount() <= 0 ? null : fuel);
                    hand = newHand;
                    handEmpty = false;
                }
            }
        }

        if (handEmpty) {
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }

        // Chain: grab spent leftovers of the same type from other furnaces before delivering.
        if (hand.getAmount() < hand.getMaxStackSize()) {
            Block next = findRetrieveFurnace(golem.getLocation(), hand.getType(), block.getLocation());
            if (next != null) {
                setJobFurnace(golem, next.getLocation());
                setTarget(golem, next.getLocation());
                setSmelterState(golem, "SMELTER_RETRIEVE_FURNACE");
                return;
            }
        }

        // Empty buckets go to the station's [Output] chest (no separate [Buckets] sign needed);
        // fall back to [Buckets] so the item is never lost if a station has no output chest.
        Block dest = findTaggedContainer(golem.getLocation(),
                plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT), false);
        if (dest == null) {
            dest = findTaggedContainer(golem.getLocation(),
                    plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BUCKETS), false);
        }
        if (dest == null) {
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        setTarget(golem, dest.getLocation());
        setSmelterState(golem, "SMELTER_RETURN_BUCKET");
    }

    /** Nearest FURNACE (excluding {@code excludeLoc}) whose fuel slot holds a spent {@code type} leftover. */
    private Block findRetrieveFurnace(Location origin, Material type, Location excludeLoc) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;

        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (!isFurnaceMaterial(b.getType())) continue;
                    if (excludeLoc != null && b.getLocation().equals(excludeLoc)) continue;
                    if (!(b.getState() instanceof Furnace furnace)) continue;
                    ItemStack fuel = furnace.getInventory().getFuel();
                    if (fuel == null || fuel.getType() != type || fuel.getType().isFuel()) continue;
                    double d = b.getLocation().distanceSquared(origin);
                    if (d < bestDist) { bestDist = d; best = b; }
                }
            }
        }
        return best;
    }

    private void onReachReturnBucket(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) {
            clearSearchCooldown(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        var leftover = chest.getInventory().addItem(hand.clone());
        if (!leftover.isEmpty()) {
            // Chest is full — re-arm the target so we keep retrying instead of dropping to IDLE.
            setTarget(golem, block.getLocation());
            return;
        }
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
        clearJobFurnace(golem);
        setSmelterState(golem, "SMELTER_IDLE");
    }

    private void onReachFuelChest(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) {
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        Location jobFurnace = getJobFurnace(golem);
        if (jobFurnace == null) {
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        int amount = getJobAmount(golem);
        Material material = getJobMaterial(golem);
        ItemStack taken = gatherFromContainer(chest, material, amount, Material::isFuel);
        if (taken == null) {
            // Nothing usable found anymore — retry later
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        golem.getEquipment().setItemInMainHand(taken);
        setTarget(golem, jobFurnace);
        setSmelterState(golem, "SMELTER_FUEL_FURNACE");
    }

    /**
     * Removes up to {@code amount} of a single item type from the container, gathering ACROSS
     * multiple slots (so partial stacks don't cap the batch). Uses {@code material} when set,
     * otherwise locks onto the first type accepted by {@code fallback}. Returns the taken stack,
     * or null if nothing matched.
     */
    private ItemStack gatherFromContainer(Container chest, Material material, int amount,
                                          java.util.function.Predicate<Material> fallback) {
        Inventory inv = chest.getInventory();
        Material want = material;
        int remaining = amount;
        ItemStack collected = null;
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (want != null) {
                if (stack.getType() != want) continue;
            } else {
                if (!fallback.test(stack.getType())) continue;
                want = stack.getType(); // lock onto the first matching type
            }
            int take = Math.min(remaining, stack.getAmount());
            if (take <= 0) continue;
            if (collected == null) collected = stack.clone();
            stack.setAmount(stack.getAmount() - take);
            inv.setItem(i, stack.getAmount() <= 0 ? null : stack);
            remaining -= take;
        }
        if (collected == null) return null;
        collected.setAmount(amount - remaining);
        return collected;
    }

    private void onReachFuelFurnace(Mob golem, Block block) {
        if (!(block.getState() instanceof Furnace furnace)) {
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        FurnaceInventory inv = furnace.getInventory();
        ItemStack existingFuel = inv.getFuel();
        if (existingFuel == null || existingFuel.getType() == Material.AIR) {
            inv.setFuel(hand.clone());
        } else if (existingFuel.getType() == hand.getType()) {
            int move = Math.min(existingFuel.getMaxStackSize() - existingFuel.getAmount(), hand.getAmount());
            existingFuel.setAmount(existingFuel.getAmount() + move);
            inv.setFuel(existingFuel);
            int rest = hand.getAmount() - move;
            if (rest > 0) {
                // Slot couldn't take it all — keep the remainder; the IDLE safety net delivers it.
                hand.setAmount(rest);
                golem.getEquipment().setItemInMainHand(hand);
                setSmelterState(golem, "SMELTER_IDLE");
                return;
            }
        } else {
            // Fuel slot occupied by a different material — keep item in hand; IDLE will re-deliver it.
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
        clearJobFurnace(golem);
        setSmelterState(golem, "SMELTER_IDLE");
    }

    private void onReachInputChest(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) {
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        Location jobFurnace = getJobFurnace(golem);
        if (jobFurnace == null) {
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        int amount = getJobAmount(golem);
        Material material = getJobMaterial(golem);
        ItemStack taken = gatherFromContainer(chest, material, amount,
                m -> plugin.smeltableInputs.contains(m)
                        || plugin.blastableInputs.contains(m)
                        || plugin.smokableInputs.contains(m));
        if (taken == null) {
            // Nothing usable found anymore — retry later
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        golem.getEquipment().setItemInMainHand(taken);
        setTarget(golem, jobFurnace);
        setSmelterState(golem, "SMELTER_INPUT_FURNACE");
    }

    private void onReachInputFurnace(Mob golem, Block block) {
        if (!(block.getState() instanceof Furnace furnace)) {
            clearJobFurnace(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        FurnaceInventory inv = furnace.getInventory();
        ItemStack existingInput = inv.getSmelting();
        if (existingInput == null || existingInput.getType() == Material.AIR) {
            inv.setSmelting(hand.clone());
        } else if (existingInput.getType() == hand.getType()) {
            int move = Math.min(existingInput.getMaxStackSize() - existingInput.getAmount(), hand.getAmount());
            existingInput.setAmount(existingInput.getAmount() + move);
            inv.setSmelting(existingInput);
            int rest = hand.getAmount() - move;
            if (rest > 0) {
                // Slot couldn't take it all — keep the remainder; the IDLE safety net delivers it.
                hand.setAmount(rest);
                golem.getEquipment().setItemInMainHand(hand);
                setSmelterState(golem, "SMELTER_IDLE");
                return;
            }
        } else {
            // Input slot occupied by a different material — keep item in hand; IDLE will re-deliver it.
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
        clearJobFurnace(golem);
        setSmelterState(golem, "SMELTER_IDLE");
    }

    // ===== SMELTER MOVEMENT =====

    private void moveViaSmelter(Mob golem, ReachCallback onReach) {
        moveVia(golem, getTarget(golem), Navigation.Arrival.BLOCK,
                () -> setSmelterState(golem, "SMELTER_IDLE"), onReach);
    }

    // ===== ALCHEMIST =====

    /*
     * Brewing knowledge is HAND-MAINTAINED, unlike the smelter's inputs which are discovered by
     * scanning recipes: vanilla potion mixes cannot be read back from the API (PotionBrewer only
     * exposes addPotionMix/removePotionMix — there is no getter), so the golem has to be told the
     * shape of vanilla brewing. Keeping it staged (water -> base -> effect -> one modifier) means we
     * never need the full recipe graph, only which role an ingredient plays.
     */

    /** Turns a WATER bottle into the AWKWARD base every real potion is built on. */
    private static final java.util.Set<Material> BREW_BASE = java.util.EnumSet.of(Material.NETHER_WART);

    /** Alters an already-brewed potion (duration / power / splash / lingering / corrupt). */
    private static final java.util.Set<Material> BREW_MODIFIERS = java.util.EnumSet.of(
            Material.REDSTONE, Material.GLOWSTONE_DUST, Material.GUNPOWDER,
            Material.DRAGON_BREATH, Material.FERMENTED_SPIDER_EYE);

    /** Turns an AWKWARD base into an actual effect potion. */
    private static final java.util.Set<Material> BREW_EFFECTS = java.util.EnumSet.of(
            Material.SUGAR, Material.GLISTERING_MELON_SLICE, Material.SPIDER_EYE,
            Material.MAGMA_CREAM, Material.GHAST_TEAR, Material.BLAZE_POWDER,
            Material.RABBIT_FOOT, Material.PUFFERFISH, Material.GOLDEN_CARROT,
            Material.PHANTOM_MEMBRANE, Material.TURTLE_HELMET, Material.BREEZE_ROD,
            Material.SLIME_BLOCK, Material.STONE, Material.COBWEB);

    /** Which potion each effect ingredient produces — drives the real potion icons in the GUI, and
     *  lets a player switch individual potions off. Hand-maintained for the same reason as above. */
    public static final Map<Material, org.bukkit.potion.PotionType> BREW_RESULTS = new java.util.LinkedHashMap<>();
    static {
        BREW_RESULTS.put(Material.GLISTERING_MELON_SLICE, org.bukkit.potion.PotionType.HEALING);
        BREW_RESULTS.put(Material.GHAST_TEAR,             org.bukkit.potion.PotionType.REGENERATION);
        BREW_RESULTS.put(Material.BLAZE_POWDER,           org.bukkit.potion.PotionType.STRENGTH);
        BREW_RESULTS.put(Material.SUGAR,                  org.bukkit.potion.PotionType.SWIFTNESS);
        BREW_RESULTS.put(Material.RABBIT_FOOT,            org.bukkit.potion.PotionType.LEAPING);
        BREW_RESULTS.put(Material.MAGMA_CREAM,            org.bukkit.potion.PotionType.FIRE_RESISTANCE);
        BREW_RESULTS.put(Material.PUFFERFISH,             org.bukkit.potion.PotionType.WATER_BREATHING);
        BREW_RESULTS.put(Material.GOLDEN_CARROT,          org.bukkit.potion.PotionType.NIGHT_VISION);
        BREW_RESULTS.put(Material.SPIDER_EYE,             org.bukkit.potion.PotionType.POISON);
        BREW_RESULTS.put(Material.PHANTOM_MEMBRANE,       org.bukkit.potion.PotionType.SLOW_FALLING);
        BREW_RESULTS.put(Material.TURTLE_HELMET,          org.bukkit.potion.PotionType.TURTLE_MASTER);
        BREW_RESULTS.put(Material.BREEZE_ROD,             org.bukkit.potion.PotionType.WIND_CHARGED);
        BREW_RESULTS.put(Material.SLIME_BLOCK,            org.bukkit.potion.PotionType.OOZING);
        BREW_RESULTS.put(Material.STONE,                  org.bukkit.potion.PotionType.INFESTED);
        BREW_RESULTS.put(Material.COBWEB,                 org.bukkit.potion.PotionType.WEAVING);
    }

    /** Modifier ingredients in the order the GUI lists them. */
    public static final List<Material> BREW_MODIFIER_ORDER = List.of(
            Material.REDSTONE, Material.GLOWSTONE_DUST, Material.GUNPOWDER,
            Material.DRAGON_BREATH, Material.FERMENTED_SPIDER_EYE);

    /** Whether this golem is allowed to use {@code m} (everything is allowed until switched off). */
    public boolean alchemyAllows(Mob golem, Material m) {
        String s = golem.getPersistentDataContainer()
                .get(plugin.alchemyDisabledKey, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return true;
        for (String part : s.split(",")) {
            if (part.equals(m.name())) return false;
        }
        return true;
    }

    public void setAlchemyAllowed(Mob golem, Material m, boolean allowed) {
        java.util.Set<String> disabled = new java.util.LinkedHashSet<>();
        String s = golem.getPersistentDataContainer()
                .get(plugin.alchemyDisabledKey, PersistentDataType.STRING);
        if (s != null && !s.isEmpty()) java.util.Collections.addAll(disabled, s.split(","));
        if (allowed) disabled.remove(m.name()); else disabled.add(m.name());
        golem.getPersistentDataContainer().set(plugin.alchemyDisabledKey,
                PersistentDataType.STRING, String.join(",", disabled));
    }

    /** What the golem is currently fetching for a stand. */
    private static final String JOB_FUEL = "FUEL", JOB_BOTTLE = "BOTTLE", JOB_INGREDIENT = "INGREDIENT";
    /** An already-brewed potion taken from [Brew] to carry another stage — an awkward base, or a
     *  finished potion the player dropped back in to have a modifier applied. */
    private static final String JOB_POTION_BASE = "POTION_BASE";
    /** Grinding blaze rods into powder and restocking [Brew] with it. */
    private static final String JOB_GRIND = "GRIND";

    private void tickAlchemist(Mob golem, PersistentDataContainer pdc) {
        // Alchemical haze, so an alchemist reads at a glance from a distance (the hauler burns).
        // Particles rather than a real PotionEffect: an effect would actually act on the entity.
        golem.getWorld().spawnParticle(org.bukkit.Particle.WITCH,
                golem.getLocation().add(0, 1.0, 0), 3, 0.25, 0.3, 0.25, 0.0);

        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "ALCHEMIST_IDLE");
        switch (state) {
            case "ALCHEMIST_IDLE"      -> alchemistDecide(golem);
            case "ALCHEMIST_FETCH"     -> moveViaAlchemist(golem, this::onReachBrewChest);
            case "ALCHEMIST_TO_WATER"  -> moveViaAlchemist(golem, this::onReachWater);
            case "ALCHEMIST_TO_CRAFT"  -> moveViaAlchemist(golem, this::onReachCraftingTable);
            case "ALCHEMIST_TO_STAND"  -> moveViaAlchemist(golem, this::onReachStandLoad);
            case "ALCHEMIST_COLLECT"   -> moveViaAlchemist(golem, this::onReachStandCollect);
            case "ALCHEMIST_TO_OUTPUT" -> moveViaAlchemist(golem, this::onReachAlchemyOutput);
            default -> setAlchemistState(golem, "ALCHEMIST_IDLE");
        }
    }

    /** Core decision engine: picks the single best action for an idle alchemist. */
    private void alchemistDecide(Mob golem) {
        if (!canSearch(golem)) return;
        Location origin = golem.getLocation();

        // Safety net (same invariant as the smelter): never sit idle holding an item, because
        // starting a job overwrites the main hand and would destroy it. Finished potions go to
        // [Output]; anything else (spare powder from grinding a rod, an unplaced bottle) goes back
        // to [Brew] so it stays in circulation instead of silting up the output chest.
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (held != null && held.getType() != Material.AIR) {
            boolean finished = isPotionItem(held.getType()) && !isWaterBottle(held);
            String outTag = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT);
            String brewTag = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BREW);
            String first = finished ? outTag : brewTag;
            String second = finished ? brewTag : outTag;
            Block dest = findTaggedContainer(origin, first, false);
            if (dest == null) dest = findTaggedContainer(origin, second, false);
            if (dest != null) {
                clearLastProblem(golem);
                clearSearchCooldown(golem);
                setTarget(golem, dest.getLocation());
                setAlchemistState(golem, "ALCHEMIST_TO_OUTPUT");
            } else {
                setLastProblem(golem, "no " + outTag + " or " + brewTag + " to hold its carried item");
                delayNextSearch(golem);
            }
            return;
        }

        AlchemyScan scan = scanAlchemyStation(golem, origin);
        if (scan.stands.isEmpty() || scan.brewChest == null
                || !(scan.brewChest.getState() instanceof Container brew)) {
            setLastProblem(golem, "no brewing stand or " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BREW)
                    + " container in range");
            delayNextSearch(golem);
            return;
        }

        // Keep the pantry stocked before servicing any stand. Blaze powder is BOTH the fuel and the
        // Strength ingredient, and rods are just how players store it — so grind one into the chest
        // and let the normal fuel/ingredient jobs find powder there. Doing it here, rather than
        // inside the fuel step, is why rods now work for Strength too and not only for fuel.
        if (scan.craftingTable != null
                && !containsMaterial(brew, Material.BLAZE_POWDER)
                && containsMaterial(brew, Material.BLAZE_ROD)) {
            clearLastProblem(golem);
            clearSearchCooldown(golem);
            clearJobFurnace(golem);
            setJobMaterial(golem, Material.BLAZE_ROD);
            golem.getPersistentDataContainer().set(alchemyJobKey, PersistentDataType.STRING, JOB_GRIND);
            setTarget(golem, scan.brewChest.getLocation());
            setAlchemistState(golem, "ALCHEMIST_FETCH");
            return;
        }

        // How many potions [Output] can actually take. Potions never stack, so each one needs a
        // WHOLE empty slot — "is the chest full" is the wrong question (a chest with no empty slot
        // but a part-used stack of redstone isn't "full", yet a potion still won't fit).
        Container outChest = scan.outputChest != null
                && scan.outputChest.getState() instanceof Container c ? c : null;
        int deliverable = outChest == null ? 0 : emptySlots(outChest);
        boolean canDeliver = deliverable > 0;

        // Potions already promised to that chest: everything sitting in a bottle slot anywhere will
        // want a slot of its own eventually. Counting them stops the golem loading three bottles
        // against one free slot and stranding the other two.
        int committed = 0;
        for (Block sb : scan.stands) {
            if (sb.getState() instanceof org.bukkit.block.BrewingStand s) {
                committed += countBottles(s.getInventory());
            }
        }

        // Sort stands by distance so the golem always services the closest one that needs something.
        scan.stands.sort(java.util.Comparator.comparingDouble(b -> b.getLocation().distanceSquared(origin)));

        for (Block standBlock : scan.stands) {
            if (!(standBlock.getState() instanceof org.bukkit.block.BrewingStand stand)) continue;
            var inv = stand.getInventory();

            // An occupied ingredient slot means the stand is brewing (vanilla consumes the
            // ingredient when it finishes) — or the player is driving it by hand. Either way, leave it.
            ItemStack ingredient = inv.getIngredient();
            if (ingredient != null && ingredient.getType() != Material.AIR) continue;

            // 1) COLLECT — unload FIRST, and drain the stand completely before touching it again.
            //    Potions come out one at a time (they don't stack), so if loading could interleave,
            //    the freed slot would tempt the golem into starting a new batch on top of a
            //    half-collected one — leaving finished potions stranded and mixing two batches.
            if (collectableBottleSlot(golem, inv, brew) >= 0) {
                if (canDeliver) {
                    clearLastProblem(golem);
                    clearSearchCooldown(golem);
                    setJobFurnace(golem, standBlock.getLocation());
                    setTarget(golem, standBlock.getLocation());
                    setAlchemistState(golem, "ALCHEMIST_COLLECT");
                    return;
                }
                continue; // nowhere to put them: leave the batch be rather than brew over it
            }

            // 2) FUEL — nothing brews without it. Only ever stage ONE blaze powder: a single pinch
            //    is 20 brews, so hoarding a stack here would starve Strength potions of powder.
            ItemStack fuelSlot = inv.getFuel();
            boolean fuelSlotEmpty = fuelSlot == null || fuelSlot.getType() == Material.AIR;
            //    (Rods are already ground into powder by the pantry step above, so this only ever
            //    has to look for powder.)
            if (stand.getFuelLevel() <= 0 && fuelSlotEmpty && standHasBottles(inv)
                    && containsMaterial(brew, Material.BLAZE_POWDER)) {
                startAlchemyFetch(golem, standBlock, JOB_FUEL, Material.BLAZE_POWDER, scan);
                return;
            }

            // 3) BOTTLES — fill empty bottle slots before adding an ingredient, so brewing starts
            //    the moment the ingredient lands (and one ingredient serves all three bottles).
            // Never load a bottle we couldn't hand in: one free slot in [Output] buys one bottle,
            // not a full batch of three.
            if (freeBottleSlot(inv) >= 0 && committed < deliverable) {
                org.bukkit.potion.PotionType stage = standStage(inv);
                // A potion already sitting in [Brew] that we can carry further beats starting from
                // scratch: it skips the whole water+wart trip, and it's exactly how a player asks
                // for another stage (drop the potion back in the chest). Only take one matching what
                // the stand already holds — all three bottles brew together.
                if (findAdvanceable(golem, brew, stage) != null) {
                    startAlchemyFetch(golem, standBlock, JOB_POTION_BASE, Material.POTION, scan);
                    return;
                }
                // Otherwise start fresh from water — but only a batch we can actually finish: with
                // no target potion switched on (or no wart), this would just park water in the stand.
                if ((stage == null || stage == org.bukkit.potion.PotionType.WATER)
                        && canBrewTarget(golem, brew)) {
                    if (containsWaterBottle(brew)) {
                        startAlchemyFetch(golem, standBlock, JOB_BOTTLE, Material.POTION, scan);
                        return;
                    }
                    if (containsMaterial(brew, Material.GLASS_BOTTLE) && scan.water != null) {
                        startAlchemyFetch(golem, standBlock, JOB_BOTTLE, Material.GLASS_BOTTLE, scan);
                        return;
                    }
                }
            }

            // 4) INGREDIENT — what the stand needs depends on what stage its bottles are at.
            Material want = neededIngredient(golem, inv, brew);
            if (want != null) {
                startAlchemyFetch(golem, standBlock, JOB_INGREDIENT, want, scan);
                return;
            }
        }
        setLastProblem(golem, "nothing to do this cycle");
        delayNextSearch(golem);
    }

    /** Sends the golem to the [Brew] chest to pick up {@code material} for {@code stand}. */
    private void startAlchemyFetch(Mob golem, Block stand, String job, Material material, AlchemyScan scan) {
        clearLastProblem(golem);
        clearSearchCooldown(golem);
        setJobFurnace(golem, stand.getLocation());
        setJobMaterial(golem, material);
        golem.getPersistentDataContainer().set(alchemyJobKey, PersistentDataType.STRING, job);
        setTarget(golem, scan.brewChest.getLocation());
        setAlchemistState(golem, "ALCHEMIST_FETCH");
    }

    private void onReachBrewChest(Mob golem, Block block) {
        Material want = getJobMaterial(golem);
        Location stand = getJobFurnace(golem);
        String job = golem.getPersistentDataContainer()
                .getOrDefault(alchemyJobKey, PersistentDataType.STRING, JOB_INGREDIENT);
        // Grinding restocks the chest itself, so it's the one job with no stand attached.
        if (want == null || !(block.getState() instanceof Container chest)
                || (stand == null && !JOB_GRIND.equals(job))) {
            abortAlchemy(golem);
            return;
        }
        // Potions never stack, so everything here is carried one at a time.
        ItemStack taken;
        if (JOB_POTION_BASE.equals(job)) {
            // Re-check the stand's stage on arrival — it may have moved on while we walked over.
            ItemStack pick = findAdvanceable(golem, chest, standStageAt(stand));
            if (pick == null) { abortAlchemy(golem); return; }
            Material pm = pick.getType();
            org.bukkit.potion.PotionType pt = basePotionType(pick);
            taken = takeOne(chest, s -> s.getType() == pm && basePotionType(s) == pt);
        } else if (want == Material.POTION) {
            taken = takeOne(chest, this::isWaterBottle);
        } else {
            taken = takeOne(chest, s -> s.getType() == want);
        }
        if (taken == null) { abortAlchemy(golem); return; }

        golem.getEquipment().setItemInMainHand(taken);
        AlchemyScan scan = scanAlchemyStation(golem, golem.getLocation());
        if (want == Material.GLASS_BOTTLE) {
            if (scan.water == null) { abortAlchemy(golem); return; }
            setTarget(golem, scan.water.getLocation());
            setAlchemistState(golem, "ALCHEMIST_TO_WATER");
        } else if (want == Material.BLAZE_ROD) {
            if (scan.craftingTable == null) { abortAlchemy(golem); return; }
            setTarget(golem, scan.craftingTable.getLocation());
            setAlchemistState(golem, "ALCHEMIST_TO_CRAFT");
        } else {
            setTarget(golem, stand);
            setAlchemistState(golem, "ALCHEMIST_TO_STAND");
        }
    }

    /** Fills the carried glass bottle at a water source or cauldron (draining the cauldron a level). */
    private void onReachWater(Mob golem, Block block) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        Location stand = getJobFurnace(golem);
        if (hand == null || hand.getType() != Material.GLASS_BOTTLE || stand == null) {
            abortAlchemy(golem);
            return;
        }
        if (!drawWater(block)) { // cauldron ran dry between deciding and arriving
            setAlchemistState(golem, "ALCHEMIST_IDLE");
            return;
        }
        golem.getEquipment().setItemInMainHand(waterBottle());
        setTarget(golem, stand);
        setAlchemistState(golem, "ALCHEMIST_TO_STAND");
    }

    /** Grinds carried blaze rods into powder using the REAL vanilla recipe via the crafting engine,
     *  so the plugin never hardcodes "1 rod = 2 powder" and follows any future recipe change. */
    private void onReachCraftingTable(Mob golem, Block block) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() != Material.BLAZE_ROD) {
            abortAlchemy(golem);
            return;
        }
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.BLAZE_ROD, 1);
        ItemStack result = Bukkit.craftItem(matrix, block.getWorld());
        if (result == null || result.getType() == Material.AIR) {
            // Recipe missing or disabled by another plugin. Say so once rather than have the golem
            // look mysteriously idle — and keep the rod instead of silently eating it.
            plugin.getLogger().warning("[LG] Alchemist could not grind a blaze rod: no crafting"
                    + " recipe for blaze powder is available on this server.");
            abortAlchemy(golem);
            return;
        }
        golem.getEquipment().setItemInMainHand(result);
        // Powder goes back to [Brew]; the IDLE safety net files non-potions there, and the normal
        // fuel/ingredient jobs pick it up from the chest on the next pass.
        clearAlchemyJob(golem);
        setAlchemistState(golem, "ALCHEMIST_IDLE");
    }

    /** Puts the carried item into the stand's fuel / bottle / ingredient slot. */
    private void onReachStandLoad(Mob golem, Block block) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) { abortAlchemy(golem); return; }
        if (!(block.getState() instanceof org.bukkit.block.BrewingStand stand)) {
            setAlchemistState(golem, "ALCHEMIST_IDLE"); // stand gone; safety net re-homes the item
            return;
        }
        var inv = stand.getInventory();
        String job = golem.getPersistentDataContainer()
                .getOrDefault(alchemyJobKey, PersistentDataType.STRING, JOB_INGREDIENT);

        boolean placed = false;
        switch (job) {
            case JOB_FUEL -> {
                ItemStack fuel = inv.getFuel();
                if (fuel == null || fuel.getType() == Material.AIR) {
                    ItemStack one = hand.clone();
                    one.setAmount(1);
                    inv.setFuel(one);
                    hand.setAmount(hand.getAmount() - 1);
                    placed = true;
                }
            }
            case JOB_BOTTLE, JOB_POTION_BASE -> {
                int slot = freeBottleSlot(inv);
                if (slot >= 0) {
                    ItemStack one = hand.clone();
                    one.setAmount(1);
                    inv.setItem(slot, one);
                    hand.setAmount(hand.getAmount() - 1);
                    placed = true;
                }
            }
            default -> {
                ItemStack ing = inv.getIngredient();
                if (ing == null || ing.getType() == Material.AIR) {
                    ItemStack one = hand.clone();
                    one.setAmount(1);
                    inv.setIngredient(one);
                    hand.setAmount(hand.getAmount() - 1);
                    placed = true;
                }
            }
        }
        // Keep whatever we couldn't place in hand — the IDLE safety net delivers it rather than
        // letting the next job's setItemInMainHand destroy it.
        golem.getEquipment().setItemInMainHand(
                placed && hand.getAmount() <= 0 ? new ItemStack(Material.AIR) : hand);
        clearAlchemyJob(golem);
        setAlchemistState(golem, "ALCHEMIST_IDLE");
    }

    /** Takes one finished potion out of the stand; the IDLE safety net walks it to [Output]. */
    private void onReachStandCollect(Mob golem, Block block) {
        if (!(block.getState() instanceof org.bukkit.block.BrewingStand stand)) {
            abortAlchemy(golem);
            return;
        }
        Block brewBlock = findTaggedContainer(golem.getLocation(),
                plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BREW), false);
        Container brew = brewBlock != null && brewBlock.getState() instanceof Container c ? c : null;
        var inv = stand.getInventory();
        int slot = collectableBottleSlot(golem, inv, brew);
        if (slot < 0) { abortAlchemy(golem); return; }

        ItemStack potion = inv.getItem(slot);
        inv.setItem(slot, null);
        golem.getEquipment().setItemInMainHand(potion);
        clearAlchemyJob(golem);

        Block out = findTaggedContainer(golem.getLocation(),
                plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT), false);
        if (out == null) { setAlchemistState(golem, "ALCHEMIST_IDLE"); return; }
        setTarget(golem, out.getLocation());
        setAlchemistState(golem, "ALCHEMIST_TO_OUTPUT");
    }

    private void onReachAlchemyOutput(Mob golem, Block block) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            setAlchemistState(golem, "ALCHEMIST_IDLE");
            return;
        }
        if (!(block.getState() instanceof Container chest)) {
            setAlchemistState(golem, "ALCHEMIST_IDLE");
            return;
        }
        Map<Integer, ItemStack> leftover = chest.getInventory().addItem(hand);
        if (leftover.isEmpty()) {
            if (isPotionItem(hand.getType()) && !isWaterBottle(hand)) {
                var pdc = golem.getPersistentDataContainer();
                pdc.set(plugin.potionsBrewedKey, PersistentDataType.INTEGER,
                        pdc.getOrDefault(plugin.potionsBrewedKey, PersistentDataType.INTEGER, 0) + 1);
            }
            golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
            setAlchemistState(golem, "ALCHEMIST_IDLE");
        } else {
            // Chest filled up: keep holding it and retry, rather than dropping it on the floor.
            golem.getEquipment().setItemInMainHand(leftover.values().iterator().next());
            delayNextSearch(golem);
            setAlchemistState(golem, "ALCHEMIST_IDLE");
        }
    }

    /**
     * Which ingredient this stand wants next, or null if nothing applies. Staged deliberately so the
     * golem never needs the (unreadable) recipe graph and never mixes a dud: water only ever takes
     * nether wart, an awkward base only ever takes an effect ingredient, and a finished potion takes
     * at most ONE modifier before being collected.
     */
    private Material neededIngredient(Mob golem, org.bukkit.inventory.BrewerInventory inv, Container brew) {
        java.util.Set<Material> want = null;
        for (int i = 0; i <= 2; i++) {
            ItemStack b = inv.getItem(i);
            if (b == null || b.getType() == Material.AIR) continue;
            if (!isPotionItem(b.getType())) continue;
            org.bukkit.potion.PotionType t = basePotionType(b);
            if (t == null) continue;
            // Awkward is only ever a means to an end, so it isn't a switch of its own: if no target
            // potion is achievable, don't take water down the first step either.
            if (t == org.bukkit.potion.PotionType.WATER) {
                if (!canBrewTarget(golem, brew)) return null;
                want = BREW_BASE;
            }
            else if (t == org.bukkit.potion.PotionType.AWKWARD) want = BREW_EFFECTS;
            // Only the modifiers vanilla would really brew into THIS potion, so we never park a
            // dud in the ingredient slot (e.g. redstone on Healing, which has no long form).
            else if (isBrewedPotion(b, t)) want = modifiersFor(b, t);
            else continue; // junk (mundane/thick) — nothing more to add
            break; // all bottles in a stand brew together, so the first one decides
        }
        if (want == null) return null;
        return pickIngredient(golem, want, brew);
    }

    /**
     * Whether any target potion is achievable right now: a switched-on effect ingredient AND the base
     * to build it on are both in the chest. Guards the first step, so switching every potion off
     * really stops the golem rather than leaving it churning out awkward bases nobody asked for.
     */
    private boolean canBrewTarget(Mob golem, Container brew) {
        return pickIngredient(golem, BREW_EFFECTS, brew) != null
                && pickIngredient(golem, BREW_BASE, brew) != null;
    }

    /**
     * The ingredient from {@code want} this golem should use: the MOST ABUNDANT one present in the
     * chest and still switched on in its menu, or null if there's nothing it may use. Most-abundant
     * (as the smelter does with ore) rather than first-slot-found, so the choice doesn't look random
     * to a player who keeps several ingredients in the chest.
     */
    private Material pickIngredient(Mob golem, java.util.Set<Material> want, Container brew) {
        Material best = null;
        int bestCount = 0;
        for (ItemStack st : brew.getInventory().getContents()) {
            if (st == null || st.getType() == Material.AIR) continue;
            Material m = st.getType();
            if (!want.contains(m)) continue;
            if (!alchemyAllows(golem, m)) continue; // switched off in this golem's menu
            int c = 0;
            for (ItemStack s2 : brew.getInventory().getContents()) {
                if (s2 != null && s2.getType() == m) c += s2.getAmount();
            }
            if (c > bestCount) { bestCount = c; best = m; }
        }
        return best;
    }

    /** A bottle slot holding something we're done with: a real potion (or junk), when the [Brew]
     *  chest has nothing further to add to it. Water bottles are left alone — they're mid-recipe. */
    private int collectableBottleSlot(Mob golem, org.bukkit.inventory.BrewerInventory inv, Container brew) {
        if (brew != null && neededIngredient(golem, inv, brew) != null) return -1; // still has a stage to go
        for (int i = 0; i <= 2; i++) {
            ItemStack b = inv.getItem(i);
            if (b == null || b.getType() == Material.AIR) continue;
            if (!isPotionItem(b.getType())) continue;
            if (isWaterBottle(b)) continue;
            // An awkward base is half-finished work, never a product. Leave it in the stand until an
            // effect ingredient turns up, instead of filing it in [Output] as though it were done.
            if (basePotionType(b) == org.bukkit.potion.PotionType.AWKWARD) continue;
            return i;
        }
        return -1;
    }

    /** The brewing stage the stand's bottles are at, or null if it holds none. All three bottles
     *  brew together, so the first one speaks for the batch. */
    private org.bukkit.potion.PotionType standStage(org.bukkit.inventory.BrewerInventory inv) {
        for (int i = 0; i <= 2; i++) {
            ItemStack b = inv.getItem(i);
            if (b == null || b.getType() == Material.AIR) continue;
            if (!isPotionItem(b.getType())) continue;
            return basePotionType(b);
        }
        return null;
    }

    /** The stage of the stand at {@code loc}, or null if it's gone or empty. */
    private org.bukkit.potion.PotionType standStageAt(Location loc) {
        if (loc == null) return null;
        return loc.getBlock().getState() instanceof org.bukkit.block.BrewingStand s
                ? standStage(s.getInventory()) : null;
    }

    /** A potion in [Brew] the golem could take one stage further right now, or null. When
     *  {@code stage} is set, only a potion matching the stand's current batch qualifies. */
    private ItemStack findAdvanceable(Mob golem, Container brew, org.bukkit.potion.PotionType stage) {
        for (ItemStack st : brew.getInventory().getContents()) {
            if (st == null || st.getType() == Material.AIR) continue;
            if (!isPotionItem(st.getType())) continue;
            if (isWaterBottle(st)) continue; // water is handled by the normal fresh-batch path
            org.bukkit.potion.PotionType t = basePotionType(st);
            if (t == null) continue;
            if (stage != null && t != stage) continue;
            if (!canAdvance(golem, st, t, brew)) continue;
            return st;
        }
        return null;
    }

    /** Whether an allowed ingredient exists to move this potion on: an effect for an awkward base,
     *  a modifier for a finished one. */
    private boolean canAdvance(Mob golem, ItemStack st, org.bukkit.potion.PotionType t, Container brew) {
        if (t == org.bukkit.potion.PotionType.AWKWARD) {
            return pickIngredient(golem, BREW_EFFECTS, brew) != null;
        }
        if (isBrewedPotion(st, t)) return pickIngredient(golem, modifiersFor(st, t), brew) != null;
        return false;
    }

    /** Wholly empty slots — the only ones a non-stacking item like a potion can go into. */
    private int emptySlots(Container container) {
        Inventory inv = container.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() == Material.AIR) n++;
        }
        return n;
    }

    /** Bottles occupying this stand — each will want a slot in [Output] once it's brewed. */
    private int countBottles(org.bukkit.inventory.BrewerInventory inv) {
        int n = 0;
        for (int i = 0; i <= 2; i++) {
            ItemStack b = inv.getItem(i);
            if (b != null && b.getType() != Material.AIR) n++;
        }
        return n;
    }

    private boolean standHasBottles(org.bukkit.inventory.BrewerInventory inv) {
        for (int i = 0; i <= 2; i++) {
            ItemStack b = inv.getItem(i);
            if (b != null && b.getType() != Material.AIR) return true;
        }
        return false;
    }

    private int freeBottleSlot(org.bukkit.inventory.BrewerInventory inv) {
        for (int i = 0; i <= 2; i++) {
            ItemStack b = inv.getItem(i);
            if (b == null || b.getType() == Material.AIR) return i;
        }
        return -1;
    }

    private org.bukkit.potion.PotionType basePotionType(ItemStack stack) {
        return stack.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm
                && pm.hasBasePotionType() ? pm.getBasePotionType() : null;
    }

    /** An actual effect potion — not water, not the awkward base, not mundane/thick junk. */
    private boolean isBrewedPotion(ItemStack stack, org.bukkit.potion.PotionType t) {
        if (!isPotionItem(stack.getType())) return false;
        return t != org.bukkit.potion.PotionType.WATER
                && t != org.bukkit.potion.PotionType.AWKWARD
                && t != org.bukkit.potion.PotionType.MUNDANE
                && t != org.bukkit.potion.PotionType.THICK;
    }

    /** Base potions a fermented spider eye corrupts into something else. Can't be derived from the
     *  enum (the mapping is arbitrary), so it's listed — conservatively, plain forms only. */
    private static final java.util.Set<org.bukkit.potion.PotionType> CORRUPTIBLE = java.util.Set.of(
            org.bukkit.potion.PotionType.NIGHT_VISION, org.bukkit.potion.PotionType.SWIFTNESS,
            org.bukkit.potion.PotionType.LEAPING, org.bukkit.potion.PotionType.HEALING,
            org.bukkit.potion.PotionType.POISON);

    private boolean potionTypeExists(String name) {
        try {
            return org.bukkit.potion.PotionType.valueOf(name) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether vanilla would actually brew {@code mod} into this potion. Derived from PotionType
     * itself — LONG_STRENGTH exists but LONG_HEALING doesn't, so redstone fits Strength and not
     * Healing. This matters: an ingredient vanilla won't brew just sits in the slot, and a stand
     * with an occupied ingredient slot is one the golem leaves alone — i.e. stuck for good.
     */
    private boolean modifierApplies(ItemStack stack, org.bukkit.potion.PotionType t, Material mod) {
        Material m = stack.getType();
        return switch (mod) {
            case REDSTONE -> potionTypeExists("LONG_" + t.name());
            case GLOWSTONE_DUST -> potionTypeExists("STRONG_" + t.name());
            case GUNPOWDER -> m == Material.POTION;             // a drinkable potion becomes splash
            case DRAGON_BREATH -> m == Material.SPLASH_POTION;  // and a splash one becomes lingering
            case FERMENTED_SPIDER_EYE -> CORRUPTIBLE.contains(t);
            default -> false;
        };
    }

    /** The modifiers that fit this exact potion right now. */
    private java.util.Set<Material> modifiersFor(ItemStack stack, org.bukkit.potion.PotionType t) {
        java.util.Set<Material> out = new java.util.HashSet<>();
        for (Material mod : BREW_MODIFIERS) {
            if (modifierApplies(stack, t, mod)) out.add(mod);
        }
        return out;
    }

    private boolean isPotionItem(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    private boolean isWaterBottle(ItemStack stack) {
        return stack != null && stack.getType() == Material.POTION
                && basePotionType(stack) == org.bukkit.potion.PotionType.WATER;
    }

    private ItemStack waterBottle() {
        ItemStack it = new ItemStack(Material.POTION);
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
            pm.setBasePotionType(org.bukkit.potion.PotionType.WATER);
            it.setItemMeta(pm);
        }
        return it;
    }

    private boolean containsMaterial(Container chest, Material m) {
        for (ItemStack st : chest.getInventory().getContents()) {
            if (st != null && st.getType() == m) return true;
        }
        return false;
    }

    private boolean containsWaterBottle(Container chest) {
        for (ItemStack st : chest.getInventory().getContents()) {
            if (isWaterBottle(st)) return true;
        }
        return false;
    }

    /** Removes and returns exactly one matching item — needed because potions carry meta that a
     *  plain Material match (as the smelter uses) can't tell apart (water bottle vs finished potion). */
    private ItemStack takeOne(Container chest, java.util.function.Predicate<ItemStack> match) {
        Inventory inv = chest.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.getType() == Material.AIR) continue;
            if (!match.test(st)) continue;
            ItemStack one = st.clone();
            one.setAmount(1);
            st.setAmount(st.getAmount() - 1);
            inv.setItem(i, st.getAmount() <= 0 ? null : st);
            return one;
        }
        return null;
    }

    /** Takes one bottle's worth of water; drains a cauldron by a level (a source block is endless). */
    private boolean drawWater(Block block) {
        if (block.getType() == Material.WATER_CAULDRON) {
            if (!(block.getBlockData() instanceof org.bukkit.block.data.Levelled lv)) return false;
            int level = lv.getLevel();
            if (level <= 0) return false;
            if (level == 1) {
                block.setType(Material.CAULDRON);
            } else {
                lv.setLevel(level - 1);
                block.setBlockData(lv);
            }
            return true;
        }
        return block.getType() == Material.WATER;
    }

    private boolean isWaterSource(Block b) {
        if (b.getType() == Material.WATER_CAULDRON) {
            return b.getBlockData() instanceof org.bukkit.block.data.Levelled lv && lv.getLevel() > 0;
        }
        if (b.getType() == Material.WATER) {
            // Only a true source is endless; flowing water would vanish as the golem walks over.
            return !(b.getBlockData() instanceof org.bukkit.block.data.Levelled lv) || lv.getLevel() == 0;
        }
        return false;
    }

    private static final class AlchemyScan {
        final List<Block> stands = new ArrayList<>();
        Block brewChest, outputChest, water, craftingTable;
        double brewDist = Double.MAX_VALUE, outputDist = Double.MAX_VALUE,
               waterDist = Double.MAX_VALUE, craftDist = Double.MAX_VALUE;
    }

    /** One pass over the search cube collecting every brewing stand plus the nearest [Brew]/[Output]
     *  container, water source and crafting table. */
    private AlchemyScan scanAlchemyStation(Mob golem, Location origin) {
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;
        String brewTag   = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BREW);
        String outputTag = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT);
        AlchemyScan scan = new AlchemyScan();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material t = b.getType();
                    double d = b.getLocation().distanceSquared(origin);
                    if (t == Material.BREWING_STAND) { scan.stands.add(b); continue; }
                    if (t == Material.CRAFTING_TABLE) {
                        if (d < scan.craftDist) { scan.craftDist = d; scan.craftingTable = b; }
                        continue;
                    }
                    if (isWaterSource(b)) {
                        if (d < scan.waterDist) { scan.waterDist = d; scan.water = b; }
                        continue;
                    }
                    if (!isStorageMaterial(t)) continue;
                    if (containerHasTag(b, brewTag)) {
                        if (d < scan.brewDist) { scan.brewDist = d; scan.brewChest = b; }
                    } else if (containerHasTag(b, outputTag)) {
                        if (d < scan.outputDist) { scan.outputDist = d; scan.outputChest = b; }
                    }
                }
            }
        }
        return scan;
    }

    /** Also drives the fisher's own chest legs (FISHER_TO_RODS/FISHER_TO_OUTPUT); giving up always
     *  lands on ALCHEMIST_IDLE, which is safe for a fisher too — its own tick switch already falls
     *  back to FISHER_IDLE on any state it doesn't recognise, exactly as it did for a null target. */
    private void moveViaAlchemist(Mob golem, ReachCallback onReach) {
        moveVia(golem, getTarget(golem), Navigation.Arrival.BLOCK,
                () -> setAlchemistState(golem, "ALCHEMIST_IDLE"), onReach);
    }

    private void setAlchemistState(Mob golem, String state) {
        golem.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, state);
    }

    private void clearAlchemyJob(Mob golem) {
        clearJobFurnace(golem);
        golem.getPersistentDataContainer().remove(alchemyJobKey);
    }

    private void abortAlchemy(Mob golem) {
        stuckProgress.remove(golem.getUniqueId());
        clearAlchemyJob(golem);
        clearTarget(golem);
        delayNextSearch(golem);
        setAlchemistState(golem, "ALCHEMIST_IDLE");
    }

    // ===== FISHER =====

    /**
     * The vanilla `gameplay/fishing` table picks between junk/treasure/fish itself, but we need that
     * choice in our own hands for two reasons: the sub-table that produced an item is the only way to
     * know whether it is treasure (and so belongs in [Treasure]), and treasure has to be gated on open
     * water — a rule vanilla enforces on the bobber entity, which a golem doesn't have.
     *
     * So the split is reproduced here with vanilla's own weights and quality values, from
     * `gameplay/fishing.json`. Effective weight is `weight + quality * luck`, which is what makes
     * Luck of the Sea trade junk for treasure. The sub-tables themselves stay vanilla.
     */
    private static final int JUNK_WEIGHT = 10, JUNK_QUALITY = -2;
    private static final int TREASURE_WEIGHT = 5, TREASURE_QUALITY = 2;
    private static final int FISH_WEIGHT = 85, FISH_QUALITY = -1;

    private final java.util.Random fishRng = new java.util.Random();

    private void tickFisher(Mob golem, PersistentDataContainer pdc) {
        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "FISHER_IDLE");

        switch (state) {
            case "FISHER_IDLE"     -> fisherDecide(golem);
            case "FISHER_TO_RODS"  -> moveViaAlchemist(golem, this::onReachRodsChest);
            case "FISHER_TO_WATER" -> moveViaFisherShore(golem);
            case "FISHER_FISHING"  -> fisherFish(golem);
            case "FISHER_TO_OUTPUT"-> moveViaAlchemist(golem, this::onReachFisherOutput);
            default -> setFisherState(golem, "FISHER_IDLE");
        }
    }

    private void fisherDecide(Mob golem) {
        if (!canSearch(golem)) return;
        FisherScan scan = scanFisherStation(golem, golem.getLocation());

        // 1) Carrying a catch: get rid of it first, so a full hand never blocks fishing.
        ItemStack carried = golem.getEquipment().getItemInMainHand();
        if (carried != null && carried.getType() != Material.AIR) {
            Block dest = isTreasure(golem) && scan.treasureChest != null
                    ? scan.treasureChest : scan.outputChest;
            if (dest == null || !hasRoomFor(dest, carried)) {
                setLastProblem(golem, "no room in " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT)
                        + " for its catch");
                delayNextSearch(golem);
                return;
            }
            clearLastProblem(golem);
            setTarget(golem, dest.getLocation());
            setFisherState(golem, "FISHER_TO_OUTPUT");
            return;
        }

        // 2) No rod: fetch one. Without a rod there is nothing to do at all.
        if (rodOf(golem) == null) {
            if (scan.rodsChest == null || !(scan.rodsChest.getState() instanceof Container c)
                    || takeableRodSlot(c) < 0) {
                setLastProblem(golem, "no usable rod in " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.RODS));
                delayNextSearch(golem);
                return;
            }
            clearLastProblem(golem);
            setTarget(golem, scan.rodsChest.getLocation());
            setFisherState(golem, "FISHER_TO_RODS");
            return;
        }

        // 3) Rod in hand: go fish. Refuse to start with nowhere to put the catch, rather than
        //    fishing something up and then standing there holding it.
        if (scan.water == null) { setLastProblem(golem, "no fishable water in range"); delayNextSearch(golem); return; }
        if (scan.outputChest == null && scan.treasureChest == null) {
            setLastProblem(golem, "no " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT) + " container in range");
            delayNextSearch(golem);
            return;
        }
        // Stand on the bank and cast out to the (open) water, the way a player does. If the pond has no
        // walkable bank in range we hold rather than wade in — never send the golem into the water.
        if (scan.shore == null) { setLastProblem(golem, "no walkable bank beside the water"); delayNextSearch(golem); return; }
        clearLastProblem(golem);
        setJobFurnace(golem, scan.water.getLocation()); // the cast point
        setTarget(golem, scan.shore);                   // where to stand
        setFisherState(golem, "FISHER_TO_WATER");
    }

    /** Walks to the bank spot and starts fishing once actually standing on it. Arrival.SPOT is what
     *  keeps this a tight, point-distance arrival rather than the centre-based BLOCK one every other
     *  leg uses — the bank spot is somewhere to stand ON, not a solid block to stand next to, and a
     *  looser check here is exactly how the fisher used to end up two blocks out in the water. */
    private void moveViaFisherShore(Mob golem) {
        moveVia(golem, getTarget(golem), Navigation.Arrival.SPOT,
                () -> setFisherState(golem, "FISHER_IDLE"),
                (g, b) -> onReachFishingSpot(g));
    }

    private void onReachRodsChest(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) { abortFisher(golem); return; }
        int slot = takeableRodSlot(chest);
        if (slot < 0) { abortFisher(golem); return; }
        ItemStack rod = chest.getInventory().getItem(slot);
        ItemStack one = rod.clone();
        one.setAmount(1);
        rod.setAmount(rod.getAmount() - 1);
        chest.getInventory().setItem(slot, rod.getAmount() <= 0 ? null : rod);
        // The rod lives in the off-hand: it is the golem's tool and stays until it breaks, leaving
        // the main hand free to carry each catch to the chest.
        golem.getEquipment().setItemInOffHand(one);
        bump(golem, plugin.rodsUsedKey);
        setFisherState(golem, "FISHER_IDLE");
        clearSearchCooldown(golem);
    }

    private void onReachFishingSpot(Mob golem) {
        // The cast point was recorded at decide time; just confirm it's still water and start the wait.
        Location water = getJobFurnace(golem);
        if (water == null || !isFishable(water.getBlock())) { abortFisher(golem); return; }
        golem.getPersistentDataContainer().set(biteTickKey, PersistentDataType.LONG,
                Bukkit.getCurrentTick() + waitTicks(golem));
        setFisherState(golem, "FISHER_FISHING");
    }

    /** Vanilla waits 100-600 ticks and Lure takes 100 off that per level. */
    private long waitTicks(Mob golem) {
        long min = plugin.cfg.fisherMinWaitTicks, max = plugin.cfg.fisherMaxWaitTicks;
        long wait = min + (long) (fishRng.nextDouble() * (max - min + 1));
        ItemStack rod = rodOf(golem);
        int lure = rod == null ? 0 : rod.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LURE);
        return Math.max(20, wait - 100L * lure);
    }

    /** Roughly one bite in six gets away — a fish you didn't reel in time. It still costs the rod a
     *  point (you cast and reeled), and the golem simply casts again. */
    private static final double FISH_MISS_CHANCE = 0.17;

    private void fisherFish(Mob golem) {
        Location spot = getJobFurnace(golem);
        ItemStack rod = rodOf(golem);
        if (spot == null || rod == null || !isFishable(spot.getBlock())) { abortFisher(golem); return; }
        // The golem stands on the bank and casts out, so the cast point sits several blocks away —
        // only a real displacement (pushed off, pond rebuilt) should abort. Kept generous on purpose.
        if (golem.getLocation().distanceSquared(spot) > (plugin.cfg.searchRadius + 4) * (plugin.cfg.searchRadius + 4)) {
            abortFisher(golem);
            return;
        }

        // Face the cast point. There is no real bobber entity — that belongs to a player-owned hook,
        // which wouldn't even render a line off a golem — so a particle "float" stands in for it.
        golem.lookAt(spot.getX(), spot.getY() + 1, spot.getZ());
        Location surface = spot.clone().add(0, 1, 0);

        long now = Bukkit.getCurrentTick();
        long bite = golem.getPersistentDataContainer()
                .getOrDefault(biteTickKey, PersistentDataType.LONG, 0L);
        // getCurrentTick() restarts from 0 with the server while biteTickKey persists, so a deadline
        // left over from last session sits unreachably far ahead and the golem would fish forever.
        // Anything further out than a whole wait window is stale — see canSearch for the same trap.
        if (bite > now + plugin.cfg.fisherMaxWaitTicks) {
            golem.getPersistentDataContainer().set(biteTickKey, PersistentDataType.LONG,
                    now + waitTicks(golem));
            return;
        }
        if (now < bite) {
            // A float bobbing in place: one steady dot that rises and dips a little, so you can see
            // exactly where the line is instead of a scattered shimmer.
            double bob = Math.sin(now * 0.3) * 0.06;
            Location floatLoc = surface.clone().add(0, bob, 0);
            golem.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, floatLoc, 1, 0, 0, 0, 0);
            golem.getWorld().spawnParticle(org.bukkit.Particle.FISHING, floatLoc, 1, 0, 0, 0, 0);
            // A trail of bubbles closing in on the float just before the bite.
            if (bite - now <= 20) {
                golem.getWorld().spawnParticle(org.bukkit.Particle.BUBBLE, surface, 3, 0.3, 0, 0.3, 0.01);
            }
            return;
        }

        // A bite. Reeling always costs the rod a point, catch or miss.
        wearRod(golem, rod);
        // A splash ring where the float went under.
        golem.getWorld().spawnParticle(org.bukkit.Particle.SPLASH, surface, 16, 0.35, 0.05, 0.35, 0.12);
        golem.getWorld().spawnParticle(org.bukkit.Particle.FISHING, surface, 10, 0.3, 0.1, 0.3, 0.05);

        boolean got = fishRng.nextDouble() >= FISH_MISS_CHANCE;
        ItemStack catchItem = got ? rollCatch(golem, spot.getBlock(), rod) : null;

        if (catchItem == null) {
            // Got away (or the rod just snapped mid-reel). If the rod's gone, re-decide to fetch a new
            // one; otherwise recast where it stands.
            golem.getWorld().playSound(golem.getLocation(),
                    org.bukkit.Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.5f, 0.7f);
            if (rodOf(golem) == null) { abortFisher(golem); return; }
            golem.getPersistentDataContainer().set(biteTickKey, PersistentDataType.LONG,
                    now + waitTicks(golem));
            return;
        }

        clearJobFurnace(golem);
        golem.getPersistentDataContainer().remove(biteTickKey);
        setFisherState(golem, "FISHER_IDLE");
        clearSearchCooldown(golem);
        golem.getEquipment().setItemInMainHand(catchItem);
        golem.getWorld().playSound(golem.getLocation(),
                org.bukkit.Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.6f, 1.0f);
    }

    /**
     * A single weighted entry in a fishing sub-table: a stack to hand out, and how likely it is
     * relative to its siblings. {@code custom} entries come from the config and are handed out exactly
     * as configured (no vanilla decoration), so a configured diamond is a plain diamond.
     */
    private record Catch(int weight, Material material, int amount, boolean custom) {
        Catch(int weight, Material material, int amount) { this(weight, material, amount, false); }
    }

    /** The base table plus any config custom-catches assigned to this pool. */
    private List<Catch> withCustom(List<Catch> base, String pool) {
        List<Catch> out = new ArrayList<>(base);
        for (var cc : plugin.cfg.customCatches) {
            if (cc.pool().equals(pool)) out.add(new Catch(cc.weight(), cc.material(), cc.amount(), true));
        }
        return out;
    }

    // The vanilla fishing sub-tables, transcribed from gameplay/fishing/{fish,junk,treasure}.json.
    // The server API refuses to run these tables for us — its LootContext can't supply the `tool`
    // parameter vanilla's fishing predicates require — so the weights and items are kept here by
    // hand, exactly as the Alchemist keeps the brewing recipes the API also won't enumerate. A new
    // vanilla catch would need a plugin update to appear; for fishing that changes about once a
    // decade. Decoration (water bottle, damage, enchants) is applied in decorateCatch().
    private static final List<Catch> FISH_TABLE = List.of(
            new Catch(60, Material.COD, 1),
            new Catch(25, Material.SALMON, 1),
            new Catch(13, Material.PUFFERFISH, 1),
            new Catch(2,  Material.TROPICAL_FISH, 1));

    private static final List<Catch> JUNK_TABLE = List.of(
            new Catch(17, Material.LILY_PAD, 1),
            new Catch(10, Material.BOWL, 1),
            new Catch(10, Material.LEATHER, 1),
            new Catch(10, Material.LEATHER_BOOTS, 1),
            new Catch(10, Material.ROTTEN_FLESH, 1),
            new Catch(10, Material.POTION, 1),       // decorated into a water bottle
            new Catch(10, Material.BONE, 1),
            new Catch(10, Material.TRIPWIRE_HOOK, 1),
            new Catch(10, Material.BAMBOO, 1),
            new Catch(5,  Material.STICK, 1),
            new Catch(5,  Material.STRING, 1),
            new Catch(2,  Material.FISHING_ROD, 1),  // decorated: damaged
            new Catch(1,  Material.INK_SAC, 10));

    private static final List<Catch> TREASURE_TABLE = List.of(
            new Catch(1, Material.BOW, 1),           // decorated: enchanted + damaged
            new Catch(1, Material.ENCHANTED_BOOK, 1),// decorated: enchanted
            new Catch(1, Material.FISHING_ROD, 1),   // decorated: enchanted + damaged
            new Catch(1, Material.NAME_TAG, 1),
            new Catch(1, Material.NAUTILUS_SHELL, 1),
            new Catch(1, Material.SADDLE, 1));

    /**
     * Rolls one catch, reproducing vanilla's fishing outcome: the junk/treasure/fish split (with
     * Luck of the Sea trading junk for treasure), then a weighted pick within the chosen sub-table.
     * Records whether it was treasure so the delivery step can route it to [Treasure].
     */
    private ItemStack rollCatch(Mob golem, Block water, ItemStack rod) {
        int luck = rod.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA);
        boolean treasureOk = plugin.cfg.fisherTreasure && isOpenWater(water);

        int junk = Math.max(0, JUNK_WEIGHT + JUNK_QUALITY * luck);
        int fish = Math.max(0, FISH_WEIGHT + FISH_QUALITY * luck);
        int treasure = treasureOk ? Math.max(0, TREASURE_WEIGHT + TREASURE_QUALITY * luck) : 0;
        int total = junk + fish + treasure;
        if (total <= 0) return null;

        int roll = fishRng.nextInt(total);
        List<Catch> table;
        boolean isTreasure = false;
        if (roll < junk) {
            table = withCustom(JUNK_TABLE, "junk");
        } else if (roll < junk + treasure) {
            table = withCustom(TREASURE_TABLE, "treasure");
            isTreasure = true;
        } else {
            table = withCustom(FISH_TABLE, "fish");
        }

        ItemStack result = decorateCatch(pickWeighted(table), isTreasure);
        if (result == null) return null;
        golem.getPersistentDataContainer().set(
                plugin.treasureFlagKey, PersistentDataType.BYTE, (byte) (isTreasure ? 1 : 0));
        bump(golem, isTreasure ? plugin.treasureCaughtKey : plugin.fishCaughtKey);
        return result;
    }

    private Catch pickWeighted(List<Catch> table) {
        int total = 0;
        for (Catch c : table) total += c.weight();
        int roll = fishRng.nextInt(total);
        for (Catch c : table) {
            roll -= c.weight();
            if (roll < 0) return c;
        }
        return table.get(table.size() - 1); // unreachable, but keeps the compiler happy
    }

    /** Turns a table entry into the item vanilla would actually hand out: a water bottle for the
     *  junk potion, worn-and-enchanted gear for treasure, a battered rod for junk. */
    private ItemStack decorateCatch(Catch c, boolean isTreasure) {
        ItemStack item = new ItemStack(c.material(), c.amount());
        if (c.custom()) return item; // config item: hand it over exactly as configured
        switch (c.material()) {
            case POTION -> {
                if (item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                    pm.setBasePotionType(org.bukkit.potion.PotionType.WATER);
                    item.setItemMeta(pm);
                }
            }
            case ENCHANTED_BOOK -> enchantBook(item);
            case BOW, FISHING_ROD -> {
                if (isTreasure) enchantGear(item);
                randomDamage(item); // both junk and treasure rods/bows come up worn
            }
            case LEATHER_BOOTS -> randomDamage(item);
            default -> { /* fish and plain junk need nothing */ }
        }
        return item;
    }

    // Treasure gear comes enchanted in vanilla (an "enchant with levels 30" roll). The exact algorithm
    // isn't exposed, so we approximate: one or two fitting enchantments at a random valid level.
    private static final org.bukkit.enchantments.Enchantment[] BOW_ENCHANTS = {
            org.bukkit.enchantments.Enchantment.POWER, org.bukkit.enchantments.Enchantment.PUNCH,
            org.bukkit.enchantments.Enchantment.FLAME, org.bukkit.enchantments.Enchantment.INFINITY,
            org.bukkit.enchantments.Enchantment.UNBREAKING, org.bukkit.enchantments.Enchantment.MENDING};
    private static final org.bukkit.enchantments.Enchantment[] ROD_ENCHANTS = {
            org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, org.bukkit.enchantments.Enchantment.LURE,
            org.bukkit.enchantments.Enchantment.UNBREAKING, org.bukkit.enchantments.Enchantment.MENDING};
    private static final org.bukkit.enchantments.Enchantment[] BOOK_ENCHANTS = {
            org.bukkit.enchantments.Enchantment.SHARPNESS, org.bukkit.enchantments.Enchantment.PROTECTION,
            org.bukkit.enchantments.Enchantment.EFFICIENCY, org.bukkit.enchantments.Enchantment.FORTUNE,
            org.bukkit.enchantments.Enchantment.SILK_TOUCH, org.bukkit.enchantments.Enchantment.UNBREAKING,
            org.bukkit.enchantments.Enchantment.MENDING, org.bukkit.enchantments.Enchantment.FEATHER_FALLING,
            org.bukkit.enchantments.Enchantment.RESPIRATION, org.bukkit.enchantments.Enchantment.LOOTING};

    private void enchantGear(ItemStack item) {
        var pool = item.getType() == Material.BOW ? BOW_ENCHANTS : ROD_ENCHANTS;
        int count = 1 + fishRng.nextInt(2);
        for (int i = 0; i < count; i++) {
            var ench = pool[fishRng.nextInt(pool.length)];
            int level = 1 + fishRng.nextInt(Math.max(1, ench.getMaxLevel()));
            item.addUnsafeEnchantment(ench, level);
        }
    }

    private void enchantBook(ItemStack book) {
        if (!(book.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta meta)) return;
        var ench = BOOK_ENCHANTS[fishRng.nextInt(BOOK_ENCHANTS.length)];
        int level = 1 + fishRng.nextInt(Math.max(1, ench.getMaxLevel()));
        meta.addStoredEnchant(ench, level, true);
        book.setItemMeta(meta);
    }

    /** A fished-up tool is worn: vanilla damages it 10%-90%. */
    private void randomDamage(ItemStack item) {
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg)) return;
        int max = item.getType().getMaxDurability();
        if (max <= 0) return;
        dmg.setDamage((int) (max * (0.10 + fishRng.nextDouble() * 0.80)));
        item.setItemMeta(dmg);
    }

    private boolean isTreasure(Mob golem) {
        return golem.getPersistentDataContainer()
                .getOrDefault(plugin.treasureFlagKey, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    /**
     * Spends a point of the rod's durability, respecting Unbreaking (a 1-in-(level+1) chance to be
     * spared), and snaps the rod when it is used up. The golem earns no XP, so Mending can never
     * repair it — rods genuinely run out, which is what keeps a fisher a resource sink.
     */
    private void wearRod(Mob golem, ItemStack rod) {
        int unbreaking = rod.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
        if (unbreaking > 0 && fishRng.nextInt(unbreaking + 1) != 0) return;

        if (!(rod.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg)) return;
        int max = dmg.hasMaxDamage() ? dmg.getMaxDamage() : rod.getType().getMaxDurability();
        int next = dmg.getDamage() + 1;
        if (next >= max) {
            golem.getEquipment().setItemInOffHand(null);
            golem.getWorld().playSound(golem.getLocation(),
                    org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);
            return;
        }
        dmg.setDamage(next);
        rod.setItemMeta(dmg);
        golem.getEquipment().setItemInOffHand(rod);
    }

    private void onReachFisherOutput(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) { abortFisher(golem); return; }
        ItemStack carried = golem.getEquipment().getItemInMainHand();
        if (carried == null || carried.getType() == Material.AIR) {
            setFisherState(golem, "FISHER_IDLE");
            return;
        }
        var leftover = chest.getInventory().addItem(carried);
        if (!leftover.isEmpty()) { abortFisher(golem); return; } // filled up en route; try again
        golem.getEquipment().setItemInMainHand(null);
        golem.getPersistentDataContainer().remove(plugin.treasureFlagKey);
        setFisherState(golem, "FISHER_IDLE");
        clearSearchCooldown(golem);
    }

    /** A rod the golem can actually fish with — any fishing rod that isn't already worn out. */
    private int takeableRodSlot(Container chest) {
        Inventory inv = chest.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.getType() != Material.FISHING_ROD) continue;
            if (st.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
                int max = dmg.hasMaxDamage() ? dmg.getMaxDamage() : st.getType().getMaxDurability();
                if (dmg.getDamage() >= max) continue;
            }
            return i;
        }
        return -1;
    }

    private ItemStack rodOf(Mob golem) {
        ItemStack off = golem.getEquipment().getItemInOffHand();
        return (off != null && off.getType() == Material.FISHING_ROD) ? off : null;
    }

    /** Only real water is fishable — a cauldron is not a pond. */
    private boolean isFishable(Block b) {
        return b.getType() == Material.WATER
                && (!(b.getBlockData() instanceof org.bukkit.block.data.Levelled lv) || lv.getLevel() == 0);
    }

    /**
     * Vanilla only yields treasure in open water: a 5x5 area of water around the bobber, clear above.
     * Approximated here around the golem's fishing block, which is what stops a 1x1 hole in the floor
     * from being a treasure farm and makes you dig a real pond.
     */
    private boolean isOpenWater(Block water) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Block b = water.getRelative(dx, 0, dz);
                if (b.getType() != Material.WATER) return false;
                if (!b.getRelative(0, 1, 0).getType().isAir()) return false;
            }
        }
        return true;
    }

    private boolean hasRoomFor(Block chestBlock, ItemStack item) {
        if (!(chestBlock.getState() instanceof Container c)) return false;
        Inventory inv = c.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.getType() == Material.AIR) return true;
            if (st.isSimilar(item) && st.getAmount() < st.getMaxStackSize()) return true;
        }
        return false;
    }

    private void bump(Mob golem, NamespacedKey key) {
        var pdc = golem.getPersistentDataContainer();
        pdc.set(key, PersistentDataType.INTEGER,
                pdc.getOrDefault(key, PersistentDataType.INTEGER, 0) + 1);
    }

    private static final class FisherScan {
        Block rodsChest, outputChest, treasureChest, water;
        Location shore; // a land spot at the pond's edge, nearest the golem — where it stands to cast
        double rodsDist = Double.MAX_VALUE, outputDist = Double.MAX_VALUE,
               treasureDist = Double.MAX_VALUE, waterDist = Double.MAX_VALUE,
               shoreDist = Double.MAX_VALUE;
    }

    /** A dry standing spot beside a water block: an air-over-solid-land neighbour that isn't itself
     *  water and isn't floating on water. Returns the golem's feet location, or null if the block has
     *  no walkable bank (an interior pond block, or water walled in). */
    /** True if the golem could stand at {@code feet}: air for body and head, a solid non-water floor
     *  under it. */
    private boolean standable(Block feet) {
        if (feet.getType() == Material.WATER) return false;
        Block head = feet.getRelative(0, 1, 0);
        Block below = feet.getRelative(0, -1, 0);
        if (below.getType() == Material.WATER) return false; // floating on the surface — not a foothold
        return feet.isPassable() && head.isPassable() && below.getType().isSolid();
    }

    /**
     * Finds a dry foothold from which to fish this water block: a bank beside it, or a deck above it
     * (a pier/bridge). Returns the golem's feet location, or null if there's nowhere to stand.
     *
     * Two shapes are covered. A **bank** is a standing spot in one of the eight horizontal neighbours,
     * within a couple of blocks up or one down (grass at the water's level, a low ledge, a shallow
     * step) — the everyday pond edge. A **deck** is the first solid block going straight up the water
     * column (a pier walkway some blocks above the surface); the golem stands on top of it and casts
     * down. Banks win over decks, and lower spots win over higher, so it hugs the nearest real edge.
     */
    private Location shoreSpotForWater(Block water) {
        int[][] off = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int dy = -1; dy <= 2; dy++) {          // lower spots first: hug the water's own level
            for (int[] o : off) {
                Block feet = water.getRelative(o[0], dy, o[1]);
                if (standable(feet)) {
                    return new Location(feet.getWorld(),
                            feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                }
            }
        }
        // No bank beside it — look for a deck directly above (a pier). Rise until the first solid
        // block, then stand on top of it if there's headroom.
        for (int up = 2; up <= 8; up++) {
            Block b = water.getRelative(0, up, 0);
            if (b.getType() == Material.WATER) continue;
            if (b.getType().isSolid()) {
                Block feet = b.getRelative(0, 1, 0);
                if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()) {
                    return new Location(feet.getWorld(),
                            feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                }
                break; // solid but capped — the column is blocked, no deck here
            }
        }
        return null;
    }

    /** One pass over the search cube for the [Rods]/[Output]/[Treasure] containers and the nearest
     *  fishable water. Prefers open water, so a fisher with a real pond in range gets the treasure
     *  rolls even if a decorative puddle happens to sit closer. */
    private FisherScan scanFisherStation(Mob golem, Location origin) {
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;
        String rodsTag     = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.RODS);
        String outputTag   = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT);
        String treasureTag = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.TREASURE);
        FisherScan scan = new FisherScan();
        boolean waterIsOpen = false;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material t = b.getType();
                    double d = b.getLocation().distanceSquared(origin);
                    if (isFishable(b)) {
                        boolean open = isOpenWater(b);
                        // An open-water spot always beats a closed one; distance only breaks ties.
                        if ((open && !waterIsOpen) || (open == waterIsOpen && d < scan.waterDist)) {
                            scan.waterDist = d; scan.water = b; waterIsOpen = open;
                        }
                        // Track the bank spot nearest the golem, from whichever edge water touches land.
                        Location bank = shoreSpotForWater(b);
                        if (bank != null) {
                            double bd = bank.distanceSquared(origin);
                            if (bd < scan.shoreDist) { scan.shoreDist = bd; scan.shore = bank; }
                        }
                        continue;
                    }
                    if (!isStorageMaterial(t)) continue;
                    if (containerHasTag(b, rodsTag)) {
                        if (d < scan.rodsDist) { scan.rodsDist = d; scan.rodsChest = b; }
                    } else if (containerHasTag(b, treasureTag)) {
                        if (d < scan.treasureDist) { scan.treasureDist = d; scan.treasureChest = b; }
                    } else if (containerHasTag(b, outputTag)) {
                        if (d < scan.outputDist) { scan.outputDist = d; scan.outputChest = b; }
                    }
                }
            }
        }
        return scan;
    }

    private void setFisherState(Mob golem, String state) {
        golem.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, state);
    }

    private void abortFisher(Mob golem) {
        stuckProgress.remove(golem.getUniqueId());
        clearJobFurnace(golem);
        clearTarget(golem);
        golem.getPersistentDataContainer().remove(biteTickKey);
        delayNextSearch(golem);
        setFisherState(golem, "FISHER_IDLE");
    }

    // ===== COURIER =====

    private void tickCourier(Mob golem, PersistentDataContainer pdc) {
        ensureCourierNav(golem);
        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "COURIER_IDLE");
        switch (state) {
            case "COURIER_IDLE"      -> courierDecide(golem);
            case "COURIER_TO_SOURCE" -> moveViaCourier(golem, (g, b) -> { onCourierProgress(g); onReachCourierSource(g, b); });
            case "COURIER_TO_DEST"   -> moveViaCourier(golem, (g, b) -> { onCourierProgress(g); onReachCourierDest(g, b); });
            default -> setCourierState(golem, "COURIER_IDLE");
        }
    }

    private void courierDecide(Mob golem) {
        if (!canSearch(golem)) return;
        Location origin = golem.getLocation();

        // Safety net: deliver a carried item before starting a new pickup (same rule as the smelter).
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (held != null && held.getType() != Material.AIR) {
            Location dest = getCourierDest(golem);
            if (dest != null) {
                clearSearchCooldown(golem);
                startCourierMove(golem, dest);
                setCourierState(golem, "COURIER_TO_DEST");
            } else {
                delayNextSearch(golem); // nowhere recorded to put it — hold
            }
            return;
        }

        List<CourierRoute> routes = getCourierRoutes(golem);
        if (routes.isEmpty()) { delayNextSearch(golem); return; }

        int r = plugin.cfg.courierSearchRadius;
        int start = golem.getPersistentDataContainer().getOrDefault(courierRrKey, PersistentDataType.INTEGER, 0);
        // Scan the (large) cube ONCE (cached ~10s), then evaluate every route against the containers.
        List<Block> containers = courierScan(golem, origin, r).list;
        if (containers.isEmpty()) { delayNextSearch(golem); return; }
        // Round-robin over routes so no single route starves the others.
        for (int k = 0; k < routes.size(); k++) {
            int idx = ((start + k) % routes.size() + routes.size()) % routes.size();
            CourierRoute route = routes.get(idx);
            if (!route.isConfigured()) continue;
            Block source = nearestSource(origin, containers, route);
            if (source == null) continue;
            Block dest = nearestDest(origin, containers, route.dest);
            if (dest == null) continue;
            if (source.getLocation().equals(dest.getLocation())) continue;

            clearSearchCooldown(golem);
            clearLastProblem(golem);
            clearCourierStuckMarker(golem);
            golem.getPersistentDataContainer().set(courierActiveKey, PersistentDataType.INTEGER, idx);
            golem.getPersistentDataContainer().set(courierRrKey, PersistentDataType.INTEGER, (idx + 1) % routes.size());
            setCourierDest(golem, dest.getLocation());
            startCourierMove(golem, source.getLocation());
            setCourierState(golem, "COURIER_TO_SOURCE");
            gdebug(golem, "route " + idx + ": pickup " + fmtLoc(source.getLocation())
                    + " -> drop " + fmtLoc(dest.getLocation()));
            return;
        }
        delayNextSearch(golem);
    }

    private void onReachCourierSource(Mob golem, Block block) {
        CourierRoute route = activeCourierRoute(golem);
        Location dest = getCourierDest(golem);
        if (route == null || dest == null || !(block.getState() instanceof Container chest)) {
            abortCourier(golem);
            return;
        }
        ItemStack taken = gatherFromContainer(chest, null, plugin.cfg.courierCarryLimit, route::carries);
        if (taken == null) { abortCourier(golem); return; }
        golem.getEquipment().setItemInMainHand(taken);
        startCourierMove(golem, dest);
        setCourierState(golem, "COURIER_TO_DEST");
    }

    private void onReachCourierDest(Mob golem, Block block) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) { abortCourier(golem); return; }

        if (!(block.getState() instanceof Container chest)) {
            // Destination gone — re-find by the active route's dest tag; else hold (IDLE net retries).
            CourierRoute route = activeCourierRoute(golem);
            Block redo = (route != null)
                    ? findCourierDest(golem.getLocation(), route.dest, plugin.cfg.courierSearchRadius) : null;
            if (redo != null) {
                setCourierDest(golem, redo.getLocation());
                startCourierMove(golem, redo.getLocation());
                setCourierState(golem, "COURIER_TO_DEST");
            } else {
                setCourierState(golem, "COURIER_IDLE");
            }
            return;
        }

        CourierRoute route = activeCourierRoute(golem);

        var leftover = chest.getInventory().addItem(hand.clone());
        if (!leftover.isEmpty()) {
            // Destination is full. Don't block the courier forever — try another container with
            // the same dest tag; if none, return the load to a source-tagged container so the hand
            // frees up and other routes can run (this full route is skipped at decide time anyway).
            if (route == null) {
                // Route was deleted mid-carry: stop retrying; idle and let the safety net re-home.
                clearCourierJob(golem);
                setCourierState(golem, "COURIER_IDLE");
                return;
            }
            int r = plugin.cfg.courierSearchRadius;
            Block alt = findCourierDest(golem.getLocation(), route.dest, r);
            if (alt != null && !alt.getLocation().equals(block.getLocation())) {
                setCourierDest(golem, alt.getLocation());
                startCourierMove(golem, alt.getLocation());
                return;
            }
            Block back = findCourierDest(golem.getLocation(), route.source, r);
            if (back != null) {
                setCourierDest(golem, back.getLocation());
                startCourierMove(golem, back.getLocation());
                return;
            }
            startCourierMove(golem, block.getLocation()); // nowhere to offload — hold and retry
            return;
        }
        // Only count real deliveries to a dest-tagged container (not loads returned to a source).
        if (route != null && containerHasTag(block, route.dest)) {
            int moved = golem.getPersistentDataContainer().getOrDefault(plugin.itemsMovedKey, PersistentDataType.INTEGER, 0);
            golem.getPersistentDataContainer().set(plugin.itemsMovedKey, PersistentDataType.INTEGER, moved + hand.getAmount());
        }
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
        clearCourierJob(golem);
        setCourierState(golem, "COURIER_IDLE");
    }

    /** Raise a courier's follow (pathfinding) range so it can walk longer routes around corners
     *  and up stairs on its own legs. Applied every tick so existing golems get it without a reload. */
    private void ensureCourierNav(Mob golem) {
        try {
            org.bukkit.attribute.AttributeInstance attr =
                    golem.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE);
            if (attr != null) {
                // Follow range caps how long a path the navigation will build; raise it toward the
                // search radius (capped at 96) so it can actually walk a long route end to end.
                double want = Math.min(plugin.cfg.courierSearchRadius + 8, 96);
                if (attr.getBaseValue() < want) attr.setBaseValue(want);
            }
        } catch (Throwable ignored) { /* attribute unavailable on this version */ }
    }

    // ===== COURIER MOVEMENT =====

    /** Begins a movement leg toward {@code finalLoc} (a container). Navigation now owns the actual
     *  routing (A* over the navmesh, with waypoint-hopping as its own last-resort fallback), so this
     *  is just the shared target field every other role already uses — no separate hop bookkeeping. */
    private void startCourierMove(Mob golem, Location finalLoc) {
        setTarget(golem, finalLoc);
    }

    private void moveViaCourier(Mob golem, ReachCallback onReach) {
        moveVia(golem, getTarget(golem), Navigation.Arrival.BLOCK,
                () -> setCourierState(golem, "COURIER_IDLE"),
                loc -> {
                    // Navigation has already tried full A*, a fresh recompute, and the waypoint-hop
                    // fallback by the time it reports STUCK. Teleporting past that is now an opt-in
                    // legacy crutch (courier-teleport, default off) for servers with geometry that
                    // genuinely can't be walked; the intended behaviour is to give up LOUDLY instead —
                    // recorded in lastProblem, surfaced in the GUI (courierRouteStatus) and in-world
                    // (the stuck-name marker), and retried on a backoff so a permanently broken route
                    // costs almost nothing while a fixed one recovers on its own.
                    if (plugin.cfg.courierTeleport) {
                        gdebug(golem, "stuck: teleporting toward target " + fmtLoc(loc));
                        golem.teleport(safeSpotNear(loc));
                        plugin.navigation.cancel(golem);
                    } else {
                        clearTarget(golem);
                        setLastProblem(golem, "stuck");
                        markCourierStuck(golem);
                        applyCourierBackoff(golem);
                        // Cargo left in the golem's hand out here, wherever "stuck" happened to be,
                        // is out of the station entirely -- no route can ever use it again. Re-home it
                        // exactly like onReachCourierDest would (alt dest-tagged container, else a
                        // source-tagged one) rather than stranding it or ever dropping it.
                        rehomeCourierLoad(golem);
                    }
                },
                onReach);
    }

    // ===== COURIER give-up handling: loud, in-world, and self-recovering =====

    /** Called the moment the courier successfully ARRIVES at either leg of a job — proof the route
     *  (or at least this stretch of it) is currently walkable. Resets the backoff so a route that's
     *  been fixed recovers on its own the very next time it's tried, with no player action needed. */
    private void onCourierProgress(Mob golem) {
        golem.getPersistentDataContainer().remove(courierBackoffKey);
    }

    /** Doubles the search cooldown each consecutive time the active route exhausts the fallback
     *  ladder, capped at ~60s, instead of retrying at the normal (search-cooldown-ticks) pace. A
     *  route that's genuinely broken (a torn-up bridge, a walled-off door) then costs almost nothing
     *  in repeated decision churn, while onCourierProgress clears this the moment it works again. */
    private static final long COURIER_BACKOFF_CAP_TICKS = 1200; // ~60s at 20 ticks/sec

    private void applyCourierBackoff(Mob golem) {
        int strikes = golem.getPersistentDataContainer()
                .getOrDefault(courierBackoffKey, PersistentDataType.INTEGER, 0);
        long cooldown = Math.min(COURIER_BACKOFF_CAP_TICKS,
                plugin.cfg.searchCooldownTicks * (1L << Math.min(strikes, 20)));
        golem.getPersistentDataContainer().set(nextSearchTickKey, PersistentDataType.LONG,
                Bukkit.getCurrentTick() + cooldown);
        golem.getPersistentDataContainer().set(courierBackoffKey, PersistentDataType.INTEGER,
                Math.min(strikes + 1, 30)); // capped so the shift above can never overflow a long
    }

    /**
     * Best-effort re-home for a carried item when the courier's fallback ladder gives up terminally
     * (moveViaCourier's onStuck, non-teleport branch): cargo left sitting in the golem's hand out in
     * a field wherever it happened to give up is out of the station entirely, and no route can ever
     * use it again. Mirrors onReachCourierDest's own full-destination resolution — try an alternative
     * dest-tagged container, then fall back to a source-tagged one — without touching that protected
     * method at all: this runs from a completely different context (the golem isn't standing next to
     * any particular container right now), so it just redirects the target and lets the ordinary
     * COURIER_TO_DEST leg (Navigation + the untouched onReachCourierDest) do the actual delivery once
     * it arrives. If nothing reachable would take the item, it stays in hand and the golem idles —
     * standing still holding it is the correct last resort; it is never dropped on the ground.
     */
    /** Whether two locations point at the same block. Needed because stored job locations come back
     *  from parseLoc centred (x+0.5), while a Block's own location sits on the corner — so comparing
     *  them with equals() silently never matches, however identical the block actually is. */
    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null) return false;
        return a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void rehomeCourierLoad(Mob golem) {
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            setCourierState(golem, "COURIER_IDLE");
            return;
        }
        CourierRoute route = activeCourierRoute(golem);
        // The destination we just failed to reach, captured before any setCourierDest overwrites it.
        // Without excluding it, a route whose dest tag marks a single container -- the ordinary case --
        // would "find an alternative", get handed back the very chest it couldn't reach, and loop
        // there forever without ever falling through to returning the load to its source.
        Location failed = getCourierDest(golem);
        if (route != null) {
            int r = plugin.cfg.courierSearchRadius;
            Location origin = golem.getLocation();
            Block alt = findCourierDest(origin, route.dest, r);
            if (alt != null && !sameBlock(alt.getLocation(), failed)) {
                setCourierDest(golem, alt.getLocation());
                startCourierMove(golem, alt.getLocation());
                setCourierState(golem, "COURIER_TO_DEST");
                return;
            }
            Block back = findCourierDest(origin, route.source, r);
            if (back != null) {
                setCourierDest(golem, back.getLocation());
                startCourierMove(golem, back.getLocation());
                setCourierState(golem, "COURIER_TO_DEST"); // onReachCourierDest doesn't care which tag it was
                return;
            }
        }
        // Nothing reachable to hand it back to right now -- hold the load and idle. courierDecide's
        // own carried-item safety net already re-tries whatever courierDestKey currently points at
        // every time canSearch() next allows it (paced by the backoff above), so this keeps trying
        // on its own without spinning: never a busy loop, never a dropped item.
        setCourierState(golem, "COURIER_IDLE");
    }

    /** Short, in-world "something's wrong here" signal that needs no menu open to see — a player
     *  walking past a base full of golems should be able to spot the stuck one at a glance. No
     *  particles: those would fire every tick a golem sits idle, which is most of the time by design. */
    private static final String STUCK_NAME_SUFFIX = " (stuck)";

    private void markCourierStuck(Mob golem) {
        String name = plainCustomName(golem);
        if (name.endsWith(STUCK_NAME_SUFFIX)) return; // already marked
        golem.customName(net.kyori.adventure.text.Component.text(name + STUCK_NAME_SUFFIX,
                net.kyori.adventure.text.format.NamedTextColor.RED));
        golem.setCustomNameVisible(true);
    }

    /** Clears the stuck marker the moment the golem starts trying its route again (courierDecide),
     *  which is optimistic on purpose — if the very next attempt fails too, markCourierStuck simply
     *  reapplies it once the ladder is exhausted again. */
    private void clearCourierStuckMarker(Mob golem) {
        String name = plainCustomName(golem);
        if (!name.endsWith(STUCK_NAME_SUFFIX)) return;
        String restored = name.substring(0, name.length() - STUCK_NAME_SUFFIX.length());
        golem.customName(net.kyori.adventure.text.Component.text(restored, net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    private String plainCustomName(Mob golem) {
        net.kyori.adventure.text.Component name = golem.customName();
        return name == null ? "" : PlainTextComponentSerializer.plainText().serialize(name);
    }

    /** Value-equality for two CourierRoute instances by source/dest tag — routes are re-parsed from
     *  PDC on every call (see getCourierRoutes), so reference equality would never match even for
     *  what's conceptually the same configured route. Used only to check whether the route a status
     *  lookup is asking about is the one lastProblem was recorded against. */
    private boolean sameRoute(CourierRoute a, CourierRoute b) {
        return a != null && b != null
                && java.util.Objects.equals(a.source, b.source)
                && java.util.Objects.equals(a.dest, b.dest);
    }

    /** True when {@code target} sits in a different world than the golem right now — carried through
     *  a portal, or the world was unloaded/reloaded since the target was saved. Location#distance()
     *  (and distanceSquared) throws IllegalArgumentException across worlds, so every movement method
     *  runs its target through this first and treats a mismatch exactly like a missing target. */
    private boolean wrongWorld(Mob golem, Location target) {
        return target.getWorld() != golem.getWorld();
    }

    /** Short "x,y,z" for a debug trace line -- concise on purpose, since gdebug output goes straight
     *  to a player's chat. */
    private String fmtLoc(Location l) {
        return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    // ===== NAVIGATION v2 — the single entry point every role's movement goes through =====

    /**
     * Delegates one logic tick of movement toward {@code target} to {@link Navigation} and reacts to
     * what it reports: ARRIVED clears the target and fires {@code onReach} (exactly as every old
     * moveToTarget* method did), STUCK hands off to {@code onStuck} to decide how this role gives up,
     * and MOVING/COMPUTING need nothing from GolemTicker at all — Navigation is already steering the
     * golem (or waiting on a background search) and will keep doing so next tick.
     *
     * A null or wrong-world target never reaches Navigation — that guard is preserved here exactly as
     * every old movement method ran it first, so a stale target from before a portal trip or a world
     * reload is handled identically to one that was never set.
     */
    private void moveVia(Mob golem, Location target, Navigation.Arrival mode,
                         Runnable onNoTarget, java.util.function.Consumer<Location> onStuck,
                         ReachCallback onReach) {
        if (target != null && wrongWorld(golem, target)) { clearTarget(golem); target = null; }
        if (target == null) { onNoTarget.run(); return; }
        Navigation.Status status = plugin.navigation.tick(golem, target, mode);
        switch (status) {
            case ARRIVED -> {
                Block block = target.getBlock();
                clearTarget(golem);
                onReach.onReach(golem, block);
            }
            case STUCK -> onStuck.accept(target);
            case MOVING, COMPUTING -> { /* Navigation is already handling this tick's steering */ }
        }
    }

    /** Convenience for the shape every non-courier, non-hauler role shares: give up to the same idle
     *  state whether the target vanished or Navigation genuinely ran out of options. */
    private void moveVia(Mob golem, Location target, Navigation.Arrival mode, Runnable setIdle, ReachCallback onReach) {
        moveVia(golem, target, mode,
                () -> { clearSearchCooldown(golem); setIdle.run(); },
                loc -> { clearTarget(golem); setLastProblem(golem, "stuck"); delayNextSearch(golem); setIdle.run(); },
                onReach);
    }

    private void moveViaHauler(Mob golem, ReachCallback onReach) {
        moveVia(golem, getTarget(golem), Navigation.Arrival.BLOCK,
                () -> {
                    // Preserves the hauler's own quirk: on a vanished target it resumes SEARCHING for
                    // whatever it was moving toward (SEEKING_BUCKET/CAULDRON/LAVA_CHEST), rather than
                    // collapsing every sub-job to one shared idle state like the other roles do.
                    String currentState = golem.getPersistentDataContainer()
                            .getOrDefault(stateKey, PersistentDataType.STRING, "SEEKING_BUCKET");
                    if (currentState.startsWith("MOVING_TO_")) {
                        clearSearchCooldown(golem);
                        setState(golem, "SEEKING_" + currentState.substring("MOVING_TO_".length()));
                    }
                },
                loc -> {
                    clearTarget(golem);
                    setLastProblem(golem, "stuck");
                    delayNextSearch(golem);
                    setState(golem, "SEEKING_BUCKET");
                },
                onReach);
    }

    /** A standable location at/adjacent to the target block (fallback: just above the block). */
    private Location safeSpotNear(Location target) {
        World w = target.getWorld();
        int bx = target.getBlockX(), by = target.getBlockY(), bz = target.getBlockZ();
        int[][] off = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy = 0; dy >= -1; dy--) {
            for (int[] o : off) {
                int x = bx + o[0], y = by + dy, z = bz + o[1];
                Block feet = w.getBlockAt(x, y, z);
                Block head = w.getBlockAt(x, y + 1, z);
                Block below = w.getBlockAt(x, y - 1, z);
                if (feet.isPassable() && head.isPassable() && below.getType().isSolid()) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return new Location(w, bx + 0.5, by + 1, bz + 0.5);
    }

    // ===== COURIER SEARCH & STATE HELPERS =====

    /** Cached one-pass scan of nearby storage containers, refreshed ~every 10s while the courier
     *  stays put. Cached handles are re-validated live on use. [Waypoint] signs are no longer
     *  collected here — Navigation does its own scan for them, only when it actually needs one (cost
     *  bias during a search, or the waypoint-hop fallback), which is far rarer than every decision. */
    private ContainerCache courierScan(Mob golem, Location origin, int r) {
        long now = Bukkit.getCurrentTick();
        ContainerCache c = courierContainerCache.get(golem.getUniqueId());
        if (c != null && now < c.expiry
                && Math.abs(c.cx - origin.getBlockX()) <= 4 && Math.abs(c.cz - origin.getBlockZ()) <= 4) {
            return c;
        }
        ContainerCache nc = new ContainerCache();
        nc.expiry = now + 200; // ~10s at 20 TPS
        nc.cx = origin.getBlockX();
        nc.cz = origin.getBlockZ();
        nc.list = new ArrayList<>();
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (isStorageMaterial(b.getType())) nc.list.add(b);
                }
            }
        }
        courierContainerCache.put(golem.getUniqueId(), nc);
        return nc;
    }

    /** Diagnostic for the courier GUI: why (or whether) a route can currently run. A route the golem
     *  most recently gave up on (see moveViaCourier's onStuck branch) reports that reason directly --
     *  ahead of the usual structural checks, since "the ladder gave up" is a stronger, more specific
     *  signal than "no source container in range" even if both happen to be true right now. */
    public String courierRouteStatus(Mob golem, CourierRoute route) {
        if (route == null || !route.isConfigured()) return "UNCONFIGURED";
        String problem = lastProblem(golem);
        if (problem != null && sameRoute(route, activeCourierRoute(golem))) {
            return "STUCK:" + problem;
        }
        Location origin = golem.getLocation();
        int r = plugin.cfg.courierSearchRadius;
        List<Block> containers = collectStorageContainers(origin, r);

        boolean anySourceTag = false, anyDestTag = false;
        for (Block b : containers) {
            if (!anySourceTag && containerHasTag(b, route.source)) anySourceTag = true;
            if (!anyDestTag && containerHasTag(b, route.dest)) anyDestTag = true;
        }
        if (!anySourceTag) return "NO_SOURCE_TAG";
        Block src = nearestSource(origin, containers, route);
        if (src == null) return "SOURCE_EMPTY";
        if (!anyDestTag) return "NO_DEST_TAG";
        Block dst = nearestDest(origin, containers, route.dest);
        if (dst == null) return "DEST_FULL";
        // Matches courierDecide's self-churn guard: same container can't be both ends.
        if (src.getLocation().equals(dst.getLocation())) return "SAME_CONTAINER";
        return "OK";
    }

    /** The courier's current activity (state + what it carries), for the GUI status lore. */
    public String courierActivity(Mob golem) {
        String state = golem.getPersistentDataContainer()
                .getOrDefault(stateKey, PersistentDataType.STRING, "COURIER_IDLE");
        ItemStack hand = golem.getEquipment().getItemInMainHand();
        boolean carrying = hand != null && hand.getType() != Material.AIR;
        return state
                + (carrying ? " (" + hand.getAmount() + "x " + hand.getType().name().toLowerCase() + ")" : "");
    }

    private List<Block> collectStorageContainers(Location origin, int r) {
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        List<Block> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) { // couriers search the full box in every direction
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (isStorageMaterial(b.getType())) out.add(b);
                }
            }
        }
        return out;
    }

    private Block nearestSource(Location origin, List<Block> containers, CourierRoute route) {
        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (Block b : containers) {
            if (!containerHasTag(b, route.source)) continue;
            if (!(b.getState() instanceof Container c)) continue;
            boolean hasCarryable = false;
            for (ItemStack s : c.getInventory().getContents()) {
                if (s != null && s.getType() != Material.AIR && route.carries(s.getType())) { hasCarryable = true; break; }
            }
            if (!hasCarryable) continue;
            double d = b.getLocation().distanceSquared(origin);
            if (d < bestDist) { bestDist = d; best = b; }
        }
        return best;
    }

    private Block nearestDest(Location origin, List<Block> containers, String tag) {
        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (Block b : containers) {
            if (!containerHasTag(b, tag)) continue;
            if (!(b.getState() instanceof Container c) || isContainerFull(c)) continue;
            double d = b.getLocation().distanceSquared(origin);
            if (d < bestDist) { bestDist = d; best = b; }
        }
        return best;
    }

    private Block findCourierDest(Location origin, String tag, int r) {
        return scanCourierContainer(origin, r, b ->
                containerHasTag(b, tag)
                        && b.getState() instanceof Container c && !isContainerFull(c));
    }

    private Block scanCourierContainer(Location origin, int r, java.util.function.Predicate<Block> ok) {
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (!isStorageMaterial(b.getType())) continue;
                    if (!ok.test(b)) continue;
                    double d = b.getLocation().distanceSquared(origin);
                    if (d < bestDist) { bestDist = d; best = b; }
                }
            }
        }
        return best;
    }

    private List<CourierRoute> getCourierRoutes(Mob golem) {
        return CourierRoute.parse(
                golem.getPersistentDataContainer().get(plugin.courierRoutesKey, PersistentDataType.STRING));
    }

    private CourierRoute activeCourierRoute(Mob golem) {
        int idx = golem.getPersistentDataContainer().getOrDefault(courierActiveKey, PersistentDataType.INTEGER, -1);
        List<CourierRoute> routes = getCourierRoutes(golem);
        if (idx < 0 || idx >= routes.size()) return null;
        return routes.get(idx);
    }

    private void setCourierState(Mob golem, String state) {
        golem.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, state);
    }

    private void setCourierDest(Mob golem, Location loc) {
        String s = loc.getWorld().getName() + "," + loc.getBlockX() + ","
                + loc.getBlockY() + "," + loc.getBlockZ();
        golem.getPersistentDataContainer().set(courierDestKey, PersistentDataType.STRING, s);
    }

    private Location getCourierDest(Mob golem) {
        return parseLoc(golem.getPersistentDataContainer().get(courierDestKey, PersistentDataType.STRING));
    }

    private void clearCourierJob(Mob golem) {
        golem.getPersistentDataContainer().remove(courierActiveKey);
        golem.getPersistentDataContainer().remove(courierDestKey);
    }

    private void abortCourier(Mob golem) {
        clearCourierJob(golem);
        setCourierState(golem, "COURIER_IDLE");
    }

    // ===== SMELTER BLOCK SEARCH =====

    /** Result of a single station scan: all furnaces plus the nearest chest per sign type. */
    private static final class StationScan {
        final List<Block> furnaces = new ArrayList<>();
        Block smeltChest, fuelChest, outputChest;
        double smeltDist = Double.MAX_VALUE, fuelDist = Double.MAX_VALUE, outputDist = Double.MAX_VALUE;
    }

    /**
     * Scans the search cube around {@code origin} ONCE, collecting every furnace and the
     * nearest [Smelt]/[Fuel]/[Output] chest — replacing four separate cube passes.
     */
    private StationScan scanStation(Mob golem, Location origin) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;

        // Resolve THIS golem's tags once: they may be its own overrides rather than the config's.
        String smeltTag  = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.SMELT);
        String fuelTag   = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.FUEL);
        String outputTag = plugin.tagFor(golem, LavaGolemPlugin.GolemTag.OUTPUT);

        StationScan scan = new StationScan();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material t = b.getType();
                    if (isFurnaceMaterial(t)) {
                        scan.furnaces.add(b);
                        continue;
                    }
                    if (!isStorageMaterial(t)) continue;

                    double d = b.getLocation().distanceSquared(origin);
                    if (containerHasTag(b, smeltTag)) {
                        if (d < scan.smeltDist)  { scan.smeltDist = d;  scan.smeltChest = b; }
                    } else if (containerHasTag(b, fuelTag)) {
                        if (d < scan.fuelDist)   { scan.fuelDist = d;   scan.fuelChest = b; }
                    } else if (containerHasTag(b, outputTag)) {
                        if (d < scan.outputDist) { scan.outputDist = d; scan.outputChest = b; }
                    }
                }
            }
        }
        return scan;
    }

    /** Storage containers we treat as taggable: chests, barrels and shulker boxes (not furnaces/hoppers). */
    private static final BlockFace[] SIGN_FACES = {
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};

    private boolean isStorageMaterial(Material m) {
        return m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL
                || Tag.SHULKER_BOXES.isTagged(m);
    }

    /** Furnace-family blocks the smelter services: regular furnace, blast furnace, smoker. */
    private boolean isFurnaceMaterial(Material m) {
        return m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER;
    }

    /** Whether a furnace of the given block type can smelt {@code item} (routes ores→blast, food→smoker). */
    private boolean furnaceAccepts(Material furnaceType, Material item) {
        return switch (furnaceType) {
            case BLAST_FURNACE -> plugin.blastableInputs.contains(item);
            case SMOKER -> plugin.smokableInputs.contains(item);
            default -> plugin.smeltableInputs.contains(item); // FURNACE
        };
    }

    /** True if the container is tagged with {@code tag} — by its custom name (anvil) or an adjacent sign. */
    private boolean containerHasTag(Block b, String tag) {
        if (!isStorageMaterial(b.getType())) return false;
        if (b.getState() instanceof Container container) {
            var name = container.customName();
            if (name != null
                    && PlainTextComponentSerializer.plainText().serialize(name).equalsIgnoreCase(tag)) {
                return true;
            }
            for (BlockFace face : SIGN_FACES) {
                if (b.getRelative(face).getState() instanceof Sign sign && signMatches(sign, tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns which smelter tag (smelt/fuel/output) marks this container — by name or sign — or null. */
    private boolean isContainerFull(Container container) {
        Inventory inv = container.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) return false;
            if (stack.getAmount() < stack.getMaxStackSize()) return false;
        }
        return true;
    }

    // ===== SMELTER STATE HELPERS =====

    private void setSmelterState(Mob golem, String state) {
        golem.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, state);
    }

    private void setJobFurnace(Mob golem, Location loc) {
        String s = loc.getWorld().getName() + "," + loc.getBlockX() + ","
                + loc.getBlockY() + "," + loc.getBlockZ();
        golem.getPersistentDataContainer().set(jobFurnaceKey, PersistentDataType.STRING, s);
    }

    private Location getJobFurnace(Mob golem) {
        return parseLoc(golem.getPersistentDataContainer().get(jobFurnaceKey, PersistentDataType.STRING));
    }

    private void clearJobFurnace(Mob golem) {
        golem.getPersistentDataContainer().remove(jobFurnaceKey);
        golem.getPersistentDataContainer().remove(jobAmountKey);
        golem.getPersistentDataContainer().remove(jobMaterialKey);
    }

    private void setJobAmount(Mob golem, int amount) {
        golem.getPersistentDataContainer().set(jobAmountKey, PersistentDataType.INTEGER, amount);
    }

    private int getJobAmount(Mob golem) {
        return golem.getPersistentDataContainer()
                .getOrDefault(jobAmountKey, PersistentDataType.INTEGER, 1);
    }

    private void setJobMaterial(Mob golem, Material material) {
        golem.getPersistentDataContainer().set(jobMaterialKey, PersistentDataType.STRING, material.name());
    }

    private Material getJobMaterial(Mob golem) {
        String s = golem.getPersistentDataContainer().get(jobMaterialKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return Material.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ===== TARGET SEARCH =====

    private void seekBucket(Mob golem) {
        Material inHand = golem.getEquipment().getItemInMainHand().getType();
        if (inHand == Material.BUCKET) {
            setState(golem, "SEEKING_CAULDRON");
            return;
        }
        if (inHand == Material.LAVA_BUCKET) {
            setState(golem, "SEEKING_LAVA_CHEST");
            return;
        }
        if (!canSearch(golem)) return;
        Block target = findTaggedContainer(golem.getLocation(),
                plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BUCKETS), true);
        if (target == null) {
            setLastProblem(golem, "no " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.BUCKETS)
                    + " container with a bucket in range");
            delayNextSearch(golem);
            return;
        }
        clearLastProblem(golem);
        clearSearchCooldown(golem);
        setTarget(golem, target.getLocation());
        setState(golem, "MOVING_TO_BUCKET");
    }

    private void seekCauldron(Mob golem) {
        if (!canSearch(golem)) return;
        Block target = findLavaCauldron(golem.getLocation());
        if (target == null) {
            setLastProblem(golem, "no lava cauldron in range");
            delayNextSearch(golem);
            return;
        }
        clearLastProblem(golem);
        clearSearchCooldown(golem);
        setTarget(golem, target.getLocation());
        setState(golem, "MOVING_TO_CAULDRON");
    }

    private void seekLavaChest(Mob golem) {
        if (!canSearch(golem)) return;
        Block target = findTaggedContainer(golem.getLocation(),
                plugin.tagFor(golem, LavaGolemPlugin.GolemTag.LAVA), false);
        if (target == null) {
            setLastProblem(golem, "no " + plugin.tagFor(golem, LavaGolemPlugin.GolemTag.LAVA) + " container in range");
            delayNextSearch(golem);
            return;
        }
        clearLastProblem(golem);
        clearSearchCooldown(golem);
        setTarget(golem, target.getLocation());
        setState(golem, "MOVING_TO_LAVA_CHEST");
    }

    // ===== ON REACH =====

    private void onReachBucketChest(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) {
            clearSearchCooldown(golem);
            setState(golem, "SEEKING_BUCKET");
            return;
        }
        Inventory inv = chest.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getType() == Material.BUCKET) {
                stack.setAmount(stack.getAmount() - 1);
                if (stack.getAmount() <= 0) inv.setItem(i, null);
                else inv.setItem(i, stack);
                golem.getEquipment().setItemInMainHand(new ItemStack(Material.BUCKET));
                // Update stats
                int taken = golem.getPersistentDataContainer()
                        .getOrDefault(plugin.bucketsTakenKey, PersistentDataType.INTEGER, 0);
                golem.getPersistentDataContainer()
                        .set(plugin.bucketsTakenKey, PersistentDataType.INTEGER, taken + 1);
                setState(golem, "SEEKING_CAULDRON");
                return;
            }
        }
        clearSearchCooldown(golem);
        setState(golem, "SEEKING_BUCKET");
    }

    private void onReachCauldron(Mob golem, Block block) {
        if (block.getType() != Material.LAVA_CAULDRON) {
            clearSearchCooldown(golem);
            setState(golem, "SEEKING_CAULDRON");
            return;
        }
        block.setType(Material.CAULDRON);
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.LAVA_BUCKET));
        setState(golem, "SEEKING_LAVA_CHEST");
    }

    private void onReachLavaChest(Mob golem, Block block) {
        if (!(block.getState() instanceof Container chest)) {
            clearSearchCooldown(golem);
            setState(golem, "SEEKING_LAVA_CHEST");
            return;
        }
        ItemStack lavaBucket = new ItemStack(Material.LAVA_BUCKET);
        var leftover = chest.getInventory().addItem(lavaBucket);
        if (!leftover.isEmpty()) {
            // Chest is full — retry later
            return;
        }
        // Update stats on successful delivery
        int delivered = golem.getPersistentDataContainer()
                .getOrDefault(plugin.lavaDeliveredKey, PersistentDataType.INTEGER, 0);
        golem.getPersistentDataContainer()
                .set(plugin.lavaDeliveredKey, PersistentDataType.INTEGER, delivered + 1);
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));
        setState(golem, "SEEKING_BUCKET");
    }

    // ===== MOVEMENT =====
    // Every role's actual walking goes through moveVia and Navigation now (see the "NAVIGATION v2"
    // section above) — moveViaHauler/moveViaSmelter/moveViaAlchemist/moveViaFisherShore/moveViaCourier
    // are this role's thin wrapper around it.

    // ===== BLOCK SEARCH =====

    private Block findTaggedContainer(Location origin, String tag, boolean needsBucketInside) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;

        Block best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (!isStorageMaterial(b.getType())) continue;
                    if (!containerHasTag(b, tag)) continue;

                    if (needsBucketInside) {
                        if (!(b.getState() instanceof Container container)) continue;
                        if (!container.getInventory().contains(Material.BUCKET)) continue;
                    }

                    double d = b.getLocation().distanceSquared(origin);
                    if (d < bestDist) {
                        bestDist = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    private boolean signMatches(Sign sign, String needle) {
        // A sign hung with its back to the container (or simply written on that side) is just as
        // valid a tag as the front — nothing about placing a sign guarantees which face ends up
        // pointed at the block it's tagging.
        return sideMatches(sign, org.bukkit.block.sign.Side.FRONT, needle)
                || sideMatches(sign, org.bukkit.block.sign.Side.BACK, needle);
    }

    private boolean sideMatches(Sign sign, org.bukkit.block.sign.Side side, String needle) {
        for (net.kyori.adventure.text.Component line : sign.getSide(side).lines()) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line);
            if (plain.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    private Block findLavaCauldron(Location origin) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;

        Block best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (b.getType() != Material.LAVA_CAULDRON) continue;
                    double d = b.getLocation().distanceSquared(origin);
                    if (d < bestDist) {
                        bestDist = d;
                        best = b;
                    }
                }
            }
        }
        return best;
    }

    // ===== STATE HELPERS =====

    private void setState(Mob golem, String state) {
        golem.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, state);
    }

    private boolean canSearch(Mob golem) {
        long now = Bukkit.getCurrentTick();
        long next = golem.getPersistentDataContainer()
                .getOrDefault(nextSearchTickKey, PersistentDataType.LONG, 0L);
        // getCurrentTick() counts from server start and resets to 0 on restart, but nextSearchTickKey
        // persists in the golem's PDC. A value left over from a previous session sits far in the
        // future and would freeze the golem for hours (it "stands ready but never moves" until the
        // counter climbs back). If next is more than a full cooldown ahead, the counter reset — the
        // cooldown is stale, so treat it as elapsed.
        if (next > now + plugin.cfg.searchCooldownTicks) return true;
        return now >= next;
    }

    private void delayNextSearch(Mob golem) {
        golem.getPersistentDataContainer().set(
                nextSearchTickKey, PersistentDataType.LONG,
                (long) (Bukkit.getCurrentTick() + plugin.cfg.searchCooldownTicks));
    }

    private void clearSearchCooldown(Mob golem) {
        golem.getPersistentDataContainer().remove(nextSearchTickKey);
    }

    private void setTarget(Mob golem, Location loc) {
        String s = loc.getWorld().getName() + "," + loc.getBlockX() + ","
                + loc.getBlockY() + "," + loc.getBlockZ();
        golem.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, s);
        // Progress is measured against the PREVIOUS target's distance, so a job that is re-aimed
        // without arriving or giving up (the carried-item safety nets do exactly this) would keep
        // counting stalls against a stale number and could declare a perfectly mobile golem stuck.
        // Every reassignment funnels through here, so clearing it here covers all of them.
        stuckProgress.remove(golem.getUniqueId());
    }

    private Location getTarget(Mob golem) {
        return parseLoc(golem.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING));
    }

    /** Parses the "world,x,y,z" format every PDC-stored location in this class uses, into the block's
     *  centre. Returns null for anything that doesn't check out — a null string, a mangled split, an
     *  unloaded/renamed world, or (NumberFormatException) a coordinate that isn't actually a number —
     *  since a hand-edited or corrupted PDC value is already a documented "nothing found" case in
     *  every caller, and crashing the golem's tick over it would be worse than just losing the target. */
    private Location parseLoc(String s) {
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        try {
            return new Location(w,
                    Integer.parseInt(parts[1]) + 0.5,
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]) + 0.5);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void clearTarget(Mob golem) {
        golem.getPersistentDataContainer().remove(targetKey);
    }

    @FunctionalInterface
    private interface ReachCallback {
        void onReach(Mob golem, Block block);
    }
}
