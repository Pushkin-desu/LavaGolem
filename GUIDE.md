# LavaGolem — Full Guide

Everything about setting the golems up, and what to do when a courier won't move.
[README](README.md) · [Русская версия](GUIDE_RU.md)

---

## Table of contents

- [Common rules](#common-rules)
- [Ownership, permissions & protection](#ownership-permissions--protection)
- [🪣 Lava Golem](#-lava-golem)
- [🔥 Smelter Golem](#-smelter-golem)
- [⚗️ Alchemist Golem](#️-alchemist-golem)
- [🎣 Fisher Golem](#-fisher-golem)
- [📦 Courier Golem](#-courier-golem)
- [How the courier finds its way](#how-the-courier-finds-its-way)
- [Troubleshooting: the courier won't move](#troubleshooting-the-courier-wont-move)
- [Asking a golem what's wrong](#asking-a-golem-whats-wrong)
- [Full config reference](#full-config-reference)

---

## Common rules

These apply to every golem.

- **Telling them apart.** The **Lava Golem is on fire** (purely visual — it takes no damage), the **Alchemist trails magic particles** and the **Fisher holds a fishing rod**. The Smelter and Courier are plain; the Courier is the one visibly carrying an item. No resource pack is involved.
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

## Ownership, permissions & protection

**Placing a Heart makes you the owner.** The Heart item itself is never tagged, so hearts stay freely tradeable — it's the act of right-clicking the ground with one that assigns ownership to whoever placed it. A golem placed before this existed (anything from before 1.1.0) has no owner and keeps working for anyone, exactly as it always did — nothing changes for an existing base until you touch it.

**Owning a golem controls who can use it.** Opening its menu, retagging its containers, editing courier routes, switching it off, disassembling it — all of that needs to be the owner, or a player with `lavagolem.use.others`. An unowned (legacy) golem skips this check entirely, same as before.

**Migrating an old base.** There are two ways to give a pre-1.1.0 golem an owner, and they are not equivalent:
- **`/lavagolem claim`** (needs `lavagolem.admin`) adopts the *nearest unowned golem within 12 blocks in place* — same entity, same stats, same custom container tags, same work mode, now with an owner.
- **Disassembling it and placing a fresh Heart** also works, but it's a *new* golem: counters reset to zero, custom tags are gone, courier routes have to be rebuilt from scratch.

If you're inheriting someone else's base, or adding ownership to golems you built before upgrading, `claim` is almost always the one you want — it's the only path that doesn't throw the golem's history away.

**A cap on how many one player can place.** `max-golems-per-player` (default `0`, unlimited) is checked at placement time by counting every golem a player already owns, across every loaded world; `lavagolem.limit.bypass` ignores it. Hitting the cap doesn't consume the Heart — placement is simply refused, so you keep the item.

**Permissions:**

| Permission | Default | Lets a player... |
|---|---|---|
| `lavagolem.craft` | `true` | Craft a Golem Heart |
| `lavagolem.place` | `true` | Place a Heart to spawn a golem |
| `lavagolem.use` | `true` | Use golems they own — menu, tags, routes, disassembly |
| `lavagolem.use.others` | `op` | Use a golem someone else owns |
| `lavagolem.limit.bypass` | `op` | Ignore `max-golems-per-player` |
| `lavagolem.admin` | `op` | Every `/lavagolem` subcommand (reload, stats, debug, claim, remove) |

**Container protection.** Golems read and write chest/barrel/etc. inventories directly, which by itself is invisible to a claim or region plugin — nothing would otherwise stop someone's golem from being placed next to your containers and routed to help itself. This only ever applies to an **owned** golem; a legacy, unowned one is never protection-checked, matching how it worked before ownership existed.

When GriefPrevention or WorldGuard is installed, the plugin asks it directly — GriefPrevention's claims and container trust, WorldGuard's `CHEST_ACCESS` region flag — and can get a real answer even while the golem's owner is offline, because that's the whole point of a claim/region system. A generic fallback also exists for other protection plugins: it fires a normal, cancellable interact event as the owner, which only works while the owner is online to fire it as.

`protection-mode` decides what happens when nothing can answer live (no GriefPrevention/WorldGuard installed, and the owner is offline):
- **`cached`** (default) — trust the last verdict recorded while the owner was online; with nothing recorded yet, allow. Good for stations that run unattended overnight.
- **`strict`** — refuse anything that can't be verified right now. Safer, but a golem's station goes quiet the moment its owner logs off.
- **`off`** — no container checks at all (the plugin's original behaviour, from before ownership existed).

`protection-cache-seconds` (default `300`) is how long that cached verdict is trusted before being treated as unknown again.

**Be aware:** the GriefPrevention and WorldGuard adapters have not been exercised on a live server running either plugin — the maintainer runs neither. Treat them as best-effort, not verified support. Every hook, adapter or generic, is held to the same rule regardless: a misbehaving one is never trusted blindly, so it can never wedge a golem — it's ignored on failure and switches itself off after enough repeated failures.

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

## 🎣 Fisher Golem

**Recipe center:** Fishing Rod · **Works with:** a pond + a `[Rods]` container and an `[Output]` container.

**What it does:** takes a rod out of your chest, stands on the bank, casts out over the water and reels the catch in, over and over until the rod breaks — then takes the next one and files the catch away. The catches use vanilla's own fishing weights, so it pulls up exactly what you would.

**How the fishing looks.** It stands on the shore and casts toward the water (it never wades in). A ripple marks where the line is; the wait to a bite is random, the same 5–30 seconds vanilla uses, and now and then a fish gets away — you'll see it reel up nothing and cast again. There is **no real bobber entity** floating on the line: a bobber belongs to a player-owned hook, which a golem doesn't have, so the ripple particle stands in for it.

**Setup:**
1. **Water** in range — see *open water* below for why the shape of it matters.
2. A `[Rods]` container with fishing rods in it.
3. An `[Output]` container for the catch.
4. *(Optional)* a `[Treasure]` container. Add one and treasure goes there while ordinary fish and junk still go to `[Output]`. Without it, everything lands in `[Output]`.

**The rod is the fuel.** There is no other running cost, and there doesn't need to be. A rod is 64 uses and a catch takes about 17 seconds on average, so **one rod is roughly 18 minutes of fishing**. A fisher that runs around the clock needs a steady supply of rods — which means string and sticks, which means farms. That's the infrastructure you're meant to build.

**Your rod's enchantments are what matter:**

| Enchantment | What it does for the golem |
|---|---|
| **Luck of the Sea** | Better catches — more treasure, less junk. Exactly the vanilla weighting. |
| **Lure** | Shortens the wait by 5 seconds per level. |
| **Unbreaking** | The rod lasts longer, the usual 1-in-(level+1) saving throw. |
| **Mending** | **Nothing.** See below. |

> **Mending is dead weight here.** The golem earns no XP — that's the trade for not having to hold the button yourself — so there is nothing to repair a rod with. Rods really do run out. Don't waste a Mending book on one.

**Open water and treasure.** In vanilla, treasure only bites in open water, and the same rule applies here: the golem needs **5×5 of water around its fishing spot with air above it**. A 1×1 hole in your floor will catch fish and junk forever and never yield a single treasure. Dig a real pond. If several bits of water are in range the golem prefers an open-water one, even if a puddle happens to be closer. You can switch treasure off entirely with `fisher-treasure: false`.

**Menu:** the on/off switch, the three container tags, and stats (fish and junk caught, treasure caught, rods used, active since).

**Adding your own catches.** In the config you can drop any item into the catch — a diamond, an emerald, anything — via `fisher-custom-catches`. Each entry joins one pool (fish, junk or treasure) and competes with the vanilla items there by weight, so a rare treasure entry only turns up on the small share of casts that roll treasure. Put it in the treasure pool and it rides the `[Treasure]` routing too. See the config reference below.

**If it just stands there:**
- No rod in `[Rods]` — or every rod in there is already worn out.
- No water within `search-radius` (8 blocks by default) — or only a cauldron, which is not a pond and can't be fished.
- Nowhere to put the catch: it won't fish something up just to stand there holding it, so it waits until `[Output]` (or `[Treasure]`) exists and has room.

> **Note on the loot:** the server API won't run the vanilla fishing tables for a plugin (they require a fishing rod as a parameter that the API can't supply), so the fish/junk/treasure lists and their weights are transcribed into the plugin from the game's own tables — the same way the Alchemist keeps the brewing recipes the API also won't hand over. What you catch, and how often, is vanilla; a brand-new vanilla catch would need a plugin update to appear.

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

The courier plans its own route before it ever takes a step. Off the main server thread, it runs an A* search over a map the plugin builds of "where can I actually stand" for every chunk it's visited — not vanilla's raw pathfinder, and not player-placed markers. That search hands back a short list of turn points, and vanilla's own short-range steering walks each leg between them (it already handles stairs, doors and small gaps well, so there's no reason to reinvent that part).

A few things follow from that:

- **The search is bounded.** It only explores loaded chunks, within `nav-search-margin` blocks of the straight line to the target (default 32). That bound is what lets a golem ever conclude "there's genuinely no way through" instead of exploring forever — but it also means long-distance hauling across chunks nobody's loaded isn't something this plugin does. Keep routes inside the area you actually play in.
- **A completed route is cached** by its start and end container, and replayed instantly on the next trip — no search at all. Digging or building near a cached route drops it from the cache immediately, so a rebuilt staircase or a newly-locked door takes effect on the courier's very next leg, not after some timeout.
- **`[Waypoint]` signs are optional, not the mechanism.** They still do two real things: ground within a few blocks of one is cheaper for the search to route through, so a golem prefers a road you actually built over open ground when both would work; and if a search fails outright, the courier falls back to hopping marker-to-marker the way it always used to. You no longer need to lay them for an ordinary route — the golem finds its own way across open, walkable ground on its own. Lay them where you want to *bias* the golem onto a particular path (a bridge over a ravine, say) or as a safety net for a route the search can't otherwise solve.

**Placing waypoints.** Signs reading `[Waypoint]` (text configurable via `waypoint-sign-text`) still work the same way they always did — like breadcrumbs along the path you want to bias or fall back to:

- one near the bottom of a staircase,
- one on each landing or turn,
- one near the top, close to the destination.

Markers are shared by every courier; each one judges distance against **its own** target, so one network of signs can bias or rescue routes for any number of couriers going in any direction — you never name or assign them to a particular golem.

### When a route can't be walked

A golem that genuinely can't reach its target doesn't blink past the problem — it tells you:

1. Whatever it was carrying is **re-homed** back to a container instead of being stranded mid-route.
2. **`(stuck)`** is appended to the golem's name, visible above its head — no menu needed to spot it from across the base.
3. The courier's menu shows the **reason** in that route's status line.
4. It **retries on a widening backoff** — short at first, longer each time it fails again — so once you fix the route (rebuild the stairs, clear the door, reopen a claim) it picks the job back up on its own, with no player action beyond fixing the actual problem.

That's the intended way to play: leave `courier-teleport` at its default, **`false`**, and read `(stuck)` as the golem accurately reporting broken infrastructure rather than something to route around. Setting `courier-teleport: true` brings back the old blink-to-target behaviour — kept only for a server whose geometry genuinely can't be walked at all, not as the everyday answer to a stuck courier.

---

## Troubleshooting: the courier won't move

Work down this list — it's ordered by how common each cause is.

**1. Status isn't `Ready`.**
Open the menu and read the status line for that route (table above). A red status means the route *can't* run yet — fix the tags, fill the source, or make room in the destination. This is the most common cause of "green heart but standing still" **when it isn't actually green**.

**2. The containers are out of range.**
`courier-search-radius` is measured **from where the golem is standing now**, and the **same in every direction** — the cube must cover *both* ends of the route. Default is 24. If your source and destination are 40 blocks apart, no radius will see both — move the golem between them or shorten the route. Note the radius is **capped at 32** even if you set it higher (a 65³ cube is already ~275k blocks; the scan cost grows as radius³, so bigger would stall the server).

**3. Its name says `(stuck)`, or the route status shows a reason.**
The golem's own A* genuinely couldn't find a walkable route within `nav-search-margin` of the target — a closed door it reads as a wall, a drop bigger than `nav-max-step-down`, water it won't wade through, or simply nothing built between the two ends yet. Read the reason in the courier's menu, fix the actual obstacle, and the golem picks the route back up on its own within a few retries — no need to re-place it or reset anything. If a path is legitimately walkable but the search struggles to find it (a maze of choices, say), a few `[Waypoint]` signs along the route you want will bias the search onto it, same as before.

**4. It seems to detour oddly, or won't use a shortcut you built.**
Digging or building near a route drops the courier's cached path immediately, but the golem still won't cross ground it hasn't mapped as walkable — a closed door reads as a wall, the top of a fence or wall isn't floor, and a diagonal move is never allowed to also step up (mobs can't jump diagonally onto a block). If a shortcut isn't being used, check it's actually walkable by those rules, not just by a player.

**5. The sign text doesn't match.**
A `[Waypoint]` sign's text must equal `waypoint-sign-text` exactly (default `[Waypoint]`), on any face of the sign. A tag on a container must equal the tag you set in the route (or the container's anvil name), exactly.

**6. It never even starts.**
Check the route filter. A **whitelist** with no items carries **nothing**; an over-broad **blacklist** can exclude everything the source holds. The status will say *"Source has nothing the filter allows."*

**7. Nothing works and you just want it to go.**
`courier-teleport` defaults to **`false`** on purpose — a stuck courier is telling you the route is broken, and the intended fix is to rebuild the route, not blink past it. If you're certain the geometry genuinely can't be walked (a server with terrain from before real pathfinding existed, say), set `courier-teleport: true` to bring back the old last-resort blink. If it's already `true` and the golem still won't move, the cause is almost always #1 or #2 — it has no valid job to do in the first place.

---

## Asking a golem what's wrong

If a golem is standing there and you can't see why, stand within 12 blocks of it and run
**`/lavagolem debug`** (needs `lavagolem.admin`). It latches onto the nearest golem and narrates its
decisions in chat — which containers it found, what it decided to do, and where it gave up:

```
[G] decide mode=BALANCED furnaces=3 smelt=true fuel=false output=true
[G] step2 STALL: furnace has no fuel and no [Fuel] chest found
```

Run it again to switch tracing off. It's per-golem and doesn't survive a restart, so it's safe to
leave on while you fix the station.

You don't have to walk to each golem: **`/lavagolem debug all`** traces every golem, and
**`/lavagolem debug courier`** (or any other role name) traces just that role, including golems
placed later.

Chat is a poor place to read a long trace, so `golemdebug-output` in the config can send it to
`plugins/LavaGolem/golemdebug.log` instead (`chat`, `file` or `both`). File output keeps writing
after you log out, which is the point — leave a courier tracing, come back, and read what it did.
Each line is stamped with the time and a short golem id, so several golems at once still make sense.

Alongside each role's own decisions it traces the whole pathfinding story: the route it asked for,
whether it found a complete path or gave up, what it cached, and what Minecraft's own navigation did
with each step it was handed.

---

## Full config reference

`plugins/LavaGolem/config.yml`:

```yaml
search-radius: 8            # Blocks a golem scans for containers/cauldrons/furnaces (from where it stands)
reach-distance: 2.2         # Distance at which a golem "arrives" at its target
search-cooldown-ticks: 40   # Ticks to wait before retrying a failed search (20 ticks = 1s)
tick-period: 10             # How often golem logic runs, in game ticks
golem-stuck-ticks: 30       # Logic ticks with no real progress before a golem gives up on its target and looks for other work (min 5, ~15s at the default tick-period)

# Tags — each works as a sign OR as the container's own anvil name
bucket-sign-text: "[Buckets]"   # Lava Golem: empty buckets
lava-sign-text:   "[Lava]"      # Lava Golem: filled lava buckets
smelt-sign-text:  "[Smelt]"     # Smelter: items to melt
fuel-sign-text:   "[Fuel]"      # Smelter: any vanilla fuel
output-sign-text: "[Output]"    # Smelter: finished goods (+ returned buckets); Alchemist: potions; Fisher: the catch
brew-sign-text:   "[Brew]"      # Alchemist: bottles + fuel + ingredients, all in one container
rods-sign-text:   "[Rods]"      # Fisher: fishing rods to work through
treasure-sign-text: "[Treasure]" # Fisher: optional — treasure goes here instead of [Output]

# --- Fisher only ---
fisher-min-wait-ticks: 100   # Vanilla's own wait window is 100-600 ticks (5-30s); Lure takes 100 off per level
fisher-max-wait-ticks: 600
fisher-treasure: true        # false = fish and junk only, no treasure at all
# Add your own items to the catch. Each joins ONE pool and competes by weight with the vanilla items
# there — a treasure entry only appears on the ~5% of casts that roll treasure. Rough pool totals:
# fish ~100, junk ~100, treasure ~6. Config items are given exactly as written (no wear/enchanting).
fisher-custom-catches: []
#  - { material: DIAMOND, weight: 2, amount: 1, pool: treasure }
#  - { material: EMERALD, weight: 5, amount: 1, pool: junk }

# --- Turn golems on or off (all on by default) ---
# A disabled golem can't be crafted or placed; any that already exist sit idle until re-enabled.
enable-lava-golem: true
enable-smelter-golem: true
enable-courier-golem: true
enable-alchemist-golem: true
enable-fisher-golem: true

# --- Courier only ---
courier-search-radius: 24    # Same in every direction; must cover BOTH ends of a route. Capped at 32 (cost ~ radius^3)
courier-carry-limit: 16      # Items carried per trip (1-64)
waypoint-sign-text: "[Waypoint]"  # Optional marker text — ground near one is cheaper to route through, and it's the fallback if a search fails
courier-teleport: false      # Legacy last-resort blink for geometry that genuinely can't be walked. Off by default: a stuck golem re-homes its load, marks itself "(stuck)" and retries on a backoff instead
courier-stuck-ticks: 20      # Parsed but not currently read by the navigation stall/retry logic — see golem-stuck-ticks and nav-move-refused-ticks below for what actually governs a courier's stalls

# --- Navigation (long-distance walking) ---
# Every golem now plans its walk with its own A* pathfinder over a cached map of "where can I stand"
# for each chunk it's visited, instead of relying only on vanilla's short-range pathfinding plus
# [Waypoint] hopping. Vanilla still handles the last few steps (doors, stairs, small gaps) — this
# just gets the golem to the right neighbourhood first, reliably, within a bounded search area.
nav-async: true               # Compute routes on a background thread instead of the main thread
nav-max-distance: 256         # How far (blocks) a single route search may reach before falling back to waypoint-hopping
nav-max-nodes: 20000          # Map cells one search may examine before settling for its best partial route
nav-chunk-cache-seconds: 600  # How long a chunk's walkability map is trusted before being rebuilt from scratch (on top of immediate event-based invalidation)
nav-chunks-per-tick: 4        # Chunks' worth of walkability map the server may build per logic tick during the normal slow trickle
nav-chunks-per-tick-burst: 24 # Ceiling used instead of the above for one tick when a golem is actively blocked waiting on chunks
nav-max-concurrent: 4         # How many golems may have a route search running at once, server-wide
nav-starved-retries: 40       # Absolute ceiling on consecutive waits for unmapped terrain before giving up regardless (runaway guard)
nav-starved-stall-tries: 3    # Consecutive waits with no shrinking of the unmapped count before walking the best partial instead
nav-prewarm-max-chunks: 256   # Cap on chunks a leg's first request may seed into the build queue
nav-max-step-down: 1          # Blocks a route may step DOWN in a single move (1-3) — vanilla won't voluntarily walk further than this anyway
nav-max-leg-blocks: 10        # Longest a single leg may be, in blocks (4-32), before being chopped back to known-walkable points
nav-search-margin: 32         # How far past the straight line to the target a route may wander, in blocks (8-128) — the search bound
nav-move-refused-ticks: 4     # Consecutive ticks vanilla may flatly refuse to walk toward the current turn point before that alone counts as a stall (2-40)

locale: en                  # en or ru
bstats: true                # Anonymous usage statistics (bstats.org)

# --- Debug tracing ---
golemdebug-output: chat     # chat | file | both — where /lavagolem debug traces go (file: plugins/LavaGolem/golemdebug.log, rotated past 5MB)

# --- Ownership & golem cap ---
max-golems-per-player: 0    # Max golems a single player may have placed at once (0 = unlimited); lavagolem.limit.bypass ignores it

# --- Container protection ---
protection-mode: cached         # cached | strict | off — what to do when a golem's owner can't be reached live (see "Ownership, permissions & protection" above)
protection-cache-seconds: 300   # How long a container's last verdict is trusted as the offline fallback, in seconds
```

**Reloading:** `/lavagolem reload` re-reads `config.yml` without restarting the server. Config no longer needs to be deleted between updates, either: on startup, any key the shipped template has that your file is missing gets appended with its documentation (after a backup to `config.yml.bak`); a key your file has that the plugin no longer ships is reported in the log but never rewritten or removed.
