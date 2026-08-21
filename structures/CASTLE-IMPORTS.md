# Castle import record

The three normal castle slots are weighted 15/15/15 (Dracula 1) in
`data/warfront/worldgen/structure_set/grand_castles.json`.

| Faction slot | Supplied world | Import center | Minimum Y | Source radius |
|---|---|---:|---:|---:|
| Aegis | Celestial Castle | `-88, 240` | 60 | 250 |
| Vostok | Cinderella's Armored Castle | `-905, 1950` | 60 | 250 |
| Sarab | Mug Castle | `104, 40` | 60 | 400 |

**Where these numbers come from — measured, then READ.** The importer's `--scan` mode
reports the mass centroid and densest 25-block bins of *tall* built material
(`--min-y 100` excludes terrain, which only builds rise above), plus a Y histogram of
everything built. The bins locate the candidates; the render
(`tools/render-structure.py`) of the resulting crop is the check that the thing at the
centre is actually the castle — a tall-mass centroid alone centred Vostok's crop on a
cluster of decorative parasol trees east of the real keep, and centred Sarab's on cliff
spires with the keep sliced off at the crop's edge. Numbers chosen by a scan still get
their picture looked at before they are trusted (CLAUDE.md rule 9, applied to imports).
Minimum Y sits just below the measured ground plateau (y≈62 in all three worlds), so
the crop carries its own ground plate but cuts off the source worlds' below-ground
machinery — one of those, a note-block music machine under the Cinderella build,
shipped inside the Vostok castle and became the room the king spawned in.

The source worlds were first converted to Minecraft 26.2 Anvil data with the official
server's `--forceUpgrade`; the upgraded overworld lands in
`<world>/dimensions/minecraft/overworld`, which is the directory the importer wants.

What the importer does beyond the crop, in order:

- copies every non-air block and its state in the crop without palette substitutions,
  preserving non-inventory block-entity data and removing downloaded inventories;
- clears four outer district boxes and stamps the faction's working-town shells
  (`structures/working-town-shells/`) at a fixed 72-block inset from each edge;
- finds the **throne chamber** — the highest roofed room near the castle's centre
  (5x5 floor preferred, 3x3 accepted) — and stamps the **specified castle interior**
  beneath it: the faction's own rethemed `grand_library`, `scientific_study`,
  `bunker_storage` junction, and two concealed `secret_passage` vault segments (retheme
  provenance in SOURCES.md; jigsaw connector blocks are filled with the piece's base
  material), joined by carved doorways and reached by a stone-clad ladder shaft beside
  the throne;
- assigns loot so **every container answers**: vault chests carry
  `warfront:castle/hidden_vault`, 24 evenly spread containers carry the faction's rich
  table, and everything else carries `warfront:castle/common` (never `minecraft:empty`);
- embeds the garrison: the king on his throne with two royal-guard officers, a
  twelve-soldier rampart watch on a ring a fifth of the way out from centre, two cellar
  sentries at the vault passage, and eight guards per working town — 49 persistent
  soldiers in all.

The exact import commands, with `U` set to the directory containing the three upgraded
worlds:

```sh
export NODE_OPTIONS=--max-old-space-size=16384
node tools/import-grand-castle.js --keep-ground \
  --world "$U/celestial/dimensions/minecraft/overworld" \
  --base structures/working-town-shells/aegis.nbt \
  --out src/main/resources/data/warfront/structure/aegis/castle.nbt --faction aegis \
  --center-x -88 --center-z 240 --min-y 60 --source-radius 250

node tools/import-grand-castle.js --keep-ground \
  --world "$U/cinderella/dimensions/minecraft/overworld" \
  --base structures/working-town-shells/vostok.nbt \
  --out src/main/resources/data/warfront/structure/vostok/castle.nbt --faction vostok \
  --center-x -905 --center-z 1950 --min-y 60 --source-radius 250

node tools/import-grand-castle.js --keep-ground \
  --world "$U/mug/dimensions/minecraft/overworld" \
  --base structures/working-town-shells/sarab.nbt \
  --out src/main/resources/data/warfront/structure/sarab/castle.nbt --faction sarab \
  --center-x 104 --center-z 40 --min-y 60 --source-radius 400
```

Runtime base registration expands the embedded seed to the faction's deterministic
castle garrison target and creates 240 citizens across the castle center and four town
economies — seeded by `BaseManager.onCastleBuilt` only after `CastleBuilder` finishes
the paste, never at discovery. Dracula uses its own structure set and import path
(`tools/import-dracula-castle.js`), so it receives none of the town districts, castle
population, or working economy.
