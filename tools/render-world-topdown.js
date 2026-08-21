#!/usr/bin/env node
// Renders a top-down map of a world region straight from its Anvil files — no client,
// no camera, no chunk streaming. Born from three failed attempts to photograph
// naturally-generated castles through the client-gametest harness, whose chunk
// streaming at remote coordinates produced frames of fog and sky. The region files
// cannot fail to mesh.
//
// Emits a PPM (pipe through tools/ppm2png.py for a PNG):
//   node tools/render-world-topdown.js --world <dir> --min-x A --max-x B --min-z C --max-z D --out map.ppm
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const { parse } = require("./nbt");

const args = process.argv.slice(2);
function arg(flag, fallback = null) {
	const i = args.indexOf(flag);
	return i >= 0 ? args[i + 1] : fallback;
}
const world = arg("--world");
const minX = Number(arg("--min-x")), maxX = Number(arg("--max-x"));
const minZ = Number(arg("--min-z")), maxZ = Number(arg("--max-z"));
const out = arg("--out", "topdown.ppm");
if (!world || [minX, maxX, minZ, maxZ].some(Number.isNaN)) {
	console.error("usage: render-world-topdown.js --world <dir> --min-x A --max-x B --min-z C --max-z D [--out f.ppm]");
	process.exit(2);
}

const PALETTE = {
	"minecraft:water": [46, 88, 200], "minecraft:grass_block": [98, 158, 68],
	"minecraft:dirt": [134, 96, 67], "minecraft:stone": [125, 125, 125],
	"minecraft:sand": [219, 207, 163], "minecraft:snow": [240, 240, 245],
	"minecraft:snow_block": [235, 235, 242], "minecraft:gravel": [136, 126, 126],
	"minecraft:oak_leaves": [55, 105, 35], "minecraft:birch_leaves": [90, 130, 60],
	"minecraft:jungle_leaves": [45, 120, 40], "minecraft:spruce_leaves": [40, 90, 45],
};
function colorOf(name) {
	if (PALETTE[name]) return PALETTE[name];
	let h = 0;
	for (const c of name) h = (h * 31 + c.charCodeAt(0)) >>> 0;
	return [80 + (h % 150), 80 + ((h >> 8) % 150), 80 + ((h >> 16) % 150)];
}

function chunkPayload(region, slot) {
	const location = region.readUInt32BE(slot * 4);
	if (!location) return null;
	const offset = (location >>> 8) * 4096;
	const length = region.readUInt32BE(offset);
	if (length < 2 || offset + 4 + length > region.length) return null;
	const compression = region[offset + 4];
	const body = region.subarray(offset + 5, offset + 4 + length);
	if (compression === 1) return zlib.gunzipSync(body);
	if (compression === 2) return zlib.inflateSync(body);
	if (compression === 3) return body;
	return null;
}
function stateAt(longs, bits, index) {
	if (!longs || longs.length === 0) return 0;
	const mask = (1n << BigInt(bits)) - 1n;
	const perLong = Math.floor(64 / bits);
	const longIndex = Math.floor(index / perLong);
	const shift = BigInt((index % perLong) * bits);
	return Number((BigInt.asUintN(64, longs[longIndex]) >> shift) & mask);
}

const width = maxX - minX + 1;
const height = maxZ - minZ + 1;
const topY = new Int16Array(width * height).fill(-32768);
const topName = new Array(width * height);

for (let rx = Math.floor(minX / 512); rx <= Math.floor(maxX / 512); rx++) {
	for (let rz = Math.floor(minZ / 512); rz <= Math.floor(maxZ / 512); rz++) {
		const file = path.join(world, "region", `r.${rx}.${rz}.mca`);
		if (!fs.existsSync(file)) continue;
		const region = fs.readFileSync(file);
		for (let slot = 0; slot < 1024; slot++) {
			const raw = chunkPayload(region, slot);
			if (!raw) continue;
			let root;
			try { root = parse(raw).root.v; } catch { continue; }
			const level = (root.Level ?? { v: root }).v;
			const chunkX = level.xPos.v, chunkZ = level.zPos.v;
			if (chunkX * 16 > maxX || chunkX * 16 + 15 < minX
					|| chunkZ * 16 > maxZ || chunkZ * 16 + 15 < minZ) continue;
			for (const section of level.sections?.v.items ?? []) {
				const states = section.block_states?.v;
				const sourcePalette = states?.palette?.v.items;
				if (!sourcePalette) continue;
				const longs = states?.data?.v;
				const bits = Math.max(4, Math.ceil(Math.log2(sourcePalette.length)));
				const sectionY = section.Y.v;
				for (let i = 0; i < 4096; i++) {
					const entry = sourcePalette.length === 1 ? sourcePalette[0]
						: sourcePalette[stateAt(longs, bits, i)];
					if (!entry) continue;
					const name = entry.Name.v;
					if (name === "minecraft:air" || name === "minecraft:cave_air") continue;
					const worldX = chunkX * 16 + (i & 15);
					const worldZ = chunkZ * 16 + ((i >> 4) & 15);
					if (worldX < minX || worldX > maxX || worldZ < minZ || worldZ > maxZ) continue;
					const worldY = sectionY * 16 + ((i >> 8) & 15);
					const index = (worldZ - minZ) * width + (worldX - minX);
					if (worldY > topY[index]) {
						topY[index] = worldY;
						topName[index] = name;
					}
				}
			}
		}
	}
}

const pixels = Buffer.alloc(width * height * 3);
for (let index = 0; index < width * height; index++) {
	const name = topName[index];
	if (!name) continue;
	let [r, g, b] = colorOf(name);
	// simple height shading so relief reads
	const shade = Math.max(0.55, Math.min(1.25, 0.55 + (topY[index] - 40) / 120));
	pixels[index * 3] = Math.min(255, r * shade);
	pixels[index * 3 + 1] = Math.min(255, g * shade);
	pixels[index * 3 + 2] = Math.min(255, b * shade);
}
fs.writeFileSync(out, Buffer.concat([Buffer.from(`P6\n${width} ${height}\n255\n`), pixels]));
console.log(`wrote ${out} (${width}x${height})`);
