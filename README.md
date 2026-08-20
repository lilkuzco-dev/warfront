# Warfront

Modern military factions for Minecraft 26.2 (Fabric). Three doctrine-driven factions (Vostok: mass and attrition; Aegis: combined-arms precision; Sarab: asymmetric ambush) with soldiers, bases, tech progression, and the order pipeline every future phase builds on.

Requires Fabric Loader 0.19.3+ and Fabric API for 26.2.

## v0.4.9 — supplied grand castles and Dracula

- A normal grand-castle roll now chooses one of three supplied builds at equal 1/3
  weight: **Celestial Castle** for Aegis, **Cinderella's Armored Castle** for Vostok,
  or **Mug Castle** for Sarab. Each imported castle core keeps its source block states
  and block-entity details, including creator credit signs.
- Four large working Warfront towns surround every normal castle. Together with the
  castle center they form five economic districts populated by 240 citizens, while
  the faction's persistent 64–96-soldier garrison serves a named, armored king.
  Source containers receive the faction's rich castle loot table.
- A separate very rare 501×501 Dracula landmark uses the authorized **Creepy
  Blackstone Castle | Halloween Edition** build by Nevas Buildings. Its isolated
  ruined plateau, dense blackstone architecture, decorated rooms, and original
  entities replace the former generated rectangular estate. Sixteen bounded hidden
  caches use Warfront loot, and Count Dracula stands at the southern approach. He has
  boss-grade health and damage, life steal and night regeneration, burns in direct
  sun, is harmed by water, and takes seven times damage from wooden swords. Full build
  and skin attribution is documented in `CREDITS.md`.
- Dracula remains isolated: it is generated from its own rare structure set and does
  not receive the four towns or the normal-castle civilian economy.
- A dedicated client game test builds the full Aegis castle and captures an aerial
  proof frame; it is kept opt-in so normal builds and releases stay lightweight.
- Worldgen bounds use Minecraft 26.2's terrain-adaptation-safe jigsaw radius and
  valid 16-chunk exclusion cap; 0.4.7 was rolled back before it entered service.

## v0.4.6 — guarded working cities

- Hitting a citizen immediately makes the attacker hostile to that faction and alerts
  every same-faction city guard inside the settlement. Guards break off dialogue and
  pursue the attacker like iron golems defending villagers.
- Every newly generated town, city, and metropolis guarantees a distinct mine office,
  farmhouse and worked field, builder workshop, trader exchange, laborer warehouse,
  and sealed city vault. Profession AI prioritizes the matching workstations instead
  of gathering at arbitrary containers.
- Citizens no longer drop the abstract city-economy inventory projected onto their
  records. Death loot is limited to at most eight items they physically produced plus
  occasional pocket change; the city's treasury is represented by its fortified vault.
- The larger functional town plates have matching jigsaw distance bounds, and every
  guaranteed job site remains within the runtime work-search radius.

## v0.4.5 — visible work, tangible stock

- Miners break exposed city ore with visible crack progress at vanilla survival speed
  for their held pickaxe, while farmers harvest mature crops and replant them.
- Mined drops, crop drops, and builder-crafted planks enter a separate tangible-output
  ledger. Abstract economy simulation can no longer invent merchant stock.
- A citizen's villager-style market now offers only the exact items and quantities that
  citizen physically produced, in exchange for emeralds. Empty workers plainly report
  that they have nothing ready for sale.
- Builder crafting now follows the vanilla one-log-to-four-planks recipe.

## v0.4.4 — profession skins stay profession skins

- Citizen rendering now assigns the synchronized miner, farmer, builder, trader, or
  laborer texture to both the entity render texture and Minecraft 26.2's avatar skin
  state. This prevents the player-model pipeline from falling back to Steve.
- The five CC0 profession textures are mapped exhaustively by profession rather than
  assembled from an unchecked string at render time.

## v0.4.3 — citizens go to work

- Existing citizens migrate away from the former shared city-center work anchor;
  new citizens retain their own seeded home/work position.
