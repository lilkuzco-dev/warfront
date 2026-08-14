#!/usr/bin/env node
// Generates the warfront base structure templates (.nbt) from voxel plans:
// Vostok base, Aegis base, and two Sarab outpost variants — walls, barracks,
// quartermaster, faction banners, sandbag stations, and embedded garrison
// soldiers (PersistenceRequired, faction/rank NBT). Zero dependencies.
// Usage: node tools/gen-structures.js
const zlib = require("node:zlib");
const fs = require("node:fs");
const path = require("node:path");

const DATA_VERSION = 4903;

// ---------- minimal NBT writer (big-endian, gzipped) ----------
function utf(str) {
	const s = Buffer.from(str, "utf8");
	return Buffer.concat([Buffer.from([s.length >> 8, s.length & 0xff]), s]);
}
function i32(v) {
	const b = Buffer.alloc(4);
	b.writeInt32BE(v);
	return b;
}
function f64(v) {
	const b = Buffer.alloc(8);
	b.writeDoubleBE(v);
	return b;
}
function f32(v) {
	const b = Buffer.alloc(4);
	b.writeFloatBE(v);
	return b;
}
// value encoders return {type, payload}
const T = {
	byte: (v) => ({ type: 1, payload: Buffer.from([v & 0xff]) }),
	int: (v) => ({ type: 3, payload: i32(v) }),
	float: (v) => ({ type: 5, payload: f32(v) }),
	double: (v) => ({ type: 6, payload: f64(v) }),
	string: (v) => ({ type: 8, payload: utf(v) }),
	list: (items, itemType) => ({
		type: 9,
		payload: Buffer.concat([Buffer.from([itemType]), i32(items.length), ...items.map((it) => it.payload)]),
	}),
	compound: (obj) => ({
		type: 10,
		payload: Buffer.concat([
			...Object.entries(obj).map(([name, val]) => Buffer.concat([Buffer.from([val.type]), utf(name), val.payload])),
			Buffer.from([0]),
		]),
	}),
};
function writeNbt(rootCompound, file) {
	const body = Buffer.concat([Buffer.from([10]), utf(""), rootCompound.payload]);
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, zlib.gzipSync(body));
	console.log(`wrote ${path.relative(process.cwd(), file)}`);
}

// ---------- voxel builder ----------
class Build {
	constructor() {
		this.blocks = new Map(); // "x,y,z" -> {name, props, nbt}
		this.entities = [];
	}
	set(x, y, z, name, props = null, nbt = null) {
		this.blocks.set(`${x},${y},${z}`, { name, props, nbt });
	}
	fill(x1, y1, z1, x2, y2, z2, name, props = null) {
		for (let x = x1; x <= x2; x++) for (let y = y1; y <= y2; y++) for (let z = z1; z <= z2; z++) this.set(x, y, z, name, props);
	}
	walls(x1, y1, z1, x2, y2, z2, name) { // hollow rectangle ring per y layer
		for (let y = y1; y <= y2; y++) {
			for (let x = x1; x <= x2; x++) {
				this.set(x, y, z1, name);
				this.set(x, y, z2, name);
			}
			for (let z = z1; z <= z2; z++) {
				this.set(x1, y, z, name);
				this.set(x2, y, z, name);
			}
		}
	}
	soldier(x, y, z, faction, rank) {
		this.entities.push({ x: x + 0.5, y, z: z + 0.5, faction, rank });
	}
	emit(file) {
		const palette = [];
		const paletteIndex = new Map();
		const keyOf = (b) => b.name + "|" + JSON.stringify(b.props ?? {});
		const blockTags = [];
		let maxX = 0, maxY = 0, maxZ = 0;
		for (const [key, block] of this.blocks) {
			const [x, y, z] = key.split(",").map(Number);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
			const pk = keyOf(block);
			if (!paletteIndex.has(pk)) {
				paletteIndex.set(pk, palette.length);
				const entry = { Name: T.string(block.name) };
				if (block.props) {
					entry.Properties = T.compound(Object.fromEntries(Object.entries(block.props).map(([k, v]) => [k, T.string(String(v))])));
				}
				palette.push(T.compound(entry));
			}
			const record = {
				pos: T.list([T.int(x), T.int(y), T.int(z)], 3),
				state: T.int(paletteIndex.get(pk)),
			};
			if (block.nbt) {
				record.nbt = block.nbt;
			}
			blockTags.push(T.compound(record));
		}
		const entityTags = this.entities.map((e) => T.compound({
			pos: T.list([T.double(e.x), T.double(e.y), T.double(e.z)], 6),
			blockPos: T.list([T.int(Math.floor(e.x)), T.int(Math.floor(e.y)), T.int(Math.floor(e.z))], 3),
			nbt: T.compound({
				id: T.string("warfront:soldier"),
				warfront_faction: T.string(e.faction),
				warfront_rank: T.string(e.rank),
				PersistenceRequired: T.byte(1),
			}),
		}));
		writeNbt(T.compound({
			size: T.list([T.int(maxX + 1), T.int(maxY + 1), T.int(maxZ + 1)], 3),
			DataVersion: T.int(DATA_VERSION),
			palette: T.list(palette, 10),
			blocks: T.list(blockTags, 10),
			entities: T.list(entityTags, 10),
		}), file);
	}
}

