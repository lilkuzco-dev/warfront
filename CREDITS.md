# Credits

## Citizen Skins
- **Author**: isaiah658
- **Source**: https://opengameart.org/content/minetest-skins-pack-1
- **License**: CC0 1.0
- **What Warfront uses**: five skins from Minetest Skins Pack 1, assigned to the
  miner, farmer, builder, trader, and laborer professions. The source pack uses the
  legacy 64x32 Minecraft layout; `tools/gen-textures.js` converts the selected skins
  to the modern 64x64 layout. Per-file provenance is recorded in
  `tools/assets/citizen-skins/README.md`.
- Credit is not required by CC0, but is retained with thanks to isaiah658.

## Repurposed Structures
- **Author**: TelepathicGrunt
- **Source**: https://github.com/TelepathicGrunt/RepurposedStructures (branch `26.2-MDG`,
  commit `0f155e849284339ba3fc8164f460eee169311ea5`)
- **License**: LGPL-3.0 (https://www.gnu.org/licenses/lgpl-3.0.html)
- **What Warfront uses**: 32 structure template NBTs (watchtowers, towers, tents,
  training targets, village houses, stronghold rooms, a fortress room), imported
  unmodified into `src/main/resources/data/warfront/structures/imported/` and adapted
  (palette retheme, jigsaw rewiring, bed→bunk substitution) into the faction base
  structures under `data/warfront/structure/`. Full per-file provenance:
  `structures/SOURCES.md`.
- Warfront itself is LGPL-3.0-or-later; the adapted structure files remain under
  LGPL-3.0. Thank you TelepathicGrunt for keeping quality structure work open.

## Dracula Skin
- **Author**: TenPlus1
- **Source**: https://github.com/mightyjoe781/simple_skins/blob/4efdee9cb1b3e5ac3c1591303669b62275b9c49d/textures/character_1995.png
- **Metadata**: https://github.com/mightyjoe781/simple_skins/blob/4efdee9cb1b3e5ac3c1591303669b62275b9c49d/meta/character_1995.txt
- **License**: CC BY-SA 4.0
- **What Warfront uses**: the legacy 64x32 `Vampire` skin, converted to Minecraft's
  modern 64x64 layout by `tools/gen-textures.js` and used for Count Dracula.

## Mojang / Minecraft
- Template pools reference vanilla (`minecraft:`) jigsaw pieces by resource location
  with Warfront processor lists — no vanilla assets are redistributed in this repo.