- Farmers spread across real mature crop rows, miners seek local ore or forge sites,
  builders use city workshops, and traders/laborers route among storage and market
  blocks. When a specialist site is absent they run a distributed local work route
  while the persistent economy continues its profession production.
- Worksite searches cover a full city district, rotate workers across matching sites,
  and abandon unreachable targets instead of crowding indefinitely against a tower.

## v0.4.2 — grounded populations and profession markets

- Citizens and base soldiers now seed on the settlement's ground band instead of
  selecting rooftops; saved roof spawns are corrected after upgrade, and roaming or
  assault squads choose nearby open ground rather than heightmap spikes.
- Citizens use five job-specific CC0 skins (miner, farmer, builder, trader, laborer)
  instead of the generic Steve texture.
- Right-clicking a citizen opens Minecraft's native villager-style merchant screen,
  with offers backed by that citizen's real inventory, prices, and purse.

## v0.4.1 — dev-server launcher hardening

- `tools/devserver.sh` now records the **listening JVM's** PID in `run/server.pid`, not
  the gradlew wrapper's. Those are different processes, which is why a leftover server
  used to be unprovable: the recorded PID could never match the one holding the port.
- On startup it refuses to guess. A stale pidfile is cleaned; a port held by a server it
  can prove is its own is reclaimed by signalling that exact PID; a port held by anything
  else stops the launch with the holder's PID, uptime and command line — it never
  silently moves ports or kills a process it cannot prove it owns.
- `stop` waits for the server process to exit rather than for the port to close, so the
  kill fallback can no longer interrupt a world save. `status` reports what holds the port.

## v0.4.0 — settlements that grow, and an economy that is not a closed loop

- **Settlements come in three sizes.** Towns (10 citizens) are common, cities (28)
  uncommon, and **metropolises rare and genuinely large — 300 citizens and a garrison
  of 50–95**, laid out as four quarters of housing around a central plaza with its own
  towers. A metropolis is about 0.9% of settlements, roughly one per four thousand
  blocks. All three share the 272-block separation floor, so even the biggest cannot
  overlap its neighbour.
- **Citizens populate like a village.** A settlement has children, bounded by the roofs
  it actually has (each `warfront:bunk` houses a household) and rationed against the
  food it is holding. Build more barracks and the town grows; burn them down, blight
  the fields or strip the granary in a raid and it stops. A newborn draws a stake so it
  starts above the liquidity floor — drawn from the treasury and levied from the
  richest, never minted.
