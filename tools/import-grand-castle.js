#!/usr/bin/env node
// Imports an upgraded Java world crop as one of Warfront's three normal castles,
// then adds the faction's four existing working-town districts around the central
// castle geometry. Old worlds must first be upgraded with Minecraft's --forceUpgrade.
//
// Usage:
//   node tools/import-grand-castle.js --world /tmp/upgraded-world \
//     --base structures/working-town-shells/aegis.nbt \
//     --out .../aegis/castle.nbt --faction aegis \
//     --center-x -100 --center-z 235 --min-y 67 --king-y 68
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const { TAG, N, parse, write } = require("./nbt");

const args = process.argv.slice(2);
function arg(flag, fallback = null) {
	const i = args.indexOf(flag);
	return i >= 0 ? args[i + 1] : fallback;
}
function numberArg(flag, fallback = null) {
	const value = arg(flag, fallback);
	return value === null ? null : Number(value);
}

const world = arg("--world");
const baseFile = arg("--base");
const output = arg("--out");
const faction = arg("--faction");
const centerX = numberArg("--center-x");
const centerZ = numberArg("--center-z");
const minY = numberArg("--min-y");
const sourceRadius = numberArg("--source-radius", "170");
const scanOnly = args.includes("--scan");
const kingWorld = [numberArg("--king-x", String(centerX)), numberArg("--king-y"),
	numberArg("--king-z", String(centerZ))];
if (!world || !baseFile || !output || !faction || centerX === null || centerZ === null
		|| minY === null || kingWorld[1] === null) {
	console.error("missing required --world/--base/--out/--faction/--center-x/--center-z/--min-y/--king-y");
	process.exit(2);
}

const SIZE = 501;
const HALF = 250;
const minX = centerX - HALF, maxX = centerX + HALF;
const minZ = centerZ - HALF, maxZ = centerZ + HALF;
const sourceMinX = centerX - sourceRadius, sourceMaxX = centerX + sourceRadius;
const sourceMinZ = centerZ - sourceRadius, sourceMaxZ = centerZ + sourceRadius;
const sourceOffset = HALF - sourceRadius;
const townCenters = [[250, 72], [428, 250], [250, 428], [72, 250]];
const inTown = (x, z) => townCenters.some(([cx, cz]) => Math.abs(x - cx) <= 34 && Math.abs(z - cz) <= 34);
const inRoad = (x, z) => (Math.abs(x - 250) <= 3 && (z <= 145 || z >= 355))
	|| (Math.abs(z - 250) <= 3 && (x <= 145 || x >= 355));
const NATURAL_GROUND = new Set([
	"minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:bedrock",
	"minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:gravel",
	"minecraft:sand", "minecraft:red_sand", "minecraft:clay", "minecraft:coal_ore",
	"minecraft:iron_ore", "minecraft:gold_ore", "minecraft:redstone_ore",
	"minecraft:lapis_ore", "minecraft:diamond_ore", "minecraft:emerald_ore",
	"minecraft:copper_ore", "minecraft:deepslate", "minecraft:tuff",
]);
if (args.includes("--keep-stone")) {
	for (const name of ["minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite"])
		NATURAL_GROUND.delete(name);
}
if (args.includes("--keep-ground")) NATURAL_GROUND.clear();

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
	throw new Error(`unsupported Anvil compression ${compression}`);
}

function stateAt(longs, bits, index) {
	if (!longs || longs.length === 0) return 0;
	const mask = (1n << BigInt(bits)) - 1n;
	const perLong = Math.floor(64 / bits);
	const paddedLength = Math.ceil(4096 / perLong);
	if (longs.length === paddedLength) {
		const longIndex = Math.floor(index / perLong);
		const shift = BigInt((index % perLong) * bits);
		return Number((BigInt.asUintN(64, longs[longIndex]) >> shift) & mask);
	}
	const bitIndex = BigInt(index * bits);
	const longIndex = Number(bitIndex >> 6n);
	const shift = bitIndex & 63n;
	let value = (BigInt.asUintN(64, longs[longIndex]) >> shift) & mask;
	if (shift + BigInt(bits) > 64n) {
		value |= (BigInt.asUintN(64, longs[longIndex + 1]) << (64n - shift)) & mask;
	}
	return Number(value);
}

const palette = [];
const paletteIndexes = new Map();
const plan = new Map();
const blockEntities = new Map();
const scanBins = new Map();
const scanBounds = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
let dataVersion = 4903;

