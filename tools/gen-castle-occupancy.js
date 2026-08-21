#!/usr/bin/env node
// Emits a castle template's per-column occupancy sidecar, which is what lets
// CastleBuilder blend a castle into terrain the way a Woodland Mansion blends:
// vanilla structures displace exactly their own volume because their templates carry
// air records; the imported castles carry none (the importer skips air), so without
// this map the builder could only flatten the whole square — reported from play as
// "clearing a massive space and just pasting".
//
// Format (gzipped NBT): { width: int, min_y: int[width*width] } where index = x*width+z
// and -1 means "the template places nothing in this column — leave the terrain alone".
//
// Run after ANY castle import:
//   node tools/gen-castle-occupancy.js src/main/resources/data/warfront/structure/<f>/castle.nbt
const fs = require("node:fs");
const { TAG, N, parse, write } = require("./nbt");

const input = process.argv[2];
if (!input) {
	console.error("usage: gen-castle-occupancy.js <castle.nbt> [out.nbt]");
	process.exit(2);
}
const output = process.argv[3] ?? input.replace(/\.nbt$/, "_occupancy.nbt");

const root = parse(fs.readFileSync(input)).root.v;
const [sizeX, , sizeZ] = root.size.v.items.map((i) => i.v ?? i);
const width = Math.max(sizeX, sizeZ);
const minY = new Array(width * width).fill(-1);
for (const record of root.blocks.v.items) {
	const [x, y, z] = record.pos.v.items.map((i) => i.v ?? i);
	const index = x * width + z;
	if (minY[index] === -1 || y < minY[index]) minY[index] = y;
}
const occupied = minY.filter((y) => y >= 0).length;

fs.writeFileSync(output, write(N.compound({
	width: N.int(width),
	min_y: { t: TAG.intArray, v: minY },
})));
console.log(`wrote ${output}: ${width}x${width}, ${occupied} occupied columns `
	+ `(${(occupied * 100 / (width * width)).toFixed(1)}%)`);
