# LavaGolem

A Paper plugin for Minecraft 1.21 that adds automated Copper Golems which fetch lava from cauldrons and deposit it in chests.

[README на русском](README_RU.md)

## Requirements

Paper **1.21.9 or newer** (Copper Golem entity is required).

## Features

- Spawn a Lava Golem by right-clicking on any block with a **Golem Heart** item
- The golem autonomously fetches empty buckets from a marked chest, fills them with lava from cauldrons, and deposits lava buckets into a deposit chest
- Sneak + right-click a golem **with empty hand** to disassemble it and recover the heart
- Right-click a golem to view its individual stats
- Golems survive server restarts

## Recipe

Shaped recipe: 4 copper ingots in corners, 4 redstone dust in cross pattern, 1 lava bucket in center.

```
C R C
R L R
C R C
```

*(C = Copper Ingot, R = Redstone Dust, L = Lava Bucket)*

## Setup

1. Place a **bucket chest** nearby and attach a sign to any of its faces with `[Buckets]` on the first line
2. Place a **lava cauldron** in range
3. Place a **lava deposit chest** nearby and attach a sign with `[Lava]` on the first line
4. Craft or obtain a Golem Heart and right-click the ground to spawn a golem

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/removegolems` | `lavagolem.admin` | Remove all lava golems |
| `/golemstats`   | `lavagolem.admin` | Show aggregate statistics |

## Configuration

Edit `plugins/LavaGolem/config.yml`:

```yaml
search-radius: 8          # Block radius to scan for chests/cauldrons
reach-distance: 2.2       # Distance at which golem "arrives" at target
search-cooldown-ticks: 40 # Ticks to wait before retrying a failed search
tick-period: 10           # How often the golem logic runs (ticks)
bucket-sign-text: "[Buckets]"
lava-sign-text: "[Lava]"
locale: en                # en or ru
bstats: true              # Anonymous usage stats
```

## Building

Requires Java 21 and Maven 3.x.

```bash
mvn clean package
```

The plugin jar will be at `target/lavagolem-1.0.0.jar`.

## License

[MIT](LICENSE)
