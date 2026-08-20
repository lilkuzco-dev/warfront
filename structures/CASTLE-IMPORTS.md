# Castle import record

The three normal castle slots are equally weighted in
`data/warfront/worldgen/structure_set/grand_castles.json`:

| Faction slot | Supplied world | Import center | Minimum Y | Source radius |
|---|---|---:|---:|---:|
| Aegis | Celestial Castle | `-100, 235` | 67 | 170 |
| Vostok | Cinderella's Armored Castle | `-925, 1940` | 64 | 170 |
| Sarab | Mug Castle | `90, 420` | 73 | 170 |

The source worlds were first converted to Minecraft 26.2 Anvil data with the official
server's `--forceUpgrade`. `tools/import-grand-castle.js` then copied every non-air
block and its state in the selected source crop without palette substitutions. It also
preserved non-inventory block-entity data, removed downloaded container inventories,
assigned the faction castle loot table to a bounded, evenly distributed selection of
containers, and composed the four Warfront working-town districts around the castle.

The exact import commands, after setting `UPGRADED_ROOT` to the directory containing
the three upgraded overworld directories, are:

```sh
NODE_OPTIONS=--max-old-space-size=6144 node tools/import-grand-castle.js --keep-ground \
  --world "$UPGRADED_ROOT/celestial" --base structures/working-town-shells/aegis.nbt \
  --out src/main/resources/data/warfront/structure/aegis/castle.nbt --faction aegis \
  --center-x -100 --center-z 235 --min-y 67 --source-radius 170 --king-y 68

NODE_OPTIONS=--max-old-space-size=6144 node tools/import-grand-castle.js --keep-ground \
  --world "$UPGRADED_ROOT/cinderella" --base structures/working-town-shells/vostok.nbt \
  --out src/main/resources/data/warfront/structure/vostok/castle.nbt --faction vostok \
  --center-x -925 --center-z 1940 --min-y 64 --source-radius 170 --king-y 70

NODE_OPTIONS=--max-old-space-size=6144 node tools/import-grand-castle.js --keep-ground \
  --world "$UPGRADED_ROOT/mug" --base structures/working-town-shells/sarab.nbt \
  --out src/main/resources/data/warfront/structure/sarab/castle.nbt --faction sarab \
  --center-x 90 --center-z 420 --min-y 73 --source-radius 170 --king-y 74
```

The normal imports seed 32 persistent faction soldiers (including eight officers) and
one persistent king in each structure. Runtime base registration expands that seed to
the faction's deterministic castle garrison target and creates 240 citizens across the
castle center and four town economies. Dracula uses its own structure set and import
path, so it does not receive these town districts.
