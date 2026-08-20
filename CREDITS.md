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

## Dracula Castle
- **Creator**: Nevas Buildings
- **Builders**: Jatos, Nebaj, and Nasdas
- **Source**: https://www.planetminecraft.com/project/creepy-blackstone-castle-halloween-edition/
- **Permission**: included under the authorization supplied for this Warfront use.
- **What Warfront uses**: the world geometry and free-standing decorative entities from **Creepy
  Blackstone Castle | Halloween Edition**, cropped to Warfront's 501×501 monumental
  structure footprint. Downloaded inventory contents are removed; a bounded set of
  containers receives Warfront loot. Hanging frames and paintings are omitted because
  Minecraft does not transform their separate absolute attachment coordinate when a
  template is placed by worldgen. The reproducible conversion tool is
  `tools/import-dracula-castle.js`.

## Normal Grand Castles

### Celestial Castle (Aegis)
- **Creators**: CloseeDBr / CloseDBr and Chillde
- **Source**: https://www.minecraftmaps.com/45712-celestial-castle
- **Permission**: distributed as a freely accessible server map; the source requires
  its author-credit cards/signs to remain, and Warfront preserves those block-entity
  details in the imported castle core.
- **What Warfront uses**: the supplied castle geometry and block-entity details,
  imported into the Aegis grand-castle slot.

### Cinderella's Armored Castle (Vostok)
- **Creators**: RAMBO 1989 and GR.KOSTAS
- **Source**: https://www.minecraftmaps.com/13219-cinderellas-armored-castle
- **Permission**: the source page grants freestyle use subject to its listed terms.
- **What Warfront uses**: the supplied castle geometry and block-entity details,
  imported into the Vostok grand-castle slot.

### Mug Castle (Sarab)
- **Creators**: BlockMaster3310 and grimreaperdylan
- **Source**: https://www.minecraftmaps.com/27607-mug-castle
- **Permission**: the creators explicitly permit use in a server, minigame, or other
  project when credit is provided.
- **What Warfront uses**: the supplied cliff-city and keep geometry and block-entity
  details, imported into the Sarab grand-castle slot.

The three checked-in faction NBTs are produced by `tools/import-grand-castle.js`.
Downloaded inventory contents are removed, source containers receive bounded Warfront
loot, and four original Warfront working-town districts are composed around each
castle. Reproduction details are in `structures/CASTLE-IMPORTS.md`.

## Mojang / Minecraft
- Template pools reference vanilla (`minecraft:`) jigsaw pieces by resource location
  with Warfront processor lists — no vanilla assets are redistributed in this repo.
