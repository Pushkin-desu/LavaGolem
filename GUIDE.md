# LavaGolem — Full Guide

Everything about setting the golems up, and what to do when a courier won't move.
[README](README.md) · [Русская версия](GUIDE_RU.md)

---

## Table of contents

- [Common rules](#common-rules)
- [🪣 Lava Golem](#-lava-golem)
- [🔥 Smelter Golem](#-smelter-golem)
- [📦 Courier Golem](#-courier-golem)
- [How the courier finds its way](#how-the-courier-finds-its-way)
- [Troubleshooting: the courier won't move](#troubleshooting-the-courier-wont-move)
- [Full config reference](#full-config-reference)

---

## Common rules

These apply to all three golems.

- **Spawning.** Craft the golem's Heart (recipes below), then right-click the ground with it. Only golems made from these recipes are controlled — vanilla copper golems are left alone.
- **Menu.** Right-click a golem with an **empty hand** to open its menu (stats, and settings where it has them).
- **Disassemble.** **Sneak + right-click** with an empty hand. The Heart drops back, along with whatever the golem was carrying.
- **Tagging a container.** Storage can be a **chest, trapped chest, barrel or shulker box**. Tag it either by:
  - putting a **sign** with the tag text on any of its 6 faces, **or**
  - **renaming the container itself** in an anvil to that text (no sign — cleaner build).
- **Range.** Golems look for their containers within `search-radius` (default 8 blocks) of where they currently stand. The courier is the exception — it has its own, larger `courier-search-radius`.
- **Restarts.** A golem's role, current job, work mode and courier routes are all saved and restored when its chunk loads.

---

## 🪣 Lava Golem

**Recipe center:** Lava Bucket · **Works with:** a lava cauldron + two tagged containers.

**What it does:** grabs an empty bucket, fills it at a *full* lava cauldron, deposits the lava bucket into storage, repeats.

**Setup:**
1. A `[Buckets]` container holding empty buckets.
2. A **cauldron that fills with lava** in range (e.g. pointed dripstone under a lava source).
3. A `[Lava]` container for the filled buckets.

**Menu:** stats only (lava delivered, buckets taken, active since).

---

## 🔥 Smelter Golem

**Recipe center:** Furnace · **Works with:** furnaces + three tagged containers.

**What it does:** feeds your furnaces and hauls the results out — no hoppers or rails needed.

- **Reads your stock** — takes the most abundant smeltable from `[Smelt]`.
- **Picks fuel that fits** — lava buckets for big batches, coal for medium, wood if that's all there is — sized from `[Fuel]` so nothing burns to waste.
- **Spreads the load** — splits a big pile evenly across all idle furnaces so they run in parallel.
- **Closes the lava loop** — pulls the empty bucket back out after a lava bucket burns and returns it to `[Output]`, so the furnace never clogs.
- **All three furnace types** — regular **furnaces**, **blast furnaces** and **smokers**, routed automatically: ore → blast furnace, food → smoker, everything else → furnace.

**Setup:**
1. One or more furnaces / blast furnaces / smokers in range.
2. A `[Smelt]` container with things to melt.
3. A `[Fuel]` container with any vanilla fuel.
4. An `[Output]` container for the results (spent buckets come back here too).

**Menu — work modes:**
- **Balanced** — loads *and* collects, loading first (the default).
- **Load only** — only feeds furnaces.
- **Collect only** — only hauls finished goods out.

Run one Smelter on **Load only** and another on **Collect only** if you want them split.

---

## 📦 Courier Golem

**Recipe center:** Hopper · **Works with:** any two containers you tag, plus optional `[Waypoint]` signs.

**What it does:** carries items between tagged containers along routes you set in its GUI. This is the golem that connects your other stations together.

**Setup:**
1. Tag the source and destination containers with **any tags you like** (`[Ore]`, `[Farm]`, `Cobble stash`, whatever).
2. Right-click the courier to open the **route editor**.
3. **Add a route**, then set its **From** and **To**:
   - **Left-click** the slot to cycle through tags the golem can see nearby.
   - **Right-click** the slot to **type a tag in chat** — for tags it can't currently see, or ones you just invented.
4. *(Optional)* build a **filter**: shift-click items from your inventory into the filter row, and pick the mode:
   - **Blacklist** (default) — carry everything *except* the listed items.
   - **Whitelist** — carry *only* the listed items.
5. Press **Save & close**.

**Reading the status:** the menu shows a live status for the selected route. If it isn't `Ready`, it tells you why:

| Status | Meaning |
|---|---|
| Ready | The route can run right now. |
| Set both From and To | The route is missing a tag. |
| No source container found nearby | Nothing with the From-tag is in range. |
| Source has nothing the filter allows | The source is empty, or the filter blocks everything in it. |
| No destination container found nearby | Nothing with the To-tag is in range. |
| Destination is full | The To-container has no room. |
| From and To resolve to the SAME container | Both tags point at one chest — pick different ones. |

The status line also shows **Now:** — what the golem is doing this moment (idle, heading to source/destination, and what it carries).

---

## How the courier finds its way

The courier walks on its legs using Minecraft's normal mob pathfinding. That pathfinder has a **budget** — it only searches so many blocks outward — so for a long or awkward route (especially *up*, where it first has to walk *away* from the goal to reach the stairs) it can fail to find a path and just beeline into a wall.

Two mechanisms handle that:

### Waypoints (the good way)

Place signs reading **`[Waypoint]`** (text configurable via `waypoint-sign-text`) along a walkable path — like breadcrumbs:

- one at the **bottom of the stairs**,
- one on **each landing / turn**,
- one **near the destination** at the top.

The courier then walks **marker to marker**. Each hop is a short, easy segment the pathfinder can solve, so it climbs stairs and rounds corners on foot.

How it chooses the next marker, precisely:

1. Can it path **directly** to its target right now? If yes, it goes straight there and ignores the markers.
2. If not, among the markers it hasn't passed yet this trip, it picks the one **closest to its target that it can actually reach**, and walks there.
3. On arrival it re-checks step 1, and repeats.

Because every courier judges markers against **its own** target, **one network of `[Waypoint]` signs serves any number of couriers going in any direction.** They're shared road signs, not per-golem assignments — you never name or assign them.

### Teleport (the fallback)

If a target genuinely can't be reached on foot — no usable markers, path blocked — the courier **teleports** next to it as a last resort, after `courier-stuck-ticks` of no progress. You can turn this off with `courier-teleport: false` for pure-walking (survival-purist) routes; then routes must be short, walkable, or marked with waypoints.

---

## Troubleshooting: the courier won't move

Work down this list — it's ordered by how common each cause is.

**1. Status isn't `Ready`.**
Open the menu and read the status line for that route (table above). A red status means the route *can't* run yet — fix the tags, fill the source, or make room in the destination. This is the most common cause of "green heart but standing still" **when it isn't actually green**.

**2. The containers are out of range.**
`courier-search-radius` is measured **from where the golem is standing now**, and the **same in every direction** — the cube must cover *both* ends of the route. Default is 24. If your source and destination are 40 blocks apart, no radius will see both — move the golem between them or shorten the route. Note the radius is **capped at 32** even if you set it higher (a 65³ cube is already ~275k blocks; the scan cost grows as radius³, so bigger would stall the server).

**3. It walks straight at the target and stops / teleports.**
The pathfinder can't route there (usually the target is up stairs behind it). **Add `[Waypoint]` signs** along the walkable path — bottom of the stairs, each landing, near the top. See the section above. This is *the* fix for vertical / behind-me routes.

**4. It reaches a waypoint but ignores the rest.**
Make sure each consecutive marker is a **short, directly walkable hop** from the previous one — a few blocks along an actual floor/staircase, not across a gap or through a wall. If two markers can't see each other on foot, the courier can't bridge them (it'll fall back to teleport).

**5. The sign text doesn't match.**
The waypoint text must equal `waypoint-sign-text` exactly (default `[Waypoint]`), on any face of the sign. A tag on a container must equal the tag you set in the route (or the container's anvil name), exactly.

**6. It never even starts.**
Check the route filter. A **whitelist** with no items carries **nothing**; an over-broad **blacklist** can exclude everything the source holds. The status will say *"Source has nothing the filter allows."*

**7. Nothing works and you want it to just go.**
Set `courier-teleport: true` (the default) so it blinks to the target when stuck. If it *is* true and still won't move, the cause is almost always #1 or #2 — the golem has no valid job to do.

---

## Full config reference

`plugins/LavaGolem/config.yml`:

```yaml
search-radius: 8            # Blocks a golem scans for containers/cauldrons/furnaces (from where it stands)
reach-distance: 2.2         # Distance at which a golem "arrives" at its target
search-cooldown-ticks: 40   # Ticks to wait before retrying a failed search (20 ticks = 1s)
tick-period: 10             # How often golem logic runs, in game ticks

# Tags — each works as a sign OR as the container's own anvil name
bucket-sign-text: "[Buckets]"   # Lava Golem: empty buckets
lava-sign-text:   "[Lava]"      # Lava Golem: filled lava buckets
smelt-sign-text:  "[Smelt]"     # Smelter: items to melt
fuel-sign-text:   "[Fuel]"      # Smelter: any vanilla fuel
output-sign-text: "[Output]"    # Smelter: finished goods (+ returned buckets)

# --- Courier only ---
courier-search-radius: 24    # Same in every direction; must cover BOTH ends of a route. Capped at 32 (cost ~ radius^3)
courier-carry-limit: 16      # Items carried per trip (1-64)
waypoint-sign-text: "[Waypoint]"  # Marker text for pathfinding waypoints
courier-teleport: true       # Last-resort blink to the target when it can't be walked to
courier-stuck-ticks: 20      # Logic ticks of no progress before that blink

locale: en                  # en or ru
bstats: true                # Anonymous usage statistics (bstats.org)
```

**Reloading:** change the file, then restart the server (or reload the plugin) for changes to take effect.
