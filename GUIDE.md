# LavaGolem — Full Guide

Everything about setting the golems up, and what to do when a courier won't move.
[README](README.md) · [Русская версия](GUIDE_RU.md)

---

## Table of contents

- [Common rules](#common-rules)
- [🪣 Lava Golem](#-lava-golem)
- [🔥 Smelter Golem](#-smelter-golem)
- [⚗️ Alchemist Golem](#️-alchemist-golem)
- [📦 Courier Golem](#-courier-golem)
- [How the courier finds its way](#how-the-courier-finds-its-way)
- [Troubleshooting: the courier won't move](#troubleshooting-the-courier-wont-move)
- [Full config reference](#full-config-reference)

---

## Common rules

These apply to all three golems.

- **Telling them apart.** The **Lava Golem is on fire** (purely visual — it takes no damage) and the **Alchemist trails magic particles**. The Smelter and Courier are plain; the Courier is the one visibly carrying an item. No resource pack is involved.
- **Spawning.** Craft the golem's Heart (recipes below), then right-click the ground with it. Only golems made from these recipes are controlled — vanilla copper golems are left alone.
- **Menu.** Right-click a golem with an **empty hand** to open its menu (stats, and settings where it has them).
- **Switching one off.** Every menu has a **power button**: click it and the golem stops on the spot and stays parked until you switch it back on. Handy while you rebuild a station. The setting survives restarts.
- **Disassemble.** **Sneak + right-click** with an empty hand. The Heart drops back, along with whatever the golem was carrying.
- **Tagging a container.** Storage can be a **chest, trapped chest, barrel or shulker box**. Tag it either by:
  - putting a **sign** with the tag text on any of its 6 faces, **or**
  - **renaming the container itself** in an anvil to that text (no sign — cleaner build).
- **Each golem can use its own tags.** The tags in the config are just the **defaults**. Every station menu shows the containers that golem looks for: **right-click** one to type any tag you like, **left-click** to reset it to the default. So two breweries — or a brewery and a smeltery — can sit next to each other without fighting over one shared `[Output]`: give one of them `[Potions]` and you're done. A custom tag is marked in blue and survives restarts.
- **How a tag is matched.** The golem compares the tag against the container's **whole** name or sign line, ignoring case. So:
  - **The brackets are not syntax** — they're just how the default tags happen to be written. If your chest is named `Potions`, the tag is `Potions`. If it's named `[Potions]`, the tag is `[Potions]`, brackets included. Typing `output` will **not** match a chest named `[Output]`.
  - **Any language works** — `Кладовка`, `Tränke`, `倉庫` are all fine, on a sign, in an anvil and in the chat prompt. Case is ignored there too.
  - Watch out for a stray **trailing space** in a container's name: it's invisible in-game but makes the tag not match.
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

## ⚗️ Alchemist Golem

**Recipe center:** Brewing Stand · **Works with:** brewing stands + one `[Brew]` container, water, and an `[Output]` container.

**What it does:** runs your brewing stands — fills bottles at water, feeds them, adds ingredients stage by stage, and hauls the finished potions out. No hopper-and-dropper brewery needed.

**One chest holds everything.** You don't sort anything: put glass bottles, nether wart, blaze powder (or blaze rods) and your ingredients into a single `[Brew]` container, and the golem works out what each item is for.

**Setup:**
1. One or more **brewing stands** in range.
2. A `[Brew]` container with bottles, fuel and ingredients — all mixed together.
3. **Water** in range: a water source block, or a **water cauldron** (which dripstone or rain refills — the golem drains one level per bottle).
4. An `[Output]` container for the finished potions.
5. *(Optional)* a **crafting table**, if you'd rather stock blaze **rods** than powder. The golem grinds a rod (using the real vanilla recipe) and puts the powder back in `[Brew]`, where its fuel and Strength jobs pick it up — so rods work for both. Like everything else, the table must be **within `search-radius`** (8 blocks by default), or the rods are simply ignored.

**How it brews.** It works in stages, looking at what's already in the stand:

| Bottles in the stand | What the golem adds |
|---|---|
| Water bottle | Nether wart → awkward base |
| Awkward base | An effect ingredient (melon, sugar, magma cream, …) |
| A finished potion | Any modifier that fits it (redstone, glowstone, gunpowder, dragon's breath) |
| Anything else / nothing left to add | Nothing — it hauls them to `[Output]` |

**It brews for as long as the ingredients are in the chest**, and stops exactly where they run out. Want long Strength? Stock nether wart, blaze powder and redstone. Add gunpowder as well and you'll get a splash long Strength — it keeps applying modifiers while any of them still fit.

It only ever adds a modifier vanilla would really brew, worked out from the game's own potion list: redstone extends Strength (there is a long Strength) but is never added to Healing (there is no long Healing). So it can't jam a stand with an ingredient that would never brew.

It only ever starts a batch it can **finish**: the awkward base is a means to an end, so with no target potion available (or all of them switched off in the menu) the golem won't touch the water either. Nether wart alone in the chest brews nothing.

**It won't brew more than `[Output]` can take.** Potions don't stack, so each one needs a whole empty slot — and the golem counts them: two free slots buys two bottles, not a full batch of three. With the chest full it finishes whatever is already in a stand and then waits, rather than stranding potions it can't hand in. Clear the chest and it picks straight back up.

For the same reason an awkward base is **never carried to `[Output]`** — if you run out of the effect ingredient mid-batch, the awkward potions simply wait in the stand until you restock, instead of being filed away as if they were finished.

**Potions in `[Brew]` get picked up and carried on.** Drop an awkward base (or a finished potion you want a modifier on) into the chest and the golem loads it straight into a stand, skipping the water-and-wart trip entirely. That's what makes "to brew further, drop the potion back into `[Brew]`" work.

**Choosing what it may brew — the menu.** Right-click the alchemist to open a list of every potion, shown as the real potion item. Click one to switch it **on or off**. Everything is on by default, so the golem brews whatever the chest allows; switch a potion off and it will ignore that ingredient even if it's sitting in `[Brew]`. Below the potions are the modifiers (extended, stronger, splash, lingering, corrupt) — the same toggles.

If several allowed ingredients are in the chest at once, it takes the **most abundant** one (the same rule the Smelter uses for ore).

> Switching **Strength** off doesn't stop the golem using blaze powder as *fuel* — fuel and ingredients are separate jobs.

**Blaze powder is both fuel and an ingredient**, so the golem only ever keeps **one pinch in the fuel slot** (that's already 20 brews). Everything else stays available for Strength potions.

**Menu:** the potion toggles above, plus the on/off switch and stats (potions brewed, active since).

> **Note on ingredients:** vanilla brewing recipes can't be read from the server API (unlike furnace recipes), so the golem's ingredient list is maintained by hand in the plugin. A brand-new vanilla ingredient may need a plugin update to be recognised.

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
output-sign-text: "[Output]"    # Smelter: finished goods (+ returned buckets); Alchemist: potions
brew-sign-text:   "[Brew]"      # Alchemist: bottles + fuel + ingredients, all in one container

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
