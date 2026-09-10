# LavaGolem

A Paper plugin for Minecraft 1.21 that adds five automated Copper Golems: one hauls lava from cauldrons, one keeps your furnaces running, one runs your brewing stands, one fishes your pond, and one carries items between your storage.

[README на русском](README_RU.md) · [Full guide & troubleshooting](GUIDE.md)

## Requirements

Paper **1.21.9 or newer** (Copper Golem entity is required).

## The golems

### 🪣 Lava Golem
Takes an empty bucket from a `[Buckets]` container, fills it at a full lava cauldron, and drops the lava bucket into a `[Lava]` container. Forever.

### 🔥 Smelter Golem
Feeds your furnaces and hauls out the results — no hoppers, no rails.

- Takes the most abundant smeltable from `[Smelt]`, picks a fuel from `[Fuel]` sized so nothing is wasted, delivers the results to `[Output]`.
- Spreads a big pile evenly across all idle furnaces instead of dumping it into one.
- Pulls the empty bucket back out after a lava bucket burns, so the furnace never clogs.
- Handles **furnaces, blast furnaces and smokers**, routing automatically: ores → blast furnaces, food → smokers, everything else → furnaces.
- Right-click it for a menu with work modes: **Balanced**, **Load only**, **Collect only**.

### ⚗️ Alchemist Golem
Runs your brewing stands — no dropper-and-hopper brewery.

- **One chest for everything** — put glass bottles, nether wart, blaze powder (or rods) and ingredients into a single `[Brew]` container; the golem sorts out what each item is for.
- **Fetches its own water** — fills bottles at a water source or a water cauldron (which rain/dripstone refills).
- **Brews by stages** — water → nether wart → awkward → an effect ingredient → one modifier, then hauls the potions to `[Output]`. It brews for as long as the ingredients are in the chest.
- **Pick what it may brew** — its menu lists every potion as the real potion item; click to switch each one on or off (modifiers too). All on by default; with several ingredients available it takes the most abundant.
- **Keeps only one blaze powder as fuel** (a single pinch is 20 brews), leaving the rest available for Strength potions.
- **Grinds blaze rods** into powder at a crafting table, using the real vanilla recipe.

### 🎣 Fisher Golem
Fishes your pond for you, on vanilla's own fishing weights.

- **Works through your rods** — takes one from `[Rods]`, fishes until it breaks, takes the next. The rod *is* the running cost: ~64 catches, about 18 minutes each.
- **Your rod's enchantments matter** — Luck of the Sea improves the catch, Lure shortens the wait, Unbreaking makes it last. **Mending does nothing**: the golem earns no XP, so rods genuinely run out.
- **Treasure needs open water**, exactly as in vanilla — 5×5 of water with air above. A 1×1 hole catches fish and junk forever and no treasure.
- **Sorts the catch** — add an optional `[Treasure]` container and treasure goes there while fish and junk go to `[Output]`.

### 📦 Courier Golem
Carries items between tagged containers along routes you set in its GUI.

- **Routes** — as many as you like, each moving items from one tag to another. Left-click a slot to cycle nearby tags, right-click to type any tag yourself.
- **Item filter** — shift-click items from your inventory into the filter; per route it works as a **blacklist** (default) or a **whitelist**.
- **Status** — the menu says exactly why a route isn't running (no source, destination full, filter blocks everything, both ends are the same chest).
- **Finds its own way** — plans each route with its own pathfinder over a cached map of walkable ground, off the main thread, then lets vanilla walk the short legs in between (stairs, doors, corners). `[Waypoint]` signs are optional, not required: ground near one is cheaper to route through, so the golem prefers a road you've built, and they're still the fallback if a search comes up empty. A golem that genuinely can't reach its target says so instead of cheating past the problem — it re-homes whatever it was carrying, appends `(stuck)` to its name, shows the reason in the route status, and keeps retrying on a widening backoff (`courier-teleport`, off by default, brings back the old blink-to-target behaviour).

## Recipes

All five share the same shape — only the center changes:

```
C R C        C = Copper Ingot
R X R        R = Redstone Dust
C R C
```

| Golem | Center `X` |
|---|---|
| Lava Golem | Lava Bucket |
| Smelter Golem | Furnace |
| Alchemist Golem | Brewing Stand |
| Fisher Golem | Fishing Rod |
| Courier Golem | Hopper |

