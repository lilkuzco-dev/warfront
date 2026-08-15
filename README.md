# Warfront

Modern military factions for Minecraft 26.2 (Fabric) — Phase 1 skeleton. Three doctrine-driven factions (Vostok: mass and attrition; Aegis: combined-arms precision; Sarab: asymmetric ambush) with soldiers, bases, tech progression, and the order pipeline every future phase builds on.

Requires Fabric Loader 0.19.3+ and Fabric API for 26.2.

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
- Relations matrix: `data/warfront/warfront_config/relations.json`
- Tactical templates: `data/warfront/warfront_templates/*.json` (preconditions incl. `min_tech_level`, constraint compatibility, doctrine affinity)

## Architecture (binding for future phases)

1. Factions/doctrines/relations/templates/tech: data, never code.
2. Stations are the universal manned-equipment foundation.
3. All commanded action flows through the order→general→template pipeline, including Phase 3 player orders.
4. Virtual (off-screen) resolution will replace the abort stub; execution stays separated from entity manipulation.
5. Tech level is the universal capability gate; nothing bypasses the points store.

## Development

Fabric Loom, Mojang mappings, JDK 25. `./gradlew build`; structure templates regenerate via `node tools/gen-structures.js`, uniform/block textures via `node tools/gen-textures.js` (soldier skins are recolors of the vanilla player skin — clothing regions only).

## License

As of v0.2.0 Warfront is licensed **LGPL-3.0-or-later** (previously CC0-1.0, the Fabric template default). The change was made so the mod can import and adapt structure NBTs from LGPL-licensed open-source mods (notably [Repurposed Structures](https://github.com/TelepathicGrunt/RepurposedStructures) by TelepathicGrunt, whose license permits reuse as long as the using mod is open source). See `CREDITS.md` for full attribution of every imported asset and `structures/SOURCES.md` for per-file provenance.
