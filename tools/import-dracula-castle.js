#!/usr/bin/env node
// Imports the authorized Nevas Buildings Creepy Blackstone Castle world as the
// Warfront Dracula structure. The source world is Minecraft 1.16 Anvil data.
//
// Usage:
//   unzip CreepyBlackstoneCastle.zip -d /tmp/creepy-castle
//   node tools/import-dracula-castle.js \
//     --world /tmp/creepy-castle/CreepyBlackstoneCastle_By_NevasBuildings \
//     --out src/main/resources/data/warfront/structure/dracula/castle.nbt
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const { TAG, N, parse, write } = require("./nbt");

const args = process.argv.slice(2);
function arg(flag, fallback = null) {
	const i = args.indexOf(flag);
	return i >= 0 ? args[i + 1] : fallback;
}

const world = arg("--world");
const output = arg("--out");
if (!world || !output) {
	console.error("usage: import-dracula-castle.js --world <extracted-world> --out <castle.nbt>");
	process.exit(2);
}

// The 501-square crop keeps the full island/plateau silhouette while matching
// Warfront's monumental-castle footprint. Y=55 removes deep WorldPainter stone.
const MIN_X = Number(arg("--min-x", "-250"));
const MAX_X = Number(arg("--max-x", "250"));
const MIN_Y = Number(arg("--min-y", "55"));
const MIN_Z = Number(arg("--min-z", "-250"));
const MAX_Z = Number(arg("--max-z", "250"));

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

function paletteState(entry) {
	const result = { Name: N.string(entry.Name.v) };
	if (entry.Properties) result.Properties = entry.Properties;
	return result;
}

function stateAt(longs, bits, index) {
	const mask = (1n << BigInt(bits)) - 1n;
	// Since 1.16, values are padded so no entry straddles two longs.
	const perLong = Math.floor(64 / bits);
	const longIndex = Math.floor(index / perLong);
	const shift = BigInt((index % perLong) * bits);
	return Number((BigInt.asUintN(64, longs[longIndex]) >> shift) & mask);
}

const palette = [];
const paletteIndexes = new Map();
const blocks = [];
const blockAt = new Map();
const blockEntities = new Map();
const sourceEntities = [];
let sourceDataVersion = 2578;

function paletteIndex(entry) {
	const props = entry.Properties
		? Object.fromEntries(Object.entries(entry.Properties.v).map(([k, v]) => [k, v.v]))
		: {};
	const key = `${entry.Name.v}|${JSON.stringify(props)}`;
	if (!paletteIndexes.has(key)) {
		paletteIndexes.set(key, palette.length);
		palette.push(paletteState(entry));
	}
	return paletteIndexes.get(key);
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
		if (root.DataVersion) sourceDataVersion = root.DataVersion.v;
		const level = (root.Level ?? { v: root }).v;
		const chunkX = level.xPos.v;
		const chunkZ = level.zPos.v;

		for (const tile of level.TileEntities?.v.items ?? []) {
			const x = tile.x?.v, y = tile.y?.v, z = tile.z?.v;
			if (Number.isInteger(x) && Number.isInteger(y) && Number.isInteger(z)) {
				blockEntities.set(`${x},${y},${z}`, tile);
			}
		}
		for (const entity of level.Entities?.v.items ?? []) sourceEntities.push(entity);

		for (const section of level.Sections?.v.items ?? []) {
			if (!section.Palette || !section.BlockStates) continue;
			const sectionY = section.Y.v;
			const sourcePalette = section.Palette.v.items;
			const bits = Math.max(4, Math.ceil(Math.log2(sourcePalette.length)));
			const longs = section.BlockStates.v;
			for (let i = 0; i < 4096; i++) {
				const entry = sourcePalette[stateAt(longs, bits, i)];
				if (!entry) continue;
				const name = entry.Name.v;
				if (name === "minecraft:air" || name === "minecraft:cave_air"
						|| name === "minecraft:void_air" || name === "minecraft:water") continue;
				const x = chunkX * 16 + (i & 15);
				const z = chunkZ * 16 + ((i >> 4) & 15);
				const y = sectionY * 16 + ((i >> 8) & 15);
				if (x < MIN_X || x > MAX_X || z < MIN_Z || z > MAX_Z || y < MIN_Y) continue;
				const relative = [x - MIN_X, y - MIN_Y, z - MIN_Z];
				const record = { pos: N.list(TAG.int, relative), state: N.int(paletteIndex(entry)) };
				blocks.push(record);
				blockAt.set(`${x},${y},${z}`, { record, name });
			}
		}
	}
}

