# LavaGolem

A Paper plugin for Minecraft 1.21 that adds three automated Copper Golems: one hauls lava from cauldrons, one keeps your furnaces running, and one carries items between your storage.

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

### 📦 Courier Golem
Carries items between tagged containers along routes you set in its GUI.

- **Routes** — as many as you like, each moving items from one tag to another. Left-click a slot to cycle nearby tags, right-click to type any tag yourself.
- **Item filter** — shift-click items from your inventory into the filter; per route it works as a **blacklist** (default) or a **whitelist**.
- **Status** — the menu says exactly why a route isn't running (no source, destination full, filter blocks everything, both ends are the same chest).
- **Waypoints** — place signs saying `[Waypoint]` along a walkable path and the courier walks marker-to-marker (up stairs, around corners) instead of into a wall. Markers are shared by every courier; each picks the one closest to *its* own target. If a target really can't be reached on foot, it teleports as a last resort (`courier-teleport`).

## Recipes

All three share the same shape — only the center changes:

```
C R C        C = Copper Ingot
R X R        R = Redstone Dust
C R C
```

| Golem | Center `X` |
|---|---|
| Lava Golem | Lava Bucket |
| Smelter Golem | Furnace |
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
| `[Output]` | Smelter — finished goods (spent buckets return here too) |
| *anything* | Courier — routes use whatever tags you type |

## Everyday use

- **Stats / settings** — right-click a golem with an empty hand to open its menu.
- **Disassemble** — sneak + right-click with an empty hand; the Heart drops back along with anything the golem carried.
- Golems, their current job, mode and routes survive server restarts.

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/removegolems` | `lavagolem.admin` | Remove all custom golems |
| `/golemstats`   | `lavagolem.admin` | Show aggregate statistics |

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

courier-search-radius: 24 # Courier only: same in every direction, must cover both ends of a route (max 32)
courier-carry-limit: 16   # Items a courier carries per trip
waypoint-sign-text: "[Waypoint]"
courier-teleport: true    # Last-resort blink when a target can't be walked to
courier-stuck-ticks: 20   # Logic ticks of no progress before blinking

locale: en                # en or ru
bstats: true              # Anonymous usage stats
```

The courier scans a cube, so its cost grows as radius³ — keep `courier-search-radius` only as big as your routes need.

## Building

Requires Java 21 and Maven 3.x.

```bash
mvn clean package
```

The plugin jar will be at `target/lavagolem-1.0.2.jar`.

## License

[MIT](LICENSE)
