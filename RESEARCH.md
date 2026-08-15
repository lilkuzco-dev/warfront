# Warfront v0.2.0 research — structure sourcing & licenses

All licenses below were read from the actual LICENSE files in each repository on
2026-08-15 (GitHub API / cloned source), not from wiki summaries. Verdicts are about
**importing/adapting structure NBT assets**, which is stricter than depending on code.

## License table

| Source | Repo | License (verified) | Import NBTs? | Verdict |
|---|---|---|---|---|
| Repurposed Structures | github.com/TelepathicGrunt/RepurposedStructures | **LGPL-3.0** (LICENSE file in repo; branch `26.2-MDG` @ `0f155e849284339ba3fc8164f460eee169311ea5`) | **YES** | Primary quarry. LGPL permits copying + modification with attribution, license preservation, and source availability. Warfront relicensed LGPL-3.0-or-later (Stage 0) so imported/rethemed NBTs carry their license coherently. Repo has an exact-match `26.2-MDG` branch — no cross-version NBT drift. |
| Vanilla Minecraft | — | Mojang EULA (no redistribution of assets) | No copy — **reference only** | Jigsaw pools may reference `minecraft:` template resource locations directly (`single_pool_element` with our own processor list) — retheme-at-generation with **zero copying**. Always safe. Used liberally (pillager outpost pieces, bastion rooms). |
| CTOV (ChoiceTheorem's Overhauled Villages) | github.com/ChoiceTheorem/ChoiceTheorem-s-overhauled-village | **CC BY-NC-ND 4.0** (LICENSE in repo — *NoDerivatives*, not the BY-NC-SA the plan assumed) | **NO** | ND forbids sharing adapted material; retheming = adaptation. Demoted to **pattern/quality reference only** (how a large fortified jigsaw compound is pooled/sized). Nothing imported → no CREDITS entry needed. |
| When Dungeons Arise | github.com/Aureljz/WhenDungeonsArise--Fabric | Split: **MIT for CODE exclusively, All Rights Reserved for ASSETS AND DATA exclusively** (LICENSE.README.txt) | **NO** | Structure NBTs are assets → ARR. Patterns only. |
| Towns & Towers | github.com/Cristelknight/Towns-and-Towers | Custom terms + **CC BY-NC-ND 4.0** ("do not make any changes to the mod aside from config files") | **NO** | Explicitly forbids modification. Patterns only. |

**Net sourcing strategy**: Repurposed Structures NBTs (copied + palette-rethemed via
`tools/retheme-structure.js`, originals preserved, per-file provenance in
`structures/SOURCES.md`, attribution in `CREDITS.md`) + vanilla piece referencing with
faction processor lists. No CTOV/WDA/T&T assets ship in Warfront.

## Repurposed Structures quarry survey (branch 26.2-MDG, 3,162 NBTs)

Directory: `common/src/main/resources/data/repurposed_structures/structure/`

| Category | Count | Military use |
|---|---|---|
| `outposts/<biome>/` | 205 | watchtower / watchtower_aged / tower, tents, targets (training), cages, logs, base/feature plates — per-biome variants (oak, badlands, basalt, ocean, …). Core watchtower + camp material. |
| `villages/<biome>/houses/` | 1350 | medium/small houses → barracks; armorer/weaponsmith/tool_smith → armory & quartermaster; library → command post; meeting_point → courtyard centerpiece; saloon → mess; stable, temple. Streets/terminators as pool-pattern reference. |
| `fortresses/jungle/` | 17 | corridor network (straight/turn/4-way, inside/outside), center_room, balcony, terminators — fortress-room skeleton for HQs. |
| `strongholds/end/` | 47 | prison, libraries, storage_crossing, corridors, stairs — underground command-bunker rooms and Sarab tunnel segments. |
| `bastions/underground/` | 167 | ramparts, walls, bridge pieces, treasure rooms — heavy fortification material (Vostok). |
| `mineshafts/` | 242 | tunnel segments — Sarab sub-compound connective tunnels. |
| `mansions/` | 597 | large room modules — HQ halls if needed. |

## Vanilla jigsaw deep-dive (how villages get big; applied in Stage 3)

- **Branch depth**: `worldgen/structure` JSON `"size"` (0–20) is jigsaw recursion depth.
  Villages use 6. Our tiers: outpost 4, forward base 6, HQ 7.
- **Growth pattern**: villages grow from `town_centers` → `streets` pool (high weight,
  many street shapes) → each street piece carries many child jigsaw blocks pointing at
  `houses` pools → `terminators` fallback pool caps open street ends when depth/budget
  runs out. Big footprint = streets-as-arteries, buildings-as-leaves, terminators-as-caps.
  Warfront equivalent: perimeter+path arteries, sourced buildings as leaves, wall-cap
  terminators.
- **Guaranteed anatomy**: vanilla guarantees the town center only; everything else is
  weighted luck. To make anatomy *mandatory* (Stage 3 requirement) the start piece and
  artery pieces must carry **dedicated jigsaw blocks with pool_alias or per-slot pools**
  (one slot → one single-entry pool = guaranteed barracks/armory/etc.), not shared
  weighted pools. `pool_aliases` (1.20.3+) lets one artery template serve all factions
  by rebinding slot pools per structure JSON.
- **Size budget**: `max_distance_from_center` (≤128) clips pieces extending beyond the
  radius; HQ needs the full 128 with arteries kept compact.
- **Terrain**: `"terrain_adaptation"`: `beard_thin` (villages/outposts — platform under
  pieces), `beard_box` (full box, for sunken/bunker parts), `bury`, `encapsulate`.
  Outpost/FB: beard_thin; HQ: beard_thin; Sarab tunnels: none (sealed pieces) or bury.
- **Spacing**: `worldgen/structure_set` `spacing`/`separation`/`salt` in chunks;
  `placement.frequency` thins occurrences. HQ target ~1500–2000 blocks ≈ spacing 112,
  separation 48, distinct salts per faction so faction bases don't collide.
- **`spawn_overrides": {}` is mandatory** on our structure JSONs (Phase 1 lesson —
  omitting it crashes/blocks datapack load on 26.2).
- **Jigsaw connection**: pieces connect where `minecraft:jigsaw` block entities agree on
  `name`/`target` and pool; `joint` rollable/aligned. RS pieces keep their jigsaw blocks
  in the NBT — the retheme tool must **preserve jigsaw block entities** (their
  `pool`/`name`/`target` NBT strings get remapped to `warfront:` pools by the tool).
- **Processors**: `worldgen/processor_list` JSONs; `minecraft:rule` with
  `random_block_match` (integrity/rot for Sarab), `block_match`, output block+NBT. Also
  usable inline on any `single_pool_element` — including elements referencing
  `minecraft:` templates (the zero-copy vanilla retheme path).

## Dialogue research (patterns only; Stage 4)

- **Conditions**: modeled on vanilla predicate JSON style — flat objects of
  `field: value|[values]|{min,max}` semantics, all-must-match, arrays = any-of. Keeps
  the corpus greppable and mirrors loot/advancement predicate conventions.
- **UI reference**: vanilla merchant screen (compact list + header readouts) and book
  screen. Custom compact screen with 4 option buttons + Leave; chat-clickable fallback
  (`ClickEvent.RUN_COMMAND` on `/warfront talk <n>`) behind a client config flag.
  No open-source dialogue mod imported or required.
- All display text via lang keys (translation-ready; JSON stores keys).