// Preserve decorative block entities, but strip absolute coordinates and item
// payloads. A bounded selection of containers becomes Warfront loot instead of
// duplicating the downloaded world's inventory contents.
const lootCandidates = [];
for (const [key, { record, name }] of blockAt) {
	const tile = blockEntities.get(key);
	if (tile) {
		const cleaned = { ...tile };
		delete cleaned.x; delete cleaned.y; delete cleaned.z;
		delete cleaned.Items; delete cleaned.LootTable; delete cleaned.LootTableSeed;
		record.nbt = N.compound(cleaned);
	}
	if (name === "minecraft:chest" || name === "minecraft:barrel") lootCandidates.push({ key, record, name });
}
lootCandidates.sort((a, b) => a.key.localeCompare(b.key));
const lootIndexes = new Set();
const lootCount = Math.min(16, lootCandidates.length);
for (let i = 0; i < lootCount; i++) {
	lootIndexes.add(Math.round(i * (lootCandidates.length - 1) / Math.max(1, lootCount - 1)));
}
for (let i = 0; i < lootCandidates.length; i++) {
	const { record, name } = lootCandidates[i];
	const table = lootIndexes.has(i) ? "warfront:castle/dracula" : "minecraft:empty";
	record.nbt = N.compound({
		id: N.string(name),
		LootTable: N.string(table),
	});
}

const entities = [];
for (const entity of sourceEntities) {
	const pos = entity.Pos?.v.items;
	if (!pos || pos.length !== 3) continue;
	const [x, y, z] = pos;
	if (x < MIN_X || x > MAX_X + 1 || z < MIN_Z || z > MAX_Z + 1 || y < MIN_Y) continue;
	const id = entity.id?.v ?? "";
	if (id === "minecraft:player" || id === "minecraft:item") continue;
	// Vanilla structure placement transforms Pos but not a hanging entity's separate
	// attachment block. Item frames and paintings therefore retain an invalid absolute
	// world coordinate whenever worldgen places this template away from the source.
	// Keep all free-standing decorative entities and omit only this unsupported class.
	if (id === "minecraft:item_frame" || id === "minecraft:glow_item_frame"
			|| id === "minecraft:painting") continue;
	const cleaned = { ...entity };
	delete cleaned.Pos; delete cleaned.UUID; delete cleaned.UUIDMost; delete cleaned.UUIDLeast;
	delete cleaned.WorldUUIDMost; delete cleaned.WorldUUIDLeast;
	delete cleaned.Motion; delete cleaned.FallDistance; delete cleaned.PortalCooldown;
	const relative = [x - MIN_X, y - MIN_Y, z - MIN_Z];
	entities.push({
		pos: N.list(TAG.double, relative),
		blockPos: N.list(TAG.int, relative.map(Math.floor)),
		nbt: N.compound(cleaned),
	});
}

// Count Dracula stands near the original world's southern approach/spawn.
const draculaPos = [-20 - MIN_X + 0.5, 66 - MIN_Y, -88 - MIN_Z + 0.5];
entities.push({
	pos: N.list(TAG.double, draculaPos),
	blockPos: N.list(TAG.int, draculaPos.map(Math.floor)),
	nbt: N.compound({
		id: N.string("warfront:dracula"),
		PersistenceRequired: N.byte(1),
	}),
});

const structure = N.compound({
	size: N.list(TAG.int, [MAX_X - MIN_X + 1, 134 - MIN_Y, MAX_Z - MIN_Z + 1]),
	DataVersion: N.int(sourceDataVersion),
	palette: { t: TAG.list, v: { itemType: TAG.compound, items: palette } },
	blocks: { t: TAG.list, v: { itemType: TAG.compound, items: blocks } },
	entities: { t: TAG.list, v: { itemType: TAG.compound, items: entities } },
});
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, write(structure));
console.log(`wrote ${output}`);
console.log(`size ${MAX_X - MIN_X + 1}x${134 - MIN_Y}x${MAX_Z - MIN_Z + 1}`);
console.log(`blocks ${blocks.length}, palette ${palette.length}, entities ${entities.length}, loot containers ${lootIndexes.size}`);
