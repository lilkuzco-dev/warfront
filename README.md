# Warfront

Modern military factions for Minecraft 26.2 (Fabric). Three doctrine-driven factions (Vostok: mass and attrition; Aegis: combined-arms precision; Sarab: asymmetric ambush) with soldiers, bases, tech progression, and the order pipeline every future phase builds on.

Requires Fabric Loader 0.19.3+ and Fabric API for 26.2.

## Civilization update — Phases 1–2

- Cities persist as data independently of world generation. Their citizens cycle
  through exactly one representation: embodied inside 48 blocks, local-abstract in
  loaded chunks, and virtual in unloaded chunks.
- Five citizen professions ship in Phase 1: miner, farmer, builder, trader, and
  laborer. Embodied citizens pathfind, work at blocks/routes, flee monsters, and are
  defended by the existing soldier-vs-hostile behavior. Nearby same-faction soldiers
  are assigned to the city rather than duplicated into a new military system.
- The actor record owns serial ID, position, partial work, and full goods inventory.
  Demotion snapshots the entity; local/virtual work uses deterministic elapsed-time
  arithmetic; promotion overwrites even a stale chunk-restored entity from that
  authoritative record.
- Commands: `/warfront city create <id> <faction> <1-500>`,
  `/warfront city list`, `/warfront city inspect <id>`, and (op)
  `/warfront city validate`. `inspect` includes the three tier counts, total goods,
  assigned soldiers, and the last measured city tick cost.
- Every city now runs one persistent, deterministic economy across all three fidelity
  tiers. Finite food/ore/timber nodes, heterogeneous skill/metabolism/aptitude,
  input-consuming crafting, upkeep, a liquidity floor, fixed-amount exchange, and
  local supply/demand prices produce winners and genuine poverty traps from an equal
  money start. Money only transfers; goods reconcile against production,
  consumption, and shock loss after every tick.
- `/warfront city economy <id>` exposes Gini, poverty and top-five-percent shares,
  wealth quantiles, regional prices, conservation status, and the last economic tick
  cost. Operators can inject `vein_depletion`, `blight`, `raid`, or `fire` with
  `/warfront city shock <id> <type>`.
- Economy cadence, starting wealth, liquidity, exchange, and shock frequency/severity
  are datapack knobs in `data/warfront/warfront_config/economy.json`.

The equal-start 10,000-tick results and conservation/shock/performance evidence are
recorded in `ECONOMY_VALIDATION.md`. Phase 3 classes and governance remain fenced
pending review.

## v0.2.2 — garrison reliability update

- Naturally generated bases show their template soldiers as soon as their chunks
  load, then fill to the configured tier target after a short settlement window.
  Player-position discovery now recovers bases even if every seed entity was removed.
- Fixed a chunk-load race that could overfill an HQ (reproduced at 48 soldiers for a
  maximum-40 Vostok HQ), and made the global cap apply across an entire spawn batch,
  patrols, and tactical assault waves.
- Enemy assault units can no longer enter a rival base's persistent garrison ledger.
  Existing bad memberships and overfilled loaded bases repair themselves safely.
- Hardened malformed saved squad IDs, failed entity creation, scheduled-task cleanup,
  and unreachable station claims. `/warfront bases` now reports stored, loaded,
  target, and hydration counts for diagnosis.

## v0.2.0 — the Garrison Update

- **Population**: bases now carry real garrisons — tier-scaled counts per faction
  (Vostok masses, Aegis fields small elite crews, Sarab disperses), lazy-hydrated as
  you approach, respawning **one soldier per `warfront:bunk` block** per interval
  (destroy the barracks and the base stops recovering; reinforcement pauses while
  enemies are inside the wire). Roaming squads shuttle between friendly bases:
  Vostok road-march columns, Aegis cross-country teams, Sarab night pairs. A global
  per-player soldier cap keeps TPS honest. Tuning: `population` block in each faction
  JSON + `warfront_config/population.json`.
- **Bases, rebuilt from real builds**: three tiers — outposts (rebuilt, bigger),
  forward bases, and rare headquarters (~1,500–2,000 block spacing). Every enclosed
  building is adapted from license-cleared open-source structures (Repurposed
  Structures, LGPL — see `CREDITS.md` and `structures/SOURCES.md`) or referenced
  vanilla pieces rethemed at generation; freehand geometry is limited to walls,
  gates, paths, trenches, and pads. One sourced build ships in three faction skins
  via `tools/retheme-structure.js`. Guaranteed anatomy per tier (towers manned,
  gates guarded, furnished interiors, faction loot). **New tiers generate in newly
  generated chunks only.**
- **Dialogue**: right-click a non-hostile soldier. **5,443 authored player options**
  (21,758 soldier response lines) surfaced four at a time by context — faction,
  standing, disposition, rank, location, time of day, your recent deeds. Soldiers
  remember: a per-player event ledger (attacks, kills, sabotage, trades, gifts,
  contracts, tributes) with slow-fading violence and faster-fading kindness drives a
  disposition band from *vengeful* to *devoted*. Kill a faction's soldiers and every
  member greets you accordingly until you claw back through apology tributes,
  penance work orders, trade, or fighting at their side — and a betrayal after
  friendship cuts twice as deep. Every set now includes visibly labeled friendly,
  neutral, threatening, and leave choices. Individual soldiers are patient,
  professional, proud, or volatile; friendly lines build goodwill and cool tempers,
  while repeated threats can make that specific soldier attack. Soldiers stop and
  face the player for the entire conversation. One hundred substantive subjects
  branch through ten sequential layers, with friendly, probing, and threatening
  approaches at every layer; the transcript-oriented screen keeps the current
  reply, topic, and depth visible and lets the player change subject cleanly. Long
  choices receive dynamically sized, wrapped buttons; long exchanges wrap inside a
  mouse-wheel-scrollable transcript instead of being clipped.
  Helping one faction echoes against its enemies.
  Quartermasters trade through dialogue (standing-gated stock, prices scale with
  standing *and* disposition); officers offer work orders (eliminate / supply /
  recon) with matrix consequences. Chat-fallback UI via
  `config/warfront-client.json`.