The result is a **Golem Heart**. Right-click the ground with it to place the golem.

## Tagging containers

Storage can be a **chest, trapped chest, barrel or shulker box**. Tag it either by:
- placing a **sign** with the matching text on any of its 6 faces, **or**
- **renaming the container itself** in an anvil to that text (no sign needed).

| Tag | Used by |
|---|---|
| `[Buckets]` | Lava Golem — empty buckets |
| `[Lava]` | Lava Golem — filled lava buckets |
| `[Smelt]` | Smelter — items to melt |
| `[Fuel]` | Smelter — any vanilla fuel |
| `[Output]` | Smelter — finished goods (spent buckets return here too); Alchemist — potions; Fisher — the catch |
| `[Brew]` | Alchemist — bottles, fuel and ingredients, all in one |
| `[Rods]` | Fisher — fishing rods to work through |
| `[Treasure]` | Fisher — optional; treasure lands here instead of `[Output]` |
| *anything* | Courier — routes use whatever tags you type |

These are only the **defaults**. In any golem's menu, right-click a container slot to give *that golem* its own tag (left-click resets it), so two stations of the same kind can stand side by side without sharing an `[Output]`.

## Everyday use

- **Telling them apart** — the Lava Golem is on fire (visual only, it takes no damage), the Alchemist trails magic particles, and the Fisher holds a rod. No resource pack.
- **Stats / settings** — right-click a golem with an empty hand to open its menu.
- **Switching one off** — every menu has a power button; the golem stops where it stands until you switch it back on.
- **Disassemble** — sneak + right-click with an empty hand; the Heart drops back along with anything the golem carried.
- Golems, their current job, mode and routes survive server restarts.

## Ownership & protection

- **Placing a Heart makes you the owner.** The Heart itself is never tagged — it stays freely tradeable — only the act of placing it assigns ownership. A golem placed before 1.1.0 has no owner and keeps working for anyone, exactly as before.
- **Only the owner can use it** — menu, tags, routes, disassembly — unless you (or they) have `lavagolem.use.others`.
- **Migrating an old golem?** `/lavagolem claim` adopts the nearest unowned golem within 12 blocks *in place*, keeping its stats, tags and mode. Disassembling and re-placing instead starts a brand-new golem with counters back at zero — `claim` is almost always what you want.
- **A cap, if you want one.** `max-golems-per-player` (default `0`, unlimited) limits how many golems one player may have placed at once; `lavagolem.limit.bypass` ignores it. A refused placement never consumes the Heart.

| Permission | Default | Lets a player... |
|---|---|---|
| `lavagolem.craft` | true | Craft a Golem Heart |
| `lavagolem.place` | true | Place a Heart to spawn a golem |
| `lavagolem.use` | true | Use golems they own — menu, tags, routes, disassembly |
| `lavagolem.use.others` | op | Use a golem someone else owns |
| `lavagolem.limit.bypass` | op | Ignore `max-golems-per-player` |
| `lavagolem.admin` | op | Every `/lavagolem` subcommand |

**Container protection.** If GriefPrevention or WorldGuard is installed, an *owned* golem's container access is checked against it (claims + container trust for GriefPrevention, the `CHEST_ACCESS` flag for WorldGuard); a legacy, unowned golem is never checked, matching how it always worked. A generic fallback hook covers other protection plugins too, but only while the owner is online to fire it as. `protection-mode` governs what happens when the owner can't be reached live: `cached` (default) trusts the last recorded verdict, `strict` refuses anything unverified, `off` disables the checks entirely.

The GriefPrevention and WorldGuard adapters have **not been tested on a live server** running either plugin — treat them as best-effort, not verified. No hook is ever trusted blindly: a misbehaving one is ignored rather than allowed to wedge a golem, and it switches itself off after repeated failures.

## Commands

Everything lives under `/lavagolem` (short form `/lg`), and all of it needs `lavagolem.admin`.

| Command | Description |
|---------|-------------|
| `/lavagolem reload` | Re-read `config.yml` without restarting the server |
| `/lavagolem stats` | Show aggregate statistics |
| `/lavagolem debug [all\|<role>]` | Toggle live decision tracing — no argument follows the nearest golem within 12 blocks, `all` every golem, or name a role (`courier`, `smelter`, …). It reports why a golem isn't working, in chat or to a log file |
| `/lavagolem claim` | Take ownership of the nearest unowned golem within 12 blocks |
| `/lavagolem remove <all\|player>` | Remove every golem, or only one player's |

