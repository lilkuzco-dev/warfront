#!/usr/bin/env node
// Generates the castle SITE MARKER template: a 16x4x16 structure with no blocks at all.
//
// Why it exists: a jigsaw start piece must fit the structure's generation box, and
// max_distance_from_center is codec-capped at 128 — so the moment the castle template
// pools pointed at the 501-wide imported monoliths (0.4.9), findValidGenerationPoint
// began failing SILENTLY for every castle, everywhere: no StructureStart, so no /locate,
// no discovery, no city ledger, ever again on fresh chunks. Measured in the worldgen
// battery on 2026-08-21: 47 placement candidates, zero starts, from both StructureCheck
// and the real generation pipeline; the "located" castles on the live server were relic
// starts baked into pre-0.4.9 chunks.
//
// So the structure RECORD and the castle BLOCKS are decoupled. The template pools place
// this marker — it has a bounding box (16x4x16, so the start is valid and recorded) and
// zero block records (so it changes nothing in the world). CastleBuilder reads which
// castle structure the marker's start belongs to and pastes the real monument itself.
//
// Usage: node tools/gen-castle-marker.js
const fs = require("node:fs");
const path = require("node:path");
const { TAG, N, write } = require("./nbt");

const out = path.join(__dirname, "../src/main/resources/data/warfront/structure/marker/castle_site.nbt");
const structure = N.compound({
	size: N.list(TAG.int, [16, 4, 16]),
	DataVersion: N.int(4903),
	palette: { t: TAG.list, v: { itemType: TAG.compound, items: [] } },
	blocks: { t: TAG.list, v: { itemType: TAG.compound, items: [] } },
	entities: { t: TAG.list, v: { itemType: TAG.compound, items: [] } },
});
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, write(structure));
console.log(`wrote ${out} (16x4x16, zero blocks)`);