const bannerNbt = () => T.compound({ id: T.string("minecraft:banner") });
const STONE = "minecraft:stone_bricks";

function flagpole(b, x, z, bannerBlock) {
	b.set(x, 1, z, "minecraft:cobblestone");
	for (let y = 2; y <= 6; y++) b.set(x, y, z, "minecraft:oak_fence");
	b.set(x, 7, z, bannerBlock, { rotation: "0" }, bannerNbt());
}

function barracks(b, x1, z1, x2, z2, plank, doorZ) {
	b.walls(x1, 1, z1, x2, 3, z2, plank);
	b.fill(x1, 4, z1, x2, 4, z2, "minecraft:spruce_slab", { type: "bottom" });
	b.fill(x1 + 1, 1, z1 + 1, x2 - 1, 3, z2 - 1, "minecraft:air");
	b.set(x1, 2, Math.floor((z1 + z2) / 2), "minecraft:iron_bars");
	b.set(x2, 2, Math.floor((z1 + z2) / 2), "minecraft:iron_bars");
	b.set(Math.floor((x1 + x2) / 2), 1, doorZ, "minecraft:air");
	b.set(Math.floor((x1 + x2) / 2), 2, doorZ, "minecraft:air");
	// bunks
	b.set(x1 + 1, 1, z1 + 1, "minecraft:red_bed", { part: "foot", facing: "east" });
	b.set(x1 + 2, 1, z1 + 1, "minecraft:red_bed", { part: "head", facing: "east" });
}

function quartermaster(b, x1, z1, x2, z2, plank) {
	b.walls(x1, 1, z1, x2, 3, z2, plank);
	b.fill(x1, 4, z1, x2, 4, z2, "minecraft:oak_slab", { type: "bottom" });
	b.fill(x1 + 1, 1, z1 + 1, x2 - 1, 3, z2 - 1, "minecraft:air");
	b.set(Math.floor((x1 + x2) / 2), 1, z2, "minecraft:air");
	b.set(Math.floor((x1 + x2) / 2), 2, z2, "minecraft:air");
	b.set(x1 + 1, 1, z1 + 1, "minecraft:barrel", { facing: "up" });
	b.set(x1 + 2, 1, z1 + 1, "minecraft:barrel", { facing: "up" });
	b.set(x2 - 1, 1, z1 + 1, "minecraft:crafting_table");
	b.set(x2 - 1, 1, z2 - 1, "minecraft:smithing_table");
}

// ---------- Vostok base: 25x25 walled compound, big garrison ----------
function vostokBase() {
	const b = new Build();
	b.fill(0, 0, 0, 24, 0, 24, "minecraft:gravel");
	b.walls(0, 1, 0, 24, 3, 24, "minecraft:cobblestone");
	for (let x = 0; x <= 24; x += 6) {
		b.set(x, 4, 0, STONE);
		b.set(x, 4, 24, STONE);
		b.set(0, 4, x, STONE);
		b.set(24, 4, x, STONE);
	}
	// south gate
	b.fill(10, 1, 24, 14, 3, 24, "minecraft:air");
	b.set(9, 1, 23, "warfront:sandbag_station");
	b.set(15, 1, 23, "warfront:sandbag_station");
	barracks(b, 2, 2, 10, 8, "minecraft:spruce_planks", 8);
	quartermaster(b, 16, 2, 22, 7, "minecraft:spruce_planks");
	flagpole(b, 12, 12, "minecraft:red_banner");
	// garrison: 5 soldiers + 1 officer (doctrine squads of 6-8 come from patrols/orders)
	b.soldier(6, 1, 12, "vostok", "soldier");
	b.soldier(12, 1, 6, "vostok", "soldier");
	b.soldier(18, 1, 12, "vostok", "soldier");
	b.soldier(12, 1, 18, "vostok", "soldier");
	b.soldier(8, 1, 20, "vostok", "soldier");
	b.soldier(13, 1, 13, "vostok", "officer");
	return b;
}