function paletteKey(entry) {
	const props = entry.Properties
		? Object.fromEntries(Object.entries(entry.Properties.v).map(([k, v]) => [k, v.v]))
		: {};
	return `${entry.Name.v}|${JSON.stringify(props)}`;
}
function ensurePalette(entry) {
	const key = paletteKey(entry);
	if (!paletteIndexes.has(key)) {
		paletteIndexes.set(key, palette.length);
		palette.push(entry);
	}
	return paletteIndexes.get(key);
}
function put(x, y, z, entry, nbt = null) {
	if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || y < 0) return;
	plan.set(`${x},${y},${z}`, { x, y, z, state: ensurePalette(entry), name: entry.Name.v, nbt });
}

const regionDir = path.join(world, "region");
for (const filename of fs.readdirSync(regionDir).filter((name) => name.endsWith(".mca")).sort()) {
	const region = fs.readFileSync(path.join(regionDir, filename));
	for (let slot = 0; slot < 1024; slot++) {
		let raw;
		try { raw = chunkPayload(region, slot); } catch (error) {
			console.warn(`${filename} slot ${slot}: ${error.message}`);
			continue;
		}
		if (!raw) continue;
		let root;
		try { root = parse(raw).root.v; } catch { continue; }
		dataVersion = Math.max(dataVersion, root.DataVersion?.v ?? 0);
		const level = (root.Level ?? { v: root }).v;
		const chunkX = level.xPos.v, chunkZ = level.zPos.v;
		const tiles = level.block_entities?.v.items ?? level.TileEntities?.v.items ?? [];
		for (const tile of tiles) {
			const x = tile.x?.v, y = tile.y?.v, z = tile.z?.v;
			if (Number.isInteger(x) && Number.isInteger(y) && Number.isInteger(z)) {
				blockEntities.set(`${x},${y},${z}`, tile);
			}
		}
		const sections = level.sections?.v.items ?? level.Sections?.v.items ?? [];
		for (const section of sections) {
			const states = section.block_states?.v;
			const sourcePalette = states?.palette?.v.items ?? section.Palette?.v.items;
			const longs = states?.data?.v ?? section.BlockStates?.v;
			if (!sourcePalette) continue;
			const bits = Math.max(4, Math.ceil(Math.log2(sourcePalette.length)));
			const sectionY = section.Y.v;
			for (let i = 0; i < 4096; i++) {
				const entry = sourcePalette[stateAt(longs, bits, i)];
				if (!entry) continue;
				const name = entry.Name.v;
				if (name === "minecraft:air" || name === "minecraft:cave_air" || name === "minecraft:void_air") continue;
				const worldX = chunkX * 16 + (i & 15);
				const worldZ = chunkZ * 16 + ((i >> 4) & 15);
				const worldY = sectionY * 16 + ((i >> 8) & 15);
				if (worldX < sourceMinX || worldX > sourceMaxX || worldZ < sourceMinZ
						|| worldZ > sourceMaxZ || worldY < minY || NATURAL_GROUND.has(name)) continue;
				if (scanOnly) {
					const bin = `${Math.floor(worldX / 25) * 25},${Math.floor(worldZ / 25) * 25}`;
					scanBins.set(bin, (scanBins.get(bin) ?? 0) + 1);
					scanBounds[0] = Math.min(scanBounds[0], worldX); scanBounds[1] = Math.min(scanBounds[1], worldY);
					scanBounds[2] = Math.min(scanBounds[2], worldZ); scanBounds[3] = Math.max(scanBounds[3], worldX);
					scanBounds[4] = Math.max(scanBounds[4], worldY); scanBounds[5] = Math.max(scanBounds[5], worldZ);
				} else {
					put(worldX - sourceMinX + sourceOffset, worldY - minY,
							worldZ - sourceMinZ + sourceOffset, entry);
				}
			}
		}
	}
}

if (scanOnly) {
	console.log(`scan bounds ${scanBounds.join(",")}`);
	for (const [bin, count] of [...scanBins].sort((a, b) => b[1] - a[1]).slice(0, 80)) {
		console.log(String(count).padStart(8), bin);
	}
	process.exit(0);
}

// Attach current block-entity data, stripping absolute coordinates and downloaded
// inventories. Container loot is assigned after the working towns are composed.
for (const block of plan.values()) {
	const tile = blockEntities.get(`${block.x - sourceOffset + sourceMinX},${block.y + minY},${block.z - sourceOffset + sourceMinZ}`);
	if (!tile) continue;
	const cleaned = { ...tile };
	delete cleaned.x; delete cleaned.y; delete cleaned.z;
	delete cleaned.Items; delete cleaned.LootTable; delete cleaned.LootTableSeed;
	block.nbt = N.compound(cleaned);
}

