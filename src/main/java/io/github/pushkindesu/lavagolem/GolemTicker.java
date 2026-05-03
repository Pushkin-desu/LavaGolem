package io.github.pushkindesu.lavagolem;

import io.papermc.paper.world.WeatheringCopperState;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public class GolemTicker extends BukkitRunnable {

    private final LavaGolemPlugin plugin;
    private final NamespacedKey stateKey;
    private final NamespacedKey targetKey;
    private final NamespacedKey nextSearchTickKey;
    private int tickCount = 0;

    public GolemTicker(LavaGolemPlugin plugin) {
        this.plugin = plugin;
        this.stateKey          = new NamespacedKey(plugin, "state");
        this.targetKey         = new NamespacedKey(plugin, "target");
        this.nextSearchTickKey = new NamespacedKey(plugin, "next_search_tick");
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
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
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
        Block target = findChestWithSign(golem.getLocation(),
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
        Block target = findChestWithSign(golem.getLocation(),
                plugin.cfg.lavaSignText, false);
        if (target == null) { delayNextSearch(golem); return; }
        clearSearchCooldown(golem);
        setTarget(golem, target.getLocation());
        setState(golem, "MOVING_TO_LAVA_CHEST");
    }

    // ===== ON REACH =====

    private void onReachBucketChest(Mob golem, Block block) {
        if (!(block.getState() instanceof Chest chest)) {
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
        if (!(block.getState() instanceof Chest chest)) {
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

    private Block findChestWithSign(Location origin, String signText, boolean needsBucketInside) {
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
                    if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST)
                        continue;

                    // Check all 6 adjacent faces for a matching sign
                    boolean hasSign = false;
                    for (BlockFace face : new BlockFace[]{
                            BlockFace.UP, BlockFace.DOWN,
                            BlockFace.NORTH, BlockFace.SOUTH,
                            BlockFace.EAST, BlockFace.WEST}) {
                        if (b.getRelative(face).getState() instanceof Sign sign
                                && signMatches(sign, signText)) {
                            hasSign = true;
                            break;
                        }
                    }
                    if (!hasSign) continue;

                    if (needsBucketInside) {
                        if (!(b.getState() instanceof Chest chest)) continue;
                        if (!chest.getInventory().contains(Material.BUCKET)) continue;
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
        long next = golem.getPersistentDataContainer()
                .getOrDefault(nextSearchTickKey, PersistentDataType.LONG, 0L);
        return Bukkit.getCurrentTick() >= next;
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
