# Structure sources manifest

Every imported NBT, its origin, license, and intended use. Untouched originals live in
`src/main/resources/data/warfront/structures/imported/<source>/<original path>`; faction
variants produced by `tools/retheme-structure.js` land in
`src/main/resources/data/warfront/structure/<faction>/` and are then composed into base
plates or referenced from template pools by `tools/gen-base-plans.js`.

**Provenance rule (enforced)**: every enclosed building in a Warfront base originates
from a file listed here or from a referenced `minecraft:` template. Freehand geometry is
limited to connective tissue: perimeter walls, gates, paths, trenches, sandbag lines,
flat pads, courtyards, flagpoles.

## Repurposed Structures — TelepathicGrunt
- Repo: https://github.com/TelepathicGrunt/RepurposedStructures — branch `26.2-MDG`,
  commit `0f155e849284339ba3fc8164f460eee169311ea5` (2026-06-18)
- License: **LGPL-3.0** (LICENSE file in repo). Warfront is LGPL-3.0-or-later; originals
  ship unmodified alongside the adapted copies, satisfying source availability.
- Original path prefix: `common/src/main/resources/data/repurposed_structures/structure/`

| Imported file | Role in Warfront |
|---|---|
| `outposts/oak/watchtower.nbt` | Watchtower (all factions; corner towers, gate overwatch) |
| `outposts/basalt/tower.nbt` | Heavy tower — HQ comms tower / Vostok keep |
| `outposts/badlands/tent1.nbt` | Field tent (Sarab camps, gate-side bivouacs) |
| `outposts/badlands/tent2.nbt` | Field tent variant |
| `outposts/badlands/targets.nbt` | Training-yard target (HQ training yard) |
| `strongholds/end/prison.nbt` | HQ bunker cell block |
| `strongholds/end/storage_crossing.nbt` | HQ bunker storage room |
| `fortresses/jungle/center_room.nbt` | HQ inner command bunker core |
| `villages/badlands/houses/medium_house_1.nbt` | Vostok barracks (bed-rich) |
| `villages/badlands/houses/medium_house_4.nbt` | Vostok barracks variant |
| `villages/badlands/houses/small_house_2.nbt` | Vostok small bunkroom / supply cache |
| `villages/badlands/houses/armorer.nbt` | Vostok armory |
| `villages/badlands/houses/weaponsmith.nbt` | Vostok armory variant |
| `villages/badlands/houses/tool_smith.nbt` | Vostok quartermaster post |
| `villages/badlands/houses/library_1.nbt` | Vostok command post |
| `villages/badlands/houses/saloon.nbt` | Vostok mess hall |
| `villages/mountains/houses/medium_house_1.nbt` | Aegis barracks |
| `villages/mountains/houses/medium_house_2.nbt` | Aegis barracks variant |
| `villages/mountains/houses/small_house_2.nbt` | Aegis bunkroom / supply cache |
| `villages/mountains/houses/armorer_house_1.nbt` | Aegis armory |
| `villages/mountains/houses/weaponsmith_1.nbt` | Aegis armory variant |
| `villages/mountains/houses/tool_smith_1.nbt` | Aegis quartermaster post |
| `villages/mountains/houses/library_1.nbt` | Aegis command post |
| `villages/mountains/houses/butcher_shop_1.nbt` | Aegis mess / stores |
| `villages/oak/houses/medium_house_1.nbt` | Sarab barracks |
| `villages/oak/houses/medium_house_2.nbt` | Sarab barracks variant |
| `villages/oak/houses/small_house_2.nbt` | Sarab bunkroom |
| `villages/oak/houses/small_house_5.nbt` | Sarab supply cache |
| `villages/oak/houses/armorer_house_1.nbt` | Sarab armory |
| `villages/oak/houses/weaponsmith_1.nbt` | Sarab armory variant |
| `villages/oak/houses/tool_smith_1.nbt` | Sarab quartermaster post |
| `villages/oak/houses/library_1.nbt` | Sarab command post |

Geometry sets are deliberately distinct per faction (badlands set → Vostok, mountains
set → Aegis, oak set → Sarab) so bases differ in silhouette as well as palette.

## Vanilla piece referencing (zero copy)
Template pools may reference `minecraft:` structure resource locations directly with
Warfront processor lists (retheme-at-generation). No vanilla NBT is copied into this
repo. Referenced pieces are listed in the template pool JSONs themselves.

## Not imported (license refusals — see RESEARCH.md)
CTOV (CC BY-NC-ND), When Dungeons Arise (assets ARR), Towns & Towers (no-modification
terms): pattern reference only, zero assets in this repo.