// ---------- Aegis base: 21x21, cleaner masonry, smaller precision garrison ----------
function aegisBase() {
	const b = new Build();
	b.fill(0, 0, 0, 20, 0, 20, "minecraft:polished_andesite");
	b.walls(0, 1, 0, 20, 3, 20, STONE);
	for (const [cx, cz] of [[0, 0], [0, 20], [20, 0], [20, 20]]) {
		b.fill(cx, 1, cz, cx, 5, cz, "minecraft:quartz_pillar", { axis: "y" });
	}
	b.fill(8, 1, 20, 12, 3, 20, "minecraft:air"); // south gate
	b.set(7, 1, 19, "warfront:sandbag_station");
	b.set(13, 1, 19, "warfront:sandbag_station");
	barracks(b, 2, 2, 8, 7, "minecraft:birch_planks", 7);
	quartermaster(b, 13, 2, 18, 6, "minecraft:birch_planks");
	flagpole(b, 10, 11, "minecraft:blue_banner");
	b.soldier(6, 1, 11, "aegis", "soldier");
	b.soldier(14, 1, 11, "aegis", "soldier");
	b.soldier(10, 1, 15, "aegis", "soldier");
	b.soldier(11, 1, 12, "aegis", "officer");
	return b;
}

// ---------- Sarab outposts: small, dispersed, hidden ----------
function sarabOutpost1() {
	const b = new Build();
	b.fill(0, 0, 0, 8, 0, 8, "minecraft:coarse_dirt");
	b.walls(0, 1, 0, 8, 1, 8, "minecraft:cobblestone");
	b.set(4, 1, 8, "minecraft:air"); // entrance
	// tent
	b.fill(1, 1, 1, 3, 1, 3, "minecraft:yellow_wool");
	b.fill(1, 2, 1, 3, 2, 3, "minecraft:air");
	b.set(2, 2, 2, "minecraft:yellow_wool");
	b.set(6, 1, 2, "minecraft:campfire", { lit: "false" });
	b.set(6, 1, 6, "warfront:sandbag_station");
	b.set(2, 1, 6, "minecraft:barrel", { facing: "up" });
	b.set(4, 1, 4, "minecraft:yellow_banner", { rotation: "0" }, bannerNbt());
	b.soldier(3, 1, 5, "sarab", "soldier");
	b.soldier(5, 1, 3, "sarab", "soldier");
	b.soldier(5, 1, 6, "sarab", "officer");
	return b;
}

function sarabOutpost2() {
	const b = new Build();
	b.fill(0, 0, 0, 6, 0, 6, "minecraft:dirt_path");
	for (const [x, z] of [[0, 0], [6, 0], [0, 6], [6, 6], [3, 0], [0, 3], [6, 3]]) {
		b.set(x, 1, z, "minecraft:cobblestone");
	}
	b.fill(4, 1, 4, 6, 1, 6, "minecraft:yellow_wool");
	b.fill(4, 2, 4, 6, 2, 6, "minecraft:air");
	b.set(5, 2, 5, "minecraft:yellow_wool");
	b.set(1, 1, 5, "minecraft:campfire", { lit: "false" });
	b.set(1, 1, 1, "warfront:sandbag_station");
	b.set(3, 1, 3, "minecraft:yellow_banner", { rotation: "0" }, bannerNbt());
	b.soldier(2, 1, 4, "sarab", "soldier");
	b.soldier(4, 1, 2, "sarab", "officer");
	return b;
}

const OUT = path.join(__dirname, "..", "src/main/resources/data/warfront/structure");
vostokBase().emit(path.join(OUT, "vostok_base.nbt"));
aegisBase().emit(path.join(OUT, "aegis_base.nbt"));
sarabOutpost1().emit(path.join(OUT, "sarab_outpost_1.nbt"));
sarabOutpost2().emit(path.join(OUT, "sarab_outpost_2.nbt"));