// Clear four outer districts and their approach roads, then copy the faction's
// existing working villages verbatim. These intentionally replace only the crop's
// outer district boxes and approach lanes; the central castle build is unaltered.
for (const [key, block] of [...plan]) {
	if (inTown(block.x, block.z) || (inRoad(block.x, block.z) && block.y <= 10)) plan.delete(key);
}
const base = parse(fs.readFileSync(baseFile)).root.v;
const basePalette = base.palette.v.items;
for (const record of base.blocks.v.items) {
	const [x, y, z] = record.pos.v.items;
	if (!inTown(x, z) && !(inRoad(x, z) && y === 0)) continue;
	const entry = basePalette[record.state.v];
	if (entry.Name.v === "minecraft:air" || entry.Name.v === "minecraft:cave_air") continue;
	put(x, y, z, entry, record.nbt ?? null);
}

const containers = [...plan.values()].filter((block) => block.name === "minecraft:chest"
	|| block.name === "minecraft:barrel").sort((a, b) => `${a.x},${a.y},${a.z}`.localeCompare(`${b.x},${b.y},${b.z}`));
const lootIndexes = new Set();
const lootCount = Math.min(24, containers.length);
for (let i = 0; i < lootCount; i++) {
	lootIndexes.add(Math.round(i * (containers.length - 1) / Math.max(1, lootCount - 1)));
}
for (let i = 0; i < containers.length; i++) {
	containers[i].nbt = N.compound({
		id: N.string(containers[i].name),
		LootTable: N.string(lootIndexes.has(i) ? `warfront:castle/${faction}` : "minecraft:empty"),
	});
}

function standableNear(wantX, wantZ, minLevel, radius = 24) {
	for (let r = 0; r <= radius; r++) {
		for (let dx = -r; dx <= r; dx++) for (let dz = -r; dz <= r; dz++) {
			if (r && Math.abs(dx) !== r && Math.abs(dz) !== r) continue;
			const x = wantX + dx, z = wantZ + dz;
			for (let y = Math.max(1, minLevel); y < Math.max(80, minLevel + 40); y++) {
				if (plan.has(`${x},${y - 1},${z}`) && !plan.has(`${x},${y},${z}`)
						&& !plan.has(`${x},${y + 1},${z}`)) return [x, y, z];
			}
		}
	}
	return [wantX, Math.max(1, minLevel), wantZ];
}
function soldierEntity(x, y, z, rank) {
	return {
		pos: N.list(TAG.double, [x + 0.5, y, z + 0.5]),
		blockPos: N.list(TAG.int, [x, y, z]),
		nbt: N.compound({
			id: N.string("warfront:soldier"), warfront_faction: N.string(faction),
			warfront_rank: N.string(rank), PersistenceRequired: N.byte(1),
		}),
	};
}

const entities = [];
const king = standableNear(kingWorld[0] - minX, kingWorld[2] - minZ, kingWorld[1] - minY, 48);
entities.push(soldierEntity(...king, "king"));
const guardOffsets = [[-18, 0], [-12, 7], [-6, -7], [0, 12], [6, -7], [12, 7], [18, 0], [0, -12]];
for (const [townX, townZ] of townCenters) {
	for (let i = 0; i < guardOffsets.length; i++) {
		const [dx, dz] = guardOffsets[i];
		const pos = standableNear(townX + dx, townZ + dz, 1, 8);
		entities.push(soldierEntity(...pos, i < 2 ? "officer" : "soldier"));
	}
}

let maxY = 0;
const blocks = [];
for (const block of plan.values()) {
	maxY = Math.max(maxY, block.y);
	const record = { pos: N.list(TAG.int, [block.x, block.y, block.z]), state: N.int(block.state) };
	if (block.nbt) record.nbt = block.nbt;
	blocks.push(record);
}
blocks.sort((a, b) => {
	const pa = a.pos.v.items, pb = b.pos.v.items;
	return pa[1] - pb[1] || pa[2] - pb[2] || pa[0] - pb[0];
});

const structure = N.compound({
	size: N.list(TAG.int, [SIZE, maxY + 1, SIZE]),
	DataVersion: N.int(dataVersion),
	palette: { t: TAG.list, v: { itemType: TAG.compound, items: palette } },
	blocks: { t: TAG.list, v: { itemType: TAG.compound, items: blocks } },
	entities: { t: TAG.list, v: { itemType: TAG.compound, items: entities } },
});
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, write(structure));
console.log(`wrote ${output}`);
console.log(`faction=${faction} size=${SIZE}x${maxY + 1}x${SIZE} blocks=${blocks.length} palette=${palette.length}`);
console.log(`towns=4 soldiers=${entities.length - 1} kings=1 loot=${lootIndexes.size}`);