`/lavagolem remove` deliberately refuses to run without an argument — it deletes golems belonging to
everyone on the server, and that is not something to trigger from muscle memory.

The old names — `/removegolems`, `/golemstats`, `/golemdebug`, `/golemclaim` — still work and still
need the same permission, but they are deprecated and will print a pointer at their replacement.

## Configuration

Edit `plugins/LavaGolem/config.yml`:

```yaml
search-radius: 8          # Block radius to scan for chests/cauldrons/furnaces
reach-distance: 2.2       # Distance at which a golem "arrives" at its target
search-cooldown-ticks: 40 # Ticks to wait before retrying a failed search
tick-period: 10           # How often golem logic runs (ticks)
bucket-sign-text: "[Buckets]"
lava-sign-text: "[Lava]"
smelt-sign-text: "[Smelt]"
fuel-sign-text: "[Fuel]"
output-sign-text: "[Output]"
brew-sign-text: "[Brew]"  # Alchemist: bottles + fuel + ingredients in one container
rods-sign-text: "[Rods]"  # Fisher: fishing rods to work through
treasure-sign-text: "[Treasure]" # Fisher: optional, treasure goes here instead of [Output]

fisher-min-wait-ticks: 100 # Fisher only: vanilla's wait window is 100-600 ticks; Lure takes 100 off per level
fisher-max-wait-ticks: 600
fisher-treasure: true     # false = fish and junk only
fisher-custom-catches: [] # add your own items to the catch — see below

# Turn golems on or off (all on by default). A disabled golem can't be crafted or placed.
enable-lava-golem: true
enable-smelter-golem: true
enable-courier-golem: true
enable-alchemist-golem: true
enable-fisher-golem: true

courier-search-radius: 24 # Courier only: same in every direction, must cover both ends of a route (max 32)
courier-carry-limit: 16   # Items a courier carries per trip
waypoint-sign-text: "[Waypoint]" # Optional: ground near a marker is cheaper to route through, and it's the fallback if a search fails
courier-teleport: false   # Legacy last resort for geometry that genuinely can't be walked; leave off so a stuck golem retries instead of cheating past broken infrastructure

# --- Navigation ---
# Every golem plans its walk with its own pathfinder (off the main thread) over a cached map of
# walkable ground; vanilla only handles each short leg (stairs, doors, small gaps). Golems only path
# through loaded chunks, within nav-search-margin of the target — this isn't for long-distance
# hauling across unloaded terrain. Many more nav-* tuning keys exist; see the full guide.
nav-max-distance: 256     # How far (blocks) a single route search may reach before giving up
nav-search-margin: 32     # How far past the straight line to the target a route may wander (blocks)
nav-chunk-cache-seconds: 600 # How long a chunk's walkability map is trusted before being rebuilt

# --- Ownership & golem cap ---
max-golems-per-player: 0  # Max golems one player may have placed at once (0 = unlimited)

# --- Container protection ---
protection-mode: cached       # cached | strict | off — what to do when a golem's owner can't be reached live
protection-cache-seconds: 300 # How long a container's last verdict is trusted as the offline fallback

golemdebug-output: chat   # chat | file | both — where /lavagolem debug traces go
locale: en                # en or ru
bstats: true              # Anonymous usage stats
```

The courier scans a cube, so its cost grows as radius³ — keep `courier-search-radius` only as big as your routes need.

**Custom catches.** Add any item to the fisher's catch with `fisher-custom-catches`. Each entry joins one pool and competes with the vanilla items there by weight:

```yaml
fisher-custom-catches:
  - { material: DIAMOND, weight: 2, amount: 1, pool: treasure }
  - { material: EMERALD, weight: 5, amount: 1, pool: junk }
```

`pool` is `fish`, `junk` or `treasure`; a `treasure` entry only appears on the ~5% of casts that roll treasure and rides the `[Treasure]` routing. Pool weight totals are roughly: fish ~100, junk ~100, treasure ~6. Config items are handed over exactly as written (no vanilla wear or enchanting).

## Building

Requires Java 21 and Maven 3.x.

```bash
mvn clean package
```

The plugin jar will be at `target/lavagolem-1.1.0.jar`.

## License

[MIT](LICENSE)
