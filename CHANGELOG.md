# 1.0.4 — The Fisher

## 🎣 Fisher Golem

A fifth golem that fishes your pond for you, on the game's own fishing weights. Recipe is the usual shape with a **Fishing Rod** in the center.

- **Works through your rods** — takes one from a `[Rods]` container, fishes until it breaks, takes the next. The rod *is* the running cost: ~64 catches at roughly 17 seconds each, about 18 minutes per rod. Fishing round the clock means a steady supply of rods, which means string and sticks, which means farms — the infrastructure is still yours to build.
- **Your rod's enchantments are what matter** — Luck of the Sea improves the catch on vanilla's own weights, Lure shortens the wait, Unbreaking makes the rod last.
- **Mending does nothing, on purpose.** The golem earns no XP. That's the trade for not standing there yourself, and it's what stops a fisher from being a perpetual motion machine.
- **Treasure needs open water**, exactly as in vanilla: 5×5 of water with air above. A 1×1 hole in the floor catches fish and junk forever and never a single treasure. Dig a real pond.
- **Sorts the catch** — add an optional `[Treasure]` container and treasure goes there while fish and junk go to `[Output]`. Skip it and everything lands in `[Output]`.

## Added

- **Custom catches.** `fisher-custom-catches` drops any item you like into the fisher's loot. Each entry joins one pool (`fish`, `junk` or `treasure`) and competes with the vanilla items there by weight, so a treasure entry only turns up on the ~5% of casts that roll treasure — and rides the `[Treasure]` routing with it.
- **Turn golems on or off.** `enable-lava-golem`, `enable-smelter-golem`, `enable-courier-golem`, `enable-alchemist-golem` and `enable-fisher-golem` (all on by default). A disabled golem can't be crafted or placed, and any that already exist sit inert until you switch it back on.
- **`/golemdebug`** (`lavagolem.admin`) — stand within 12 blocks of a golem and it narrates its decisions in chat: which containers it found, what it decided to do, and where it gave up. Run it again to switch it off. Traces the Smelter's decision loop, the one with the most moving parts.
- New tags `[Rods]` and `[Treasure]`, both settable per-golem from its menu like every other tag.
- `/golemstats` now also reports the total catch.

## Fixed

- **The Smelter no longer stalls on a lava-only station.** Lava is deliberately skipped as fuel for small batches so a whole bucket isn't wasted — but if lava was the *only* fuel in `[Fuel]`, fuel selection returned nothing, the load step refused to commit input it couldn't fuel, and the golem stood there forever. It now falls back to lava rather than doing nothing.
- **The Alchemist Heart stopped crafting after a `/reload`.** Its recipe wasn't being removed on plugin disable, so re-registering it on enable was rejected as a duplicate key and the recipe silently vanished. Every recipe is now cleaned up properly.

---

*Requires Paper 1.21+ and Java 21. Existing golems, their jobs, modes and stats carry over — no migration needed.*
