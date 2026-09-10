# 1.1 — Golems that walk, and golems that are yours

Two big things. Golems find their own way around now, and they belong to somebody.

## 🧭 They walk there themselves

Golems used to point Minecraft's own pathfinder at a chest and hope. When that stalled they hopped between `[Waypoint]` signs you had placed by hand, and when that failed too they simply **teleported**. Long routes meant a trail of signs down every staircase, and a golem blinking through a wall was the least Minecraft-looking thing in the plugin.

The plugin now does its own pathfinding, on a background thread, over a cached map of ground it knows a golem can stand on. It works out the handful of turning points a route actually needs and lets vanilla walk the short legs between them — because doors, fences, jumps and gaps are things vanilla already handles well.

Those turning points are exactly the waypoints you used to place. The plugin generates them now.

- **`[Waypoint]` signs still matter, they're just optional.** Ground near one is cheaper to walk, so golems prefer the road you built over scrambling across raw terrain — and the old marker-hopping is still there as a fallback if a search comes up empty.
- **Teleporting is off by default.** A golem that can't reach its target is telling you the route is broken, and this plugin has always been about the infrastructure you build. Now it says so instead of cheating past it.
- **When a route really is impossible**, the courier carries its load back to a container instead of stranding it in a field, gets `(stuck)` added to its name so you can spot it walking past, and puts the reason in its route status. It retries on a widening backoff, so rebuilding that staircase brings it back on its own — you don't have to touch the golem.

## 🔒 Golems have owners

Anyone could walk up to your golem, sneak-click it, and leave with the heart and whatever it was carrying. Or open its menu and rewrite your routes.

A golem now remembers who placed it. Opening its menu and taking it apart need you to be the owner, or to hold `lavagolem.use.others`.

- **Golems placed before this update keep working for everyone**, exactly as they did. Nothing on a running server locks itself when you update.
- **`/lavagolem claim`** adopts the nearest unowned golem where it stands, keeping its statistics, tags and work mode. Disassembling and re-placing works too, but that's a brand-new golem with its counters back at zero.
- **Permissions:** `lavagolem.craft`, `lavagolem.place`, `lavagolem.use`, `lavagolem.use.others`, `lavagolem.limit.bypass`.
- **`max-golems-per-player`** caps how many anyone may place. A refused placement never eats the heart.

## 🛡️ Land protection is respected

A courier moved items by reaching into containers directly, which fires no event at all — so no protection plugin ever saw it. A courier placed next to somebody else's tagged chests, with a route from their tag to yours, was an automated thief.

Container access is now checked against the land protection you already run: proper adapters for **GriefPrevention** and **WorldGuard**, and a generic path that covers other plugins while the owner is online. `protection-mode` chooses what happens when nothing can answer — `cached` (the default) trusts the last verdict from while you were online, so stations keep running overnight; `strict` refuses what it can't verify; `off` restores the old behaviour.

**Told straight:** neither adapter has been tested against a live server — I don't run either plugin. What *is* handled is how they fail: a misbehaving hook is ignored rather than believed, so it can never freeze a golem, and it switches itself off after repeated failures instead of filling your console. If you run either plugin, please open an issue and tell me what you see.

## Added

- **One command.** Everything lives under **`/lavagolem`** (or `/lg`): `reload`, `stats`, `debug`, `claim`, `remove`. The old names still work and still need the same permission, but they're deprecated and will point you at the replacement.
- **`/lavagolem reload`** re-reads the config without a restart — including switching golem roles on and off, which registers and removes their recipes live.
- **`/lavagolem remove` now needs an argument**: `all`, or a player's name to remove only theirs. It deletes everybody's golems, and that shouldn't be a thing you can trigger from muscle memory.
- **`/lavagolem debug all`** or **`/lavagolem debug courier`** traces a whole fleet, or one role, without walking to each golem.
- **`golemdebug-output: chat | file | both`** sends traces to `plugins/LavaGolem/golemdebug.log`, which keeps being written after you log out. Leave a courier tracing, go away, come back and read what it did.
- **The config file updates itself.** New keys are appended with their documentation, after a backup to `config.yml.bak`. Keys that are no longer used get mentioned, never rewritten or deleted. No more wiping your settings to see what's new.

## Fixed

- **Right-clicking a golem no longer takes the item out of its hands.** The plugin cancelled one of the two events a right-click raises, so vanilla's own copper-golem item-take ran anyway: a carried stack simply left your station. It's cancelled properly now, and the golem's real equipment is pushed back to your client so the item stops flickering out of view.
- **Golems no longer freeze while walking a detour.** Progress was measured purely as distance closed toward the target, so any route going *around* something — a building, a hill, the long way to a staircase — looked like standing still. Every fifteen seconds the golem gave up on a path it was walking perfectly well and started over. A golem that's moving is making progress now, whatever the straight line says.
- **Golems stopped trying to walk along the tops of fences.** A fence is "solid", so the map thought its top was a walkable ledge and routed golems along it — a step vanilla then refused to take, which is why a golem could leave a base easily and never get back in.
- **Doors work.** They were treated as solid walls whether open or shut, so golems planned their way around every doorway.
- **Diagonal climbs are gone.** The map allowed stepping up a block while moving diagonally, which a mob cannot do. Descending routes worked and the same route back up did not.
- **A single bad golem can't take down the rest** — each one is ticked in isolation, and a golem whose target ended up in another world (dragged through a portal, say) no longer throws every tick.
- **Every role notices when it's stuck**, not just the courier, and says why through `/lavagolem debug` rather than standing there in silence.
- **`search-radius` is capped** at 32 like the courier's already was. Both scan a cube, so the cost grows with the cube of the radius.

## For server owners

- The jar is `lavagolem-1.1.0.jar`.
- Your existing `config.yml` is kept and topped up in place on first start. Check `config.yml.bak` if anything looks wrong.
- **`courier-teleport` now defaults to `false`.** If your config already sets it to `true`, it stays `true` — change it by hand if you want the new behaviour.
- Nothing about your golems needs migrating. Jobs, routes, modes, tags and statistics all carry over.

---

*Requires Paper 1.21+ and Java 21.*
