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
- **Waypoints** — place signs saying `[Waypoint]` along a walkable path and the courier walks marker-to-marker (up stairs, around corners) instead of into a wall. Markers are shared by every courier; each picks the one closest to *its* own target. If a target really can't be reached on foot, it teleports as a last resort (`courier-teleport`).

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

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/removegolems` | `lavagolem.admin` | Remove all custom golems |
| `/golemstats`   | `lavagolem.admin` | Show aggregate statistics |
| `/golemdebug`   | `lavagolem.admin` | Toggle live decision tracing for the nearest golem (within 12 blocks) — it reports in chat why it isn't working |

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
waypoint-sign-text: "[Waypoint]"
courier-teleport: true    # Last-resort blink when a target can't be walked to
courier-stuck-ticks: 20   # Logic ticks of no progress before blinking

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

The plugin jar will be at `target/lavagolem-1.0.4.jar`.

## License

[MIT](LICENSE)
