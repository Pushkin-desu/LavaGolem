package io.github.pushkindesu.lavagolem;

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
import org.bukkit.entity.Entity;
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
    private final NamespacedKey courierFinalKey;
    private int tickCount = 0;

    /** Transient per-golem "stuck" progress tracking for the courier's teleport fallback. */
    private final Map<java.util.UUID, double[]> courierStuck = new HashMap<>(); // uuid -> {stallTicks, lastDist}

    /** Short-lived cache of nearby storage containers per courier, so the expensive radius cube
     *  scan runs at most every ~10s (per stationary golem) instead of on every decision. */
    private final Map<java.util.UUID, ContainerCache> courierContainerCache = new HashMap<>();
    private static final class ContainerCache {
        long expiry; int cx, cz; List<Block> list; List<Location> waypoints;
    }

    /** Transient per-courier navigation state: which waypoints it has already passed this leg. */
    private final Map<java.util.UUID, java.util.Set<Long>> courierVisited = new HashMap<>();

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
        this.courierFinalKey   = new NamespacedKey(plugin, "courier_final");
    }

    @Override
    public void run() {
        tickCount++;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(Mob.class)) {
                Mob golem = (Mob) entity;
                if (golem.getPersistentDataContainer()
                        .get(plugin.golemEntityKey, PersistentDataType.BYTE) == null) continue;

                // Reset weathering state every ~5 seconds
                if (tickCount % 10 == 0 && golem instanceof CopperGolem cg) {
                    cg.setWeatheringState(WeatheringCopperState.UNAFFECTED);
                }

                tickGolem(golem);
            }
        }
    }

    private void tickGolem(Mob golem) {
        // Hold a golem still, whatever its role, while a player has its menu open — so it doesn't
        // walk away, and a courier never starts on a half-configured route (movement is halted
        // once, on open, in markMenuOpen). It resumes on the next tick after the menu closes.
        if (plugin.isMenuOpen(golem.getUniqueId())) return;

        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        String role = pdc.getOrDefault(plugin.roleKey, PersistentDataType.STRING,
                LavaGolemPlugin.ROLE_HAULER);

        if (LavaGolemPlugin.ROLE_SMELTER.equals(role)) {
            tickSmelter(golem, pdc);
        } else if (LavaGolemPlugin.ROLE_COURIER.equals(role)) {
            tickCourier(golem, pdc);
        } else {
            tickHauler(golem, pdc);
        }
    }

    // ===== LAVA HAULER (unchanged behavior) =====

    private void tickHauler(Mob golem, PersistentDataContainer pdc) {
        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "SEEKING_BUCKET");

        switch (state) {
            case "SEEKING_BUCKET"       -> seekBucket(golem);
            case "MOVING_TO_BUCKET"     -> moveToTarget(golem, this::onReachBucketChest);
            case "SEEKING_CAULDRON"     -> seekCauldron(golem);
            case "MOVING_TO_CAULDRON"   -> moveToTarget(golem, this::onReachCauldron);
            case "SEEKING_LAVA_CHEST"   -> seekLavaChest(golem);
            case "MOVING_TO_LAVA_CHEST" -> moveToTarget(golem, this::onReachLavaChest);
        }
    }

    // ===== SMELTER =====

    private void tickSmelter(Mob golem, PersistentDataContainer pdc) {
        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "SMELTER_IDLE");

        switch (state) {
            case "SMELTER_IDLE"              -> smelterDecide(golem);
            case "SMELTER_COLLECT_FURNACE"   -> moveToTargetSmelter(golem, this::onReachCollectFurnace);
            case "SMELTER_TO_OUTPUT"         -> moveToTargetSmelter(golem, this::onReachOutputChest);
            case "SMELTER_RETRIEVE_FURNACE"  -> moveToTargetSmelter(golem, this::onReachRetrieveFurnace);
            case "SMELTER_RETURN_BUCKET"     -> moveToTargetSmelter(golem, this::onReachReturnBucket);
            case "SMELTER_FUEL_CHEST"        -> moveToTargetSmelter(golem, this::onReachFuelChest);
            case "SMELTER_FUEL_FURNACE"      -> moveToTargetSmelter(golem, this::onReachFuelFurnace);
            case "SMELTER_INPUT_CHEST"       -> moveToTargetSmelter(golem, this::onReachInputChest);
            case "SMELTER_INPUT_FURNACE"     -> moveToTargetSmelter(golem, this::onReachInputFurnace);
            default -> setSmelterState(golem, "SMELTER_IDLE");
        }
    }

    /** Core decision engine: picks the single best action for an idle smelter golem. */
    private void smelterDecide(Mob golem) {
        if (!canSearch(golem)) return;
        Location origin = golem.getLocation();

        // Safety net: if we're still carrying an item (a delivery hit a full chest, a target
        // slot was occupied, or a station lacks an [Output]), deliver it BEFORE starting any new
        // job — otherwise the next job's setItemInMainHand would overwrite and destroy it.
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (held != null && held.getType() != Material.AIR) {
            Block dest = findTaggedContainer(origin, plugin.cfg.outputSignText, false);
            if (dest == null) dest = findTaggedContainer(origin, plugin.cfg.bucketSignText, false);
            if (dest != null) {
                clearSearchCooldown(golem);
                setTarget(golem, dest.getLocation());
                setSmelterState(golem, "SMELTER_TO_OUTPUT");
            } else {
                delayNextSearch(golem); // nowhere to put it yet — hold and wait
            }
            return;
        }

        StationScan scan = scanStation(origin);
        List<Block> furnaces = scan.furnaces;
        if (furnaces.isEmpty()) { delayNextSearch(golem); return; }

        Block outputChest = scan.outputChest;
        Block smeltChest = scan.smeltChest;
        Block fuelChest = scan.fuelChest;

        // Work mode (set via the golem's GUI): gates which task groups this golem performs.
        String mode = golem.getPersistentDataContainer()
                .getOrDefault(plugin.modeKey, PersistentDataType.STRING, LavaGolemPlugin.MODE_BALANCED);
        boolean canLoad = !LavaGolemPlugin.MODE_COLLECT_ONLY.equals(mode);
        boolean canCollect = !LavaGolemPlugin.MODE_LOAD_ONLY.equals(mode);

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
                clearSearchCooldown(golem);
                setJobFurnace(golem, bestRetrieve.getLocation());
                setTarget(golem, bestRetrieve.getLocation());
                setSmelterState(golem, "SMELTER_RETRIEVE_FURNACE");
                return;
            }
        }

        // 2) INPUT — furnace with empty input slot, [Smelt] has a smeltable material
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
                        if (n <= 0) { delayNextSearch(golem); return; }
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
                        if (chosenFuel != null) {
                            clearSearchCooldown(golem);
                            setJobFurnace(golem, bestFurnace.getLocation());
                            setJobAmount(golem, n);
                            setJobMaterial(golem, bestMaterial);
                            setTarget(golem, smeltChest.getLocation());
                            setSmelterState(golem, "SMELTER_INPUT_CHEST");
                            return;
                        }
                    }
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
            if (mat == Material.LAVA_BUCKET) continue; // only chosen above when a >= 32
            int cap = fuelCapacity(mat);
            int waste = cap * (int) Math.ceil(target / (double) cap) - target;
            if (waste < bestWaste || (waste == bestWaste && cap > bestCap)) {
                bestWaste = waste;
                bestCap = cap;
                best = mat;
            }
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
        Block outputChest = findTaggedContainer(golem.getLocation(), plugin.cfg.outputSignText, false);
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
        Block dest = findTaggedContainer(golem.getLocation(), plugin.cfg.outputSignText, false);
        if (dest == null) {
            dest = findTaggedContainer(golem.getLocation(), plugin.cfg.bucketSignText, false);
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

    private void moveToTargetSmelter(Mob golem, ReachCallback onReach) {
        Location target = getTarget(golem);
        if (target == null) {
            clearSearchCooldown(golem);
            setSmelterState(golem, "SMELTER_IDLE");
            return;
        }
        double dist = golem.getLocation().distance(target);
        if (dist <= plugin.cfg.reachDistance) {
            Block block = target.getBlock();
            clearTarget(golem);
            onReach.onReach(golem, block);
        } else {
            golem.getPathfinder().moveTo(target, 1.0);
        }
    }

    // ===== COURIER =====

    private void tickCourier(Mob golem, PersistentDataContainer pdc) {
        ensureCourierNav(golem);
        String state = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "COURIER_IDLE");
        switch (state) {
            case "COURIER_IDLE"      -> courierDecide(golem);
            case "COURIER_TO_SOURCE" -> moveToTargetCourier(golem, this::onReachCourierSource);
            case "COURIER_TO_DEST"   -> moveToTargetCourier(golem, this::onReachCourierDest);
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
            golem.getPersistentDataContainer().set(courierActiveKey, PersistentDataType.INTEGER, idx);
            golem.getPersistentDataContainer().set(courierRrKey, PersistentDataType.INTEGER, (idx + 1) % routes.size());
            setCourierDest(golem, dest.getLocation());
            startCourierMove(golem, source.getLocation());
            setCourierState(golem, "COURIER_TO_SOURCE");
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

    // ===== COURIER MOVEMENT (with teleport fallback) =====

    /** Begins a movement leg toward {@code finalLoc} (a container), routing via waypoints if needed. */
    private void startCourierMove(Mob golem, Location finalLoc) {
        setCourierFinal(golem, finalLoc);
        courierVisited.remove(golem.getUniqueId());
        courierStuck.remove(golem.getUniqueId());
        setTarget(golem, courierHop(golem, finalLoc));
    }

    private void moveToTargetCourier(Mob golem, ReachCallback onReach) {
        Location hop = getTarget(golem);
        Location fin = getCourierFinal(golem);
        if (fin == null) fin = hop;
        if (hop == null || fin == null) {
            courierStuck.remove(golem.getUniqueId());
            courierVisited.remove(golem.getUniqueId());
            setCourierState(golem, "COURIER_IDLE");
            return;
        }

        // Arrived at the final container? Measure to the block's CENTER, not its corner: a container
        // is a solid block the golem can't stand on, and when another container blocks the near face
        // the golem stops one cell back. Corner-distance then reads ~0.5 over the real gap and the
        // golem would stall and teleport while physically already next to the target.
        if (reachDist(golem.getLocation(), fin) <= plugin.cfg.reachDistance) {
            courierStuck.remove(golem.getUniqueId());
            courierVisited.remove(golem.getUniqueId());
            Block block = fin.getBlock();
            clearTarget(golem);
            onReach.onReach(golem, block);
            return;
        }

        // Reached the current waypoint hop → mark it passed and pick the next hop toward the final.
        if (reachDist(golem.getLocation(), hop) <= plugin.cfg.reachDistance) {
            markVisited(golem, hop);
            courierStuck.remove(golem.getUniqueId());
            setTarget(golem, courierHop(golem, fin));
            return;
        }

        // Walk to the current hop. Teleport (to the final) ONLY after genuinely making no headway
        // toward the hop for the full stuck window — never as a quick reaction to moveTo() briefly
        // returning false, which made it blink ~10 blocks early even on a clear straight line (the
        // target is the container's solid block, so pathing to it reports "false" intermittently).
        // It now walks the whole way and blinks only when truly stuck. st = {stallTicks, lastDist}.
        double[] st = courierStuck.computeIfAbsent(golem.getUniqueId(), u -> new double[]{0, Double.MAX_VALUE});
        double d = golem.getLocation().distance(hop);
        if (d < st[1] - 0.25) {        // got at least a quarter-block closer → still making progress
            st[0] = 0;
            st[1] = d;
        } else {
            st[0] += 1;                // no measurable progress this tick
        }
        if (plugin.cfg.courierTeleport && st[0] >= plugin.cfg.courierStuckTicks) {
            golem.teleport(safeSpotNear(fin));
            courierVisited.remove(golem.getUniqueId());
            setTarget(golem, fin);
            st[0] = 0;
            st[1] = Double.MAX_VALUE;
        } else {
            golem.getPathfinder().moveTo(hop, 1.0);
        }
    }

    /** Next hop toward {@code finalLoc}: the final itself if directly walkable, else the waypoint
     *  CLOSEST TO THE FINAL among those this golem can actually path to and hasn't passed yet.
     *  Waypoints are shared, anonymous road signs: every golem evaluates them against its own goal,
     *  so one network of markers serves any number of couriers and routes with no configuration. */
    private Location courierHop(Mob golem, Location finalLoc) {
        if (pathReaches(golem, finalLoc)) return finalLoc;
        List<Location> wps = courierScan(golem, golem.getLocation(), plugin.cfg.courierSearchRadius).waypoints;
        java.util.Set<Long> visited = courierVisited.get(golem.getUniqueId());
        List<Location> candidates = new ArrayList<>();
        for (Location w : wps) {
            if (visited != null && visited.contains(keyOf(w))) continue;
            candidates.add(w);
        }
        candidates.sort(java.util.Comparator.comparingDouble(w -> w.distanceSquared(finalLoc)));
        // Best-first: take the closest-to-goal marker we can reach. Each miss costs one pathfind,
        // so bound the tries; a hop is only picked on arrival, not every tick.
        int tries = Math.min(candidates.size(), HOP_CANDIDATE_LIMIT);
        for (int i = 0; i < tries; i++) {
            Location w = candidates.get(i);
            if (pathReaches(golem, w)) return w;
        }
        // No direct path and no usable marker: rather than beeline at an unreachable far block (which
        // just stalls and teleports), walk to the farthest point ALONG THE WAY we can actually path to.
        // This makes long walkable stretches (e.g. a straight corridor past the last stair marker) get
        // covered on foot without a marker on every span. Teleport only if even this finds nothing.
        Location stone = steppingStoneToward(golem, finalLoc);
        return stone != null ? stone : finalLoc;
    }

    private static final int HOP_CANDIDATE_LIMIT = 8;

    /** The farthest standable point on the straight line toward {@code target} that the mob can
     *  actually path to right now, or null if it can't even advance a few blocks that way. */
    private Location steppingStoneToward(Mob golem, Location target) {
        Location from = golem.getLocation();
        org.bukkit.util.Vector dir = target.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 3) return null;
        dir.multiply(1.0 / dist); // unit vector toward the target
        double max = Math.min(dist - 1, plugin.cfg.courierSearchRadius);
        // Probe far → near so the first reachable point is the biggest safe stride.
        for (double step = max; step >= 3; step -= 3) {
            Location probe = from.clone().add(dir.getX() * step, dir.getY() * step, dir.getZ() * step);
            Location spot = safeSpotNear(probe);
            if (spot != null && pathReaches(golem, spot)) return spot;
        }
        return null;
    }

    /** Whether the mob's navigation can build a path whose end actually reaches {@code loc}. */
    private boolean pathReaches(Mob golem, Location loc) {
        var pf = golem.getPathfinder();
        if (!pf.moveTo(loc, 1.0)) return false;
        var path = pf.getCurrentPath();
        if (path == null) return false;
        Location fp = path.getFinalPoint();
        return fp != null && fp.distanceSquared(loc) <= 6.25; // within ~2.5 blocks of the goal
    }

    private void markVisited(Mob golem, Location w) {
        courierVisited.computeIfAbsent(golem.getUniqueId(), u -> new java.util.HashSet<>()).add(keyOf(w));
    }

    private long keyOf(Location l) {
        return Block.getBlockKey(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private void setCourierFinal(Mob golem, Location loc) {
        String s = loc.getWorld().getName() + "," + loc.getBlockX() + ","
                + loc.getBlockY() + "," + loc.getBlockZ();
        golem.getPersistentDataContainer().set(courierFinalKey, PersistentDataType.STRING, s);
    }

    private Location getCourierFinal(Mob golem) {
        String s = golem.getPersistentDataContainer().get(courierFinalKey, PersistentDataType.STRING);
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
    }

    /** Distance from {@code from} to the CENTER of the block {@code blockLoc} points at. Normalises a
     *  block-corner target (x.0) to its centre (x.5) so "standing right next to a solid container"
     *  is measured honestly, whether the stored target was a corner or an already-centred waypoint. */
    private double reachDist(Location from, Location blockLoc) {
        double dx = from.getX() - (blockLoc.getBlockX() + 0.5);
        double dy = from.getY() - (blockLoc.getBlockY() + 0.5);
        double dz = from.getZ() - (blockLoc.getBlockZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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

    /** Cached one-pass scan of nearby storage containers AND waypoint signs, refreshed ~every 10s
     *  while the courier stays put. Cached handles are re-validated live on use. */
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
        nc.waypoints = new ArrayList<>();
        World world = origin.getWorld();
        int ox = origin.getBlockX(), oy = origin.getBlockY(), oz = origin.getBlockZ();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material t = b.getType();
                    if (isStorageMaterial(t)) {
                        nc.list.add(b);
                    } else if (Tag.ALL_SIGNS.isTagged(t)
                            && b.getState() instanceof Sign sign
                            && signMatches(sign, plugin.cfg.waypointSignText)) {
                        nc.waypoints.add(b.getLocation().add(0.5, 0, 0.5));
                    }
                }
            }
        }
        courierContainerCache.put(golem.getUniqueId(), nc);
        return nc;
    }

    /** Diagnostic for the courier GUI: why (or whether) a route can currently run. */
    public String courierRouteStatus(Mob golem, CourierRoute route) {
        if (route == null || !route.isConfigured()) return "UNCONFIGURED";
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
        String s = golem.getPersistentDataContainer().get(courierDestKey, PersistentDataType.STRING);
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
    }

    private void clearCourierJob(Mob golem) {
        golem.getPersistentDataContainer().remove(courierActiveKey);
        golem.getPersistentDataContainer().remove(courierDestKey);
        golem.getPersistentDataContainer().remove(courierFinalKey);
        courierVisited.remove(golem.getUniqueId());
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
    private StationScan scanStation(Location origin) {
        World world = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int r = plugin.cfg.searchRadius;

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

                    String matched = matchedContainerTag(b);
                    if (matched == null) continue;
                    double d = b.getLocation().distanceSquared(origin);
                    if (matched.equals(plugin.cfg.smeltSignText)) {
                        if (d < scan.smeltDist)  { scan.smeltDist = d;  scan.smeltChest = b; }
                    } else if (matched.equals(plugin.cfg.fuelSignText)) {
                        if (d < scan.fuelDist)   { scan.fuelDist = d;   scan.fuelChest = b; }
                    } else if (matched.equals(plugin.cfg.outputSignText)) {
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
    private String matchedContainerTag(Block b) {
        if (!isStorageMaterial(b.getType())) return null;
        if (b.getState() instanceof Container container) {
            var name = container.customName();
            if (name != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(name);
                if (plain.equalsIgnoreCase(plugin.cfg.smeltSignText))  return plugin.cfg.smeltSignText;
                if (plain.equalsIgnoreCase(plugin.cfg.fuelSignText))   return plugin.cfg.fuelSignText;
                if (plain.equalsIgnoreCase(plugin.cfg.outputSignText)) return plugin.cfg.outputSignText;
            }
            for (BlockFace face : SIGN_FACES) {
                if (!(b.getRelative(face).getState() instanceof Sign sign)) continue;
                if (signMatches(sign, plugin.cfg.smeltSignText))  return plugin.cfg.smeltSignText;
                if (signMatches(sign, plugin.cfg.fuelSignText))   return plugin.cfg.fuelSignText;
                if (signMatches(sign, plugin.cfg.outputSignText)) return plugin.cfg.outputSignText;
            }
        }
        return null;
    }

    private boolean isContainerFull(Container container) {
        Inventory inv = container.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) return false;
            if (inv.getItem(i).getAmount() < inv.getItem(i).getMaxStackSize()) return false;
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
        String s = golem.getPersistentDataContainer().get(jobFurnaceKey, PersistentDataType.STRING);
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        return new Location(w,
                Integer.parseInt(parts[1]) + 0.5,
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]) + 0.5);
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
                plugin.cfg.bucketSignText, true);
        if (target == null) { delayNextSearch(golem); return; }
        clearSearchCooldown(golem);
        setTarget(golem, target.getLocation());
        setState(golem, "MOVING_TO_BUCKET");
    }

    private void seekCauldron(Mob golem) {
        if (!canSearch(golem)) return;
        Block target = findLavaCauldron(golem.getLocation());
        if (target == null) { delayNextSearch(golem); return; }
        clearSearchCooldown(golem);
        setTarget(golem, target.getLocation());
        setState(golem, "MOVING_TO_CAULDRON");
    }

    private void seekLavaChest(Mob golem) {
        if (!canSearch(golem)) return;
        Block target = findTaggedContainer(golem.getLocation(),
                plugin.cfg.lavaSignText, false);
        if (target == null) { delayNextSearch(golem); return; }
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

    // ===== MOVEMENT (Pathfinder API) =====

    private void moveToTarget(Mob golem, ReachCallback onReach) {
        Location target = getTarget(golem);
        if (target == null) {
            String currentState = golem.getPersistentDataContainer()
                    .getOrDefault(stateKey, PersistentDataType.STRING, "SEEKING_BUCKET");
            if (currentState.startsWith("MOVING_TO_")) {
                clearSearchCooldown(golem);
                setState(golem, "SEEKING_" + currentState.substring("MOVING_TO_".length()));
            }
            return;
        }
        double dist = golem.getLocation().distance(target);
        if (dist <= plugin.cfg.reachDistance) {
            Block block = target.getBlock();
            clearTarget(golem);
            onReach.onReach(golem, block);
        } else {
            golem.getPathfinder().moveTo(target, 1.0);
        }
    }

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
        var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
        for (net.kyori.adventure.text.Component line : side.lines()) {
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
    }

    private Location getTarget(Mob golem) {
        String s = golem.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 4) return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null) return null;
        return new Location(w,
                Integer.parseInt(parts[1]) + 0.5,
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]) + 0.5);
    }

    private void clearTarget(Mob golem) {
        golem.getPersistentDataContainer().remove(targetKey);
    }

    @FunctionalInterface
    private interface ReachCallback {
        void onReach(Mob golem, Block block);
    }
}