- **World hostility**: faction soldiers and vanilla hostile mobs acquire one another
  on sight, so patrols defend themselves from monsters without player intervention.
- **Fixes**: soldier "ghost limb" rendering fixed at the root (player-model overlay
  parts now posed); item model transform audit clean. (No M1911 exists in this mod
  or the managed pack — the reported gun-rendering bug was this arm issue.)
- **License**: relicensed **LGPL-3.0-or-later** (from the template's CC0) to carry
  the imported LGPL structures correctly. Full verification log: `VERIFY.md`;
  screenshots: `screenshots/v0.2.0/`.

## What's in Phase 1

- **Factions as data**: `data/warfront/warfront_factions/*.json` — id, name, colors, and the full doctrine weight block (aggression, casualty tolerance, squad size, flank vectors, ambush/night bias, retreat threshold, tech rate). Add a faction by dropping a JSON file in a datapack; no code.
- **Soldiers**: one entity type, faction-assigned, per-faction uniform textures + faction-dyed gear, ranks as data (one officer per squad). Combat gear scales with faction tech level.
- **Bases**: Vostok and Aegis bases at village-like rarity, 2 smaller Sarab outpost variants generating more often; garrisons patrol, roaming squads spawn in faction territory. Bases include **sandbag stations** — the universal "soldier mans equipment" system that Phase 4 turrets/AA/silos will reuse.
- **Tech progression**: factions accrue points over world time (rate = doctrine `tech_rate`), levels 0–4 gate gear tier, squad size, station manning (level 1+), and template availability. Levels 0–2 are concrete; 3–4 exist in data with empty unlock lists.
- **Hostility**: neutral by default; attacking a faction's soldiers or base infrastructure makes that faction hostile to you (shared faction-wide, persisted, decays back to neutral). Faction-vs-faction relations are a data matrix — hostile patrol encounters fight with visible doctrine differences.
- **Order pipeline**: `Order -> General (template filter + doctrine scoring) -> TacticalTemplate -> executor`. One template ships (`infantry_assault`); orders targeting unloaded chunks abort cleanly via the virtual-resolution stub.

## Debug commands (op)

```
/warfront tech <faction>            view tech level + points
/warfront tech <faction> <0-4>      set level (also refreshes loaded soldiers' gear)
/warfront order <faction> assault <x> <y> <z>   run a faction assault through the pipeline
/warfront standing                  your standing with each faction
/warfront patrol <faction>          force-spawn a roaming squad nearby
```

## Tuning (all data)

- Doctrine weights: `src/main/resources/data/warfront/warfront_factions/{vostok,aegis,sarab}.json`
- Tech curve + gates: `data/warfront/warfront_config/tech.json` (thresholds, points/day, gear & squad size per level, unlocks)
- Standing thresholds/decay/penalties: `data/warfront/warfront_config/standing.json`
- Economy cadence/liquidity/exchange/shocks: `data/warfront/warfront_config/economy.json`
- Relations matrix: `data/warfront/warfront_config/relations.json`
- Tactical templates: `data/warfront/warfront_templates/*.json` (preconditions incl. `min_tech_level`, constraint compatibility, doctrine affinity)

## Architecture (binding for future phases)

1. Factions/doctrines/relations/templates/tech: data, never code.
2. Stations are the universal manned-equipment foundation.
3. All commanded action flows through the order→general→template pipeline, including Phase 3 player orders.
4. Virtual (off-screen) resolution will replace the abort stub; execution stays separated from entity manipulation.
5. Tech level is the universal capability gate; nothing bypasses the points store.

## Development

Fabric Loom, Mojang mappings, JDK 25. `./gradlew build`. Asset/structure pipelines:

- `node tools/retheme-structure.js --batch tools/retheme-batch.json` — rethemes the
  imported Repurposed Structures NBTs into the three faction skins (material maps in
  `tools/retheme-maps/`). Run after touching maps or imports.
- `node tools/gen-base-plans.js` — composes the 9 tier×faction base plates (freehand
  connective tissue + stamped sourced buildings) and the jigsaw sprawl pieces.
- `node tools/gen-textures.js` — soldier uniforms (player-skin recolors), block
  textures, icon.
- `tools/devserver.sh start|stop|log` — headless dev server (rcon 25576/`wartest`,
  carpet fake player keeps 26.2's pause-when-empty at bay).

**v0.2.0 base tiers**: outposts were rebuilt and two new tiers (forward base,
headquarters) were added. All of them generate in **newly generated chunks only** —
already-explored terrain keeps whatever stood there before.

## License

As of v0.2.0 Warfront is licensed **LGPL-3.0-or-later** (previously CC0-1.0, the Fabric template default). The change was made so the mod can import and adapt structure NBTs from LGPL-licensed open-source mods (notably [Repurposed Structures](https://github.com/TelepathicGrunt/RepurposedStructures) by TelepathicGrunt, whose license permits reuse as long as the using mod is open source). See `CREDITS.md` for full attribution of every imported asset and `structures/SOURCES.md` for per-file provenance.