- **The economy is no longer zero-sum.** Cities send expeditions out of town, and the
  citizens who go are genuinely away — not standing in the market square. Parties
  **mine emeralds** and **trade** (wealth from outside the model entirely), **forage**
  (goods), and **raid other settlements** (wealth and grain taken off a hostile
  faction's city and carried home, with casualties on both sides). What comes home
  lands in the town treasury and is paid out to its people, so a city that mines well
  gets visibly richer and a city that is raided gets poorer.
- Every flow is booked, so `conserved=true` still means what it says:
  `/warfront city economy` now reports held wealth, the treasury, and what has been
  carried in and out. `/warfront city expeditions` lists the parties currently away,
  and `inspect` shows population against housing.
- `tools/econ-selftest.sh` runs the economy's invariants — births, deaths, mining,
  looting, foraging and the snapshot round trip — standalone in about a second, with
  no Minecraft in the loop.

## v0.3.1 — the dialogue rewrite

- **Nothing is written by substitution any more.** The corpus had reached its size by
  slotting a topic noun into shared sentence frames — three frames alone produced 300
  player questions — and the seams showed as broken English: *"ammunition reserves is
  measured"*, *"mountain passes … curling around it"*, *"the disarming rival militias"*.
  Field chatter and every deep-branch refusal are now authored per subject.
- **Each faction has a rhetorical grammar, not just a vocabulary.** Vostok speaks in
  parataxis and litotes, Aegis in anaphora, asyndeton and meiosis, Sarab in the
  balanced antithesis of proverb literature. `dialogue/VOICE.md` documents the devices.
- **The validator prevents relapse.** `tools/validate-dialogue.js` fails the build on
  firearms vocabulary and on any sentence frame reused past a small threshold.
- Corpus is deliberately smaller and entirely readable: 1,863 player options and
  4,418 response lines, with zero firearms violations and no frame repeated more than
  four times.

## v0.3.0 — the settlement update

- **Faction bases never generate on top of each other.** Every base, headquarters and
  city now draws from a single `warfront:bases` structure set instead of nine
  independent ones. `spacing`/`separation` only constrain placements *within* a set,
  so nine sets with nine salts were mutually blind — two factions could and did land
  in the same chunk. One set with `spacing 20 / separation 13` gives a hard floor of
  **224 blocks** between any two warfront structures, cross-faction included.
  `node tools/verify-base-spacing.js` replays the placement maths offline and fails
  the build if any pair falls under 200 blocks.
- **Bases and cities are populated.** Discovering a structure now seeds a civilian
  settlement alongside its garrison — 4 at an outpost, 8 at a forward base, 14 at a
  headquarters, 28 in a city — placed on real standable ground around the start
  piece. Counts are datapack knobs in `warfront_config/population.json`.
- **Cities generate in the world.** `warfront:<faction>_city` is a jigsaw settlement
  built from the same license-cleared rethemed buildings as the bases, with civilian
  grammar instead of military: an open road cross, a market plaza with a well and
  stalls, worked farmland, lit streets, and a light guard rather than a garrison.
  Cities sprawl into housing and farm districts along their roads.
- **Emeralds are the currency.** The economy's abstract money is denominated in
  emeralds at a datapack rate. Citizens sell only stock they physically produced;
  their larger holdings and the city treasury remain economic state rather than
  turning every resident into a loot chest. Player trade is booked in the conservation
  ledger, so `conserved=true` still means what it says. `/warfront city economy`
  reports city wealth, per-lot prices and net player trade in emeralds.

## Civilization update — Phases 1–2

- Cities persist as data independently of world generation, and — since v0.3.0 — are
  also generated by it. Their citizens cycle
  through exactly one representation: embodied inside 48 blocks, local-abstract in
  loaded chunks, and virtual in unloaded chunks.
- Five citizen professions ship in Phase 1: miner, farmer, builder, trader, and
  laborer. Embodied citizens pathfind, work at profession buildings and world resources,
  and flee monsters. Damaging one alerts the settlement's same-faction soldiers
  immediately; those guards are assigned to the city rather than duplicated into a
  separate military system.
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
- **Dialogue**: right-click a non-hostile soldier. **2,743 authored player options**
  (4,658 soldier response lines) surfaced four at a time by context — faction,
  standing, disposition, rank, location, time of day, your recent deeds. Soldiers
  remember: a per-player event ledger (attacks, kills, sabotage, trades, gifts,
  contracts, tributes) with slow-fading violence and faster-fading kindness drives a
  disposition band from *vengeful* to *devoted*. Kill a faction's soldiers and every
  member greets you accordingly until you claw back through apology tributes,
  penance work orders, trade, or fighting at their side — and a betrayal after
  friendship cuts twice as deep. Choices are written naturally rather than marked
  with tone symbols or mechanic explanations. Individual soldiers are patient,
  professional, proud, or volatile; friendly lines build goodwill and cool tempers,
  while repeated threats can make that specific soldier attack. Soldiers stop and
  face the player for the entire conversation. Ten substantive, hand-authored
  subjects branch through ten sequential layers, with friendly, probing, and
  threatening approaches at every layer. Standing is a silent disclosure gate:
  hostile soldiers refuse details, neutral soldiers give a guarded concrete fact,
  and trusted soldiers add faction-specific history. Recent fights, monster
  attacks, locations, contracts, and other immediate events take priority over
  long-form lore. The transcript-oriented screen shows only the soldier's identity,
  current subject, and exchange; standing, disposition, personality, mood, and
  branch depth remain hidden. The player can still change subject cleanly. Long choices
  receive dynamically sized, wrapped buttons; long exchanges wrap inside a
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
