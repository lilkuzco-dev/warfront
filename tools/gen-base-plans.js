#!/usr/bin/env node
// Base composer (Stage 3). Freehand geometry here is CONNECTIVE TISSUE ONLY —
// perimeter walls, gates, paths, trenches, sandbag lines, flat pads, flagpoles,
// courtyards. Every enclosed building is STAMPED from a license-cleared rethemed
// NBT under data/warfront/structure/<faction>/ (see structures/SOURCES.md); this
// script never freehands a building.
//
// Emits, per faction: outpost_a/outpost_b, forward_base, headquarters plates plus
// the jigsaw-attached sprawl pieces (Vostok trench arms, Sarab path arms +
// satellite sub-camps, tent pads, terminators).
// Usage: node tools/gen-base-plans.js
const fs = require("node:fs");
const path = require("node:path");
const { TAG, N, parse, write } = require("./nbt");

const DATA_VERSION = 4903;
const ROOT = path.join(__dirname, "..", "src/main/resources/data/warfront/structure");

const BANNER = { vostok: "red", aegis: "blue", sarab: "green" };
const GROUND = { vostok: "minecraft:gravel", aegis: "minecraft:andesite", sarab: "minecraft:dirt_path" };
const WALL = { vostok: "minecraft:cobblestone", aegis: "minecraft:smooth_stone", sarab: "minecraft:mud_bricks" };
const WALL_CAP = { vostok: "minecraft:cobbled_deepslate", aegis: "minecraft:polished_andesite", sarab: "minecraft:sandstone" };
const PATH = { vostok: "minecraft:dirt_path", aegis: "minecraft:polished_andesite", sarab: "minecraft:dirt_path" };
const WALL_H = { vostok: 4, aegis: 4, sarab: 2 };

// ---------- rotation helpers ----------
const FACINGS = ["north", "east", "south", "west"];
function rotFacing(f, steps) {
	const i = FACINGS.indexOf(f);
	return i < 0 ? f : FACINGS[(i + steps) % 4];
}
function rotProps(props, steps) {
	if (!props || steps === 0) return props;
	const out = {};
	for (const [k, v] of Object.entries(props)) {
		if (FACINGS.includes(k)) out[rotFacing(k, steps)] = v; // pane/fence/wall side props
		else if (k === "facing") out[k] = rotFacing(v, steps);
		else if (k === "axis" && v !== "y") out[k] = steps % 2 === 0 ? v : (v === "x" ? "z" : "x");
		else if (k === "rotation") out[k] = String((parseInt(v) + steps * 4) % 16);
		else if (k === "orientation") {
			const [a, b] = v.split("_");
			out[k] = `${FACINGS.includes(a) ? rotFacing(a, steps) : a}_${FACINGS.includes(b) ? rotFacing(b, steps) : b}`;
		} else out[k] = v;
	}
	return out;
}

// ---------- plan builder ----------
class Plan {
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
	soldier(x, y, z, faction, rank) {
		this.entities.push({ x: x + 0.5, y, z: z + 0.5, faction, rank });
	}
	jigsaw(x, y, z, orientation, pool, finalState, joint = "rollable", target = "minecraft:building_entrance") {
		this.set(x, y, z, "minecraft:jigsaw", { orientation }, {
			name: "warfront:socket", target, pool,
			final_state: finalState, joint,
		});
	}

	/**
	 * Stamps a rethemed sourced NBT at (ox,oy,oz) rotated so its entrance faces
	 * `face` (n/e/s/w; pieces without an entrance jigsaw treat their unrotated south
	 * as the entrance). Jigsaw blocks bake to final_state; chests/barrels get the
	 * loot table. Returns the rotated footprint {w,h,d}.
	 */
	stamp(file, ox, oy, oz, face, lootTable = null) {
		const { root } = parse(fs.readFileSync(file));
		const r = root.v;
		const [sx, , sz] = r.size.v.items;
		const palette = r.palette.v.items.map((p) => ({
			name: p.Name.v,
			props: p.Properties ? Object.fromEntries(Object.entries(p.Properties.v).map(([k, v]) => [k, v.v])) : null,
		}));
		// entrance facing from the kept building_entrance jigsaw, default south
		let entrance = "south";
		for (const b of r.blocks.v.items) {
			const p = palette[b.state.v];
			if (p.name === "minecraft:jigsaw" && b.nbt?.v.name?.v === "minecraft:building_entrance") {
				entrance = p.props?.orientation?.split("_")[0] ?? "south";
			}
		}
		const want = { n: "north", e: "east", s: "south", w: "west" }[face];
		let steps = (FACINGS.indexOf(want) - FACINGS.indexOf(entrance) + 4) % 4;
		const rot = (x, z) => {
			switch (steps) {
				case 1: return [sz - 1 - z, x];
				case 2: return [sx - 1 - x, sz - 1 - z];
				case 3: return [z, sx - 1 - x];
				default: return [x, z];
			}
		};
		for (const b of r.blocks.v.items) {
			let { name, props } = palette[b.state.v];
			const [bx, by, bz] = b.pos.v.items;
			if (name === "minecraft:structure_void") continue;
			let nbt = null;
			if (name === "minecraft:jigsaw") {
				// bake to final_state (entrances included: plates connect via their own sockets)
				const fsStr = b.nbt?.v.final_state?.v ?? "minecraft:air";
				const m = fsStr.match(/^([a-z0-9_:]+)(?:\[(.*)\])?$/);
				name = m[1] === "minecraft:structure_void" ? "minecraft:air" : m[1];
				props = m[2] ? Object.fromEntries(m[2].split(",").map((kv) => kv.split("="))) : null;
			} else if (b.nbt) {
				nbt = b.nbt; // carry block entity data (e.g. banners)
			}
			if (lootTable && (name === "minecraft:chest" || name === "minecraft:barrel")) {
				nbt = N.compound({ id: N.string(name === "minecraft:chest" ? "minecraft:chest" : "minecraft:barrel"),
					LootTable: N.string(lootTable) });
			}
			const [rx, rz] = rot(bx, bz);
			const tx = ox + rx, ty = oy + by, tz = oz + rz;
			const existing = this.blocks.get(`${tx},${ty},${tz}`);
			if (existing && ty >= 1 && existing.name !== "minecraft:air" && name !== "minecraft:air"
					&& !existing.name.includes("wall") && !existing.name.includes("lantern")) {
				this.collisions = (this.collisions ?? 0) + 1;
			}
			this.set(tx, ty, tz, name, rotProps(props, steps), nbt);
		}
		return steps % 2 === 0 ? { w: sx, d: sz } : { w: sz, d: sx };
	}

	/** Finds the highest floor cell with head-room in a region (tower platforms). */
	topPlatform(x1, z1, x2, z2) {
		let best = null;
		for (let x = x1; x <= x2; x++) for (let z = z1; z <= z2; z++) {
			for (let y = 40; y >= 1; y--) {
				const here = this.blocks.get(`${x},${y},${z}`);
				const above = this.blocks.get(`${x},${y + 1},${z}`);
				const above2 = this.blocks.get(`${x},${y + 2},${z}`);
				if (here && here.name !== "minecraft:air" && (!above || above.name === "minecraft:air")
						&& (!above2 || above2.name === "minecraft:air")) {
					if (!best || y > best.y) best = { x, y: y + 1, z };
					break;
				}
			}
		}
		return best;
	}

	emit(faction, name) {
		const file = path.join(ROOT, faction, `${name}.nbt`);
		const palette = [];
		const paletteIndex = new Map();
		const blockItems = [];
		let maxX = 0, maxY = 0, maxZ = 0;
		const wrapProps = (props) => ({ t: TAG.compound,
			v: Object.fromEntries(Object.entries(props).map(([k, v]) => [k, N.string(String(v))])) });
		for (const [key, block] of this.blocks) {
			const [x, y, z] = key.split(",").map(Number);
			maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
			const pk = block.name + "|" + JSON.stringify(block.props ?? {});
			if (!paletteIndex.has(pk)) {
				paletteIndex.set(pk, palette.length);
				const entry = { Name: N.string(block.name) };
				if (block.props && Object.keys(block.props).length) {
					entry.Properties = wrapProps(block.props);
				}
				palette.push(entry);
			}
			const rec = { pos: N.list(TAG.int, [x, y, z]), state: N.int(paletteIndex.get(pk)) };
			if (block.nbt) {
				// wrapped {t,v} values pass through; plain objects become string compounds
				rec.nbt = typeof block.nbt.t === "number" ? block.nbt
					: N.compound(Object.fromEntries(Object.entries(block.nbt).map(([k, v]) => [k, N.string(String(v))])));
			}
			blockItems.push(rec);
		}
		const entityItems = this.entities.map((e) => ({
			pos: N.list(TAG.double, [e.x, e.y, e.z]),
			blockPos: N.list(TAG.int, [Math.floor(e.x), Math.floor(e.y), Math.floor(e.z)]),
			nbt: N.compound({
				id: N.string("warfront:soldier"),
				warfront_faction: N.string(e.faction),
				warfront_rank: N.string(e.rank),
				PersistenceRequired: N.byte(1),
			}),
		}));
		const root = N.compound({
			size: N.list(TAG.int, [maxX + 1, maxY + 1, maxZ + 1]),
			DataVersion: N.int(DATA_VERSION),
			palette: { t: TAG.list, v: { itemType: TAG.compound, items: palette } },
			blocks: { t: TAG.list, v: { itemType: TAG.compound, items: blockItems } },
			entities: { t: TAG.list, v: { itemType: TAG.compound, items: entityItems } },
		});
		fs.mkdirSync(path.dirname(file), { recursive: true });
		fs.writeFileSync(file, write(root));
		console.log(`wrote ${faction}/${name}.nbt (${maxX + 1}x${maxY + 1}x${maxZ + 1}, ${this.blocks.size} blocks, ${this.entities.length} seeds)`
			+ (this.collisions ? `  STAMP COLLISIONS: ${this.collisions}` : ""));
	}
}

const pieceFile = (faction, piece) => path.join(ROOT, faction, `${piece}.nbt`);
const loot = (faction, tier) => `warfront:base/${faction}_${tier}`;

// ---------- connective tissue ----------
/** Perimeter wall ring with crenellated cap, corner posts, and a south gate. */
function perimeter(p, f, size, gateWidth) {
	const h = WALL_H[f];
	const wall = WALL[f], cap = WALL_CAP[f];
	const max = size - 1;
	// ground skirt (sockets live here) + interior ground
	p.fill(0, 0, 0, max, 0, max, GROUND[f]);
	for (let i = 1; i <= max - 1; i++) {
		for (const [x, z] of [[i, 1], [i, max - 1], [1, i], [max - 1, i]]) {
			for (let y = 1; y <= h; y++) p.set(x, y, z, wall);
			if ((x + z) % 2 === 0) p.set(x, h + 1, z, cap);
		}
	}
	for (const [cx, cz] of [[1, 1], [1, max - 1], [max - 1, 1], [max - 1, max - 1]]) {
		for (let y = 1; y <= h + 1; y++) p.set(cx, y, cz, cap);
		p.set(cx, h + 2, cz, "minecraft:lantern");
	}
	// south gate: gap + flanking sandbag stations + lanterns
	const g1 = Math.floor(size / 2) - Math.floor(gateWidth / 2);
	p.fill(g1, 1, max - 1, g1 + gateWidth - 1, h, max - 1, "minecraft:air");
	p.fill(g1, 0, max - 1, g1 + gateWidth - 1, 0, max, PATH[f]);
	p.set(g1 - 1, 1, max - 2, "warfront:sandbag_station");
	p.set(g1 + gateWidth, 1, max - 2, "warfront:sandbag_station");
	p.set(g1 - 1, h + 1, max - 1, "minecraft:lantern");
	p.set(g1 + gateWidth, h + 1, max - 1, "minecraft:lantern");
	// vanilla-referenced feature sockets flanking the gate (tents/targets/log piles,
	// rethemed at generation by warfront:<f>_features processors — zero copying)
	p.jigsaw(g1 - 5, 0, max, "south_up", `warfront:${f}/features`, GROUND[f], "rollable", "minecraft:feature");
	p.jigsaw(g1 + gateWidth + 4, 0, max, "south_up", `warfront:${f}/features`, GROUND[f], "rollable", "minecraft:feature");
}

function flagpole(p, f, x, z, height = 7) {
	p.set(x, 1, z, WALL_CAP[f]);
	for (let y = 2; y <= height; y++) p.set(x, y, z, "minecraft:oak_fence");
	p.set(x, height + 1, z, `minecraft:${BANNER[f]}_banner`, { rotation: "0" }, { id: "minecraft:banner" });
}

/** HQ banner centerpiece: raised dais with a banner triptych. */
function bannerCenterpiece(p, f, cx, cz) {
	p.fill(cx - 2, 1, cz - 2, cx + 2, 1, cz + 2, WALL_CAP[f]);
	for (const dx of [-2, 0, 2]) {
		p.set(cx + dx, 2, cz - 2, WALL[f]);
		p.set(cx + dx, 3, cz - 2, WALL[f]);
		p.set(cx + dx, 4, cz - 2, `minecraft:${BANNER[f]}_banner`, { rotation: "8" }, { id: "minecraft:banner" });
	}
	flagpole(p, f, cx, cz, 9);
}

function pathCross(p, f, size, courtH) {
	const mid = Math.floor(size / 2);
	p.fill(mid - 1, 0, 2, mid + 1, 0, size - 2, PATH[f]);
	p.fill(2, 0, mid - 1, size - 2, 0, mid + 1, PATH[f]);
	if (courtH) p.fill(mid - courtH, 0, mid - courtH, mid + courtH, 0, mid + courtH, PATH[f]);
}

function floodlight(p, x, z) {
	for (let y = 1; y <= 5; y++) p.set(x, y, z, "minecraft:andesite_wall");
	p.set(x, 6, z, "minecraft:end_rod", { facing: "up" });
}

function heliPad(p, f, x1, z1, size) {
	p.fill(x1, 0, z1, x1 + size - 1, 0, z1 + size - 1, f === "sarab" ? "minecraft:packed_mud" : "minecraft:smooth_stone");
	const c = Math.floor(size / 2);
	for (let i = -2; i <= 2; i++) {
		p.set(x1 + c - 2, 0, z1 + c + i, "minecraft:light_gray_concrete");
		p.set(x1 + c + 2, 0, z1 + c + i, "minecraft:light_gray_concrete");
	}
	for (let i = -1; i <= 1; i++) p.set(x1 + c + i * 0, 0, z1 + c, "minecraft:light_gray_concrete");
	p.set(x1 + c - 1, 0, z1 + c, "minecraft:light_gray_concrete");
	p.set(x1 + c + 1, 0, z1 + c, "minecraft:light_gray_concrete");
}

function storageYard(p, f, x1, z1, lootTable) {
	p.fill(x1, 0, z1, x1 + 6, 0, z1 + 4, "minecraft:smooth_stone");
	for (const [dx, dz] of [[0, 0], [1, 0], [3, 0], [4, 0], [6, 0], [0, 2], [2, 2], [5, 2], [1, 4], [4, 4], [6, 4]]) {
		p.set(x1 + dx, 1, z1 + dz, "minecraft:barrel", { facing: "up" },
			{ id: "minecraft:barrel", LootTable: lootTable });
	}
	p.set(x1 + 3, 1, z1 + 2, "minecraft:chest", { facing: "south" }, { id: "minecraft:chest", LootTable: lootTable });
	p.set(x1 + 2, 1, z1 + 0, "minecraft:hay_block");
	p.set(x1 + 5, 1, z1 + 4, "minecraft:hay_block");
}

function trainingYard(p, f, x1, z1) {
	for (const dx of [0, 5]) {
		p.stamp(pieceFile(f, "targets"), x1 + dx, 1, z1, "s");
	}
	for (let x = x1 - 1; x <= x1 + 9; x += 2) p.set(x, 1, z1 + 8, "minecraft:oak_fence");
}

/** Stamps a tower and mans its top platform with a station + seed guard. */
function tower(p, f, piece, x, z, face) {
	const { w, d } = p.stamp(pieceFile(f, piece), x, 1, z, face, loot(f, "outpost"));
	const top = p.topPlatform(x + 2, z + 2, x + w - 3, z + d - 3);
	if (top) {
		p.set(top.x, top.y, top.z, "warfront:sandbag_station");
		p.soldier(top.x + 1, top.y, top.z, f, "soldier");
	}
	return { w, d };
}

// sockets: horizontal jigsaws in the ground skirt, facing outward
function armSocket(p, f, x, z, orientation, pool) {
	p.jigsaw(x, 0, z, orientation, pool, GROUND[f]);
}

// ---------- faction plates ----------
function outpost(f, variant) {
	const size = { vostok: 38, aegis: 30, sarab: 30 }[f];
	const p = new Plan();
	perimeter(p, f, size, 4);
	pathCross(p, f, size, 4);
	const mid = Math.floor(size / 2);
	const L = loot(f, "outpost");

	if (f === "vostok") {
		tower(p, f, "watchtower", variant === "a" ? 3 : size - 19, 3, "s");
		p.stamp(pieceFile(f, variant === "a" ? "barracks_1" : "barracks_2"), 4, 1, size - 15, "n", L);
		p.stamp(pieceFile(f, "armory_2"), size - 13, 1, mid, "w", L);
		p.stamp(pieceFile(f, "bunkroom"), variant === "a" ? size - 10 : 4, 1, 4, "s", L);
		flagpole(p, f, mid, mid);
		armSocket(p, f, 0, mid, "west_up", "warfront:vostok/trench_arm");
		armSocket(p, f, size - 1, mid, "east_up", "warfront:vostok/trench_arm");
		armSocket(p, f, mid + 3, size - 1, "south_up", "warfront:vostok/tent_pads");
		p.soldier(mid, 1, mid + 3, f, "officer");
		p.soldier(mid - 2, 1, size - 4, f, "soldier");
		p.soldier(mid + 3, 1, size - 4, f, "soldier");
	} else if (f === "aegis") {
		tower(p, f, "watchtower", size - 18, 3, "s");
		p.stamp(pieceFile(f, variant === "a" ? "barracks_1" : "barracks_2"), 3, 1, size - 14, "n", L);
		p.stamp(pieceFile(f, "armory_1"), 3, 1, 4, "s", L);
		flagpole(p, f, mid, mid);
		floodlight(p, mid - 4, mid - 4);
		floodlight(p, mid + 4, mid + 4);
		p.soldier(mid, 1, mid + 2, f, "officer");
		p.soldier(mid - 2, 1, size - 4, f, "soldier");
	} else {
		tower(p, f, "watchtower", size - 18, 3, "s");
		p.stamp(pieceFile(f, "tent_1"), 3, 1, mid + 2, "e", L);
		p.stamp(pieceFile(f, "tent_2"), 17, 1, size - 11, "n", L);
		p.stamp(pieceFile(f, variant === "a" ? "bunkroom" : "supply"), 3, 1, 4, "s", L);
		p.set(mid, 1, mid, "minecraft:campfire", { lit: "true" });
		flagpole(p, f, mid + 2, mid + 2, 5);
		armSocket(p, f, 0, mid, "west_up", "warfront:sarab/path_arm");
		armSocket(p, f, size - 1, mid, "east_up", "warfront:sarab/path_arm");
		armSocket(p, f, mid - 4, 0, "north_up", "warfront:sarab/path_arm");
		p.soldier(mid, 1, mid + 2, f, "officer");
		p.soldier(4, 1, mid, f, "soldier");
	}
	p.emit(f, `outpost_${variant}`);
}

function forwardBase(f) {
	const size = { vostok: 64, aegis: 54, sarab: 56 }[f];
	const p = new Plan();
	perimeter(p, f, size, 5);
	pathCross(p, f, size, 5);
	const mid = Math.floor(size / 2);
	const L = loot(f, "forward_base");

	// two towers on the north corners (anatomy requirement), armory between them
	tower(p, f, "watchtower", 3, 3, "s");
	tower(p, f, "watchtower", size - 19, 3, "s");
	p.stamp(pieceFile(f, "armory_1"), 20, 1, 4, "s", L);
	// west column: command post faces the courtyard
	p.stamp(pieceFile(f, "command_post"), 4, 1, 20, "e", L);
	// east column: quartermaster post with the quartermaster NPC seed
	const qmx = f === "sarab" ? size - 16 : size - 16;
	const qm = p.stamp(pieceFile(f, "quartermaster"), qmx, 1, 24, "w", L);
	p.soldier(qmx + Math.floor(qm.w / 2), 1, 24 + Math.floor(qm.d / 2), f, "quartermaster");
	// south row: barracks (gate stays clear)
	p.stamp(pieceFile(f, "barracks_1"), 14, 1, size - 17, "n", L);
	if (f === "vostok") {
		p.stamp(pieceFile(f, "barracks_2"), mid + 6, 1, size - 17, "n", L);
		p.stamp(pieceFile(f, "mess"), size - 15, 1, size - 18, "n", L);
		heliPad(p, f, 18, 18, 11);
		armSocket(p, f, 0, mid, "west_up", "warfront:vostok/trench_arm");
		armSocket(p, f, size - 1, mid, "east_up", "warfront:vostok/trench_arm");
		armSocket(p, f, mid + 6, size - 1, "south_up", "warfront:vostok/tent_pads");
	} else if (f === "aegis") {
		p.stamp(pieceFile(f, "mess"), 28, 1, size - 16, "n", L);
		heliPad(p, f, 40, size - 16, 11);
		floodlight(p, 20, 20);
		floodlight(p, 34, 20);
		floodlight(p, 20, 34);
		floodlight(p, 34, 34);
	} else {
		p.stamp(pieceFile(f, "tent_1"), size - 13, 1, size - 14, "n", L);
		heliPad(p, f, 28, size - 18, 11);
		armSocket(p, f, 0, mid, "west_up", "warfront:sarab/path_arm");
		armSocket(p, f, size - 1, mid, "east_up", "warfront:sarab/path_arm");
		armSocket(p, f, 20, 0, "north_up", "warfront:sarab/path_arm");
		armSocket(p, f, 36, 0, "north_up", "warfront:sarab/path_arm");
	}
	flagpole(p, f, mid, mid, 8);
	p.soldier(mid, 1, mid + 3, f, "officer");
	p.soldier(mid - 3, 1, size - 5, f, "soldier");
	p.soldier(mid + 4, 1, size - 5, f, "soldier");
	p.soldier(6, 1, mid, f, "soldier");
	p.emit(f, "forward_base");
}

function headquarters(f) {
	const size = { vostok: 92, aegis: 80, sarab: 76 }[f];
	const p = new Plan();
	perimeter(p, f, size, 6);
	pathCross(p, f, size, 8);
	const mid = Math.floor(size / 2);
	const L = loot(f, "headquarters");

	// corners: heavy comms tower NE, watchtowers NW + SW
	tower(p, f, "heavy_tower", size - 19, 3, "s");
	tower(p, f, "watchtower", 3, 3, "s");
	tower(p, f, "watchtower", 3, size - 19, "n");
	// center: inner command bunker (fortress core) + banner centerpiece dais
	p.stamp(pieceFile(f, "bunker_core"), mid - 6, 1, mid - 10, "s", L);
	bannerCenterpiece(p, f, mid, mid + 8);
	// west column: command post + bunker annexes (cells, storage);
	// sarab's command post runs 17 deep, so its annexes shift south/east
	p.stamp(pieceFile(f, "command_post"), 4, 1, 19, "e", L);
	p.stamp(pieceFile(f, "bunker_cells"), 4, 1, f === "sarab" ? 37 : 31, "e", L);
	if (f === "sarab") {
		p.stamp(pieceFile(f, "bunker_storage"), 18, 1, 37, "e", L);
	} else {
		p.stamp(pieceFile(f, "bunker_storage"), 4, 1, 43, "e", L);
	}
	// east column: two quartermaster posts (+ mess where the faction has one)
	for (const z of [20, 33]) {
		const qm = p.stamp(pieceFile(f, "quartermaster"), size - 16, 1, z, "w", L);
		p.soldier(size - 16 + Math.floor(qm.w / 2), 1, z + Math.floor(qm.d / 2), f, "quartermaster");
	}
	if (f !== "sarab") {
		p.stamp(pieceFile(f, "mess"), size - 16, 1, 46, "n", L);
	}
	// north row armories between the towers
	p.stamp(pieceFile(f, "armory_1"), 20, 1, 4, "s", L);
	p.stamp(pieceFile(f, "armory_2"), size - 31, 1, 4, "s", L);
	// south rows: barracks (Vostok gets mass rows)
	p.stamp(pieceFile(f, "barracks_1"), 18, 1, size - 17, "n", L);
	p.stamp(pieceFile(f, "barracks_2"), mid + 6, 1, size - 17, "n", L);
	if (f === "vostok") {
		p.stamp(pieceFile(f, "barracks_1"), 18, 1, size - 31, "n", L);
		p.stamp(pieceFile(f, "barracks_2"), mid + 6, 1, size - 31, "n", L);
		armSocket(p, f, 0, mid, "west_up", "warfront:vostok/trench_arm");
		armSocket(p, f, size - 1, mid - 8, "east_up", "warfront:vostok/trench_arm");
		armSocket(p, f, size - 1, mid + 8, "east_up", "warfront:vostok/trench_arm");
		armSocket(p, f, mid + 8, size - 1, "south_up", "warfront:vostok/tent_pads");
	} else if (f === "aegis") {
		for (const [x, z] of [[30, 30], [50, 30], [30, 50], [50, 50]]) floodlight(p, x, z);
	} else {
		p.stamp(pieceFile(f, "tent_1"), 20, 1, 30, "e", L);
		p.stamp(pieceFile(f, "tent_2"), 28, 1, size - 28, "n", L);
		armSocket(p, f, 0, mid - 8, "west_up", "warfront:sarab/path_arm");
		armSocket(p, f, 0, mid + 13, "west_up", "warfront:sarab/path_arm");
		armSocket(p, f, size - 1, mid + 5, "east_up", "warfront:sarab/path_arm");
		armSocket(p, f, 30, 0, "north_up", "warfront:sarab/path_arm");
	}
	// training yard NE of the courtyard, storage yard west, heli pad SE
	trainingYard(p, f, mid + 14, 20);
	storageYard(p, f, 20, mid + 8, L);
	heliPad(p, f, mid + 10, size - 20, 13);
	// garrison seeds
	p.soldier(mid, 1, mid + 12, f, "officer");
	p.soldier(mid - 4, 1, size - 6, f, "soldier");
	p.soldier(mid + 5, 1, size - 6, f, "soldier");
	p.soldier(6, 1, mid - 4, f, "soldier");
	p.soldier(size - 7, 1, mid, f, "soldier");
	p.emit(f, "headquarters");
}

// ---------- jigsaw sprawl pieces ----------
function trenchArm() {
	const p = new Plan();
	// 12-long dug trench: path floor, dirt lips, sandbag posts — pure connective tissue
	p.fill(0, 0, 0, 11, 0, 4, "minecraft:coarse_dirt");
	p.fill(0, 0, 1, 11, 0, 3, "minecraft:dirt_path");
	for (let x = 0; x <= 11; x++) {
		p.set(x, 1, 0, "minecraft:dirt");
		p.set(x, 1, 4, "minecraft:dirt");
		if (x % 4 === 1) {
			p.set(x, 2, 0, "warfront:sandbag_station");
			p.set(x, 2, 4, "minecraft:cobblestone_wall");
		}
	}
	// near end: entrance (matches plate sockets); far end: chain socket
	p.set(0, 0, 2, "minecraft:jigsaw", { orientation: "west_up" }, {
		name: "minecraft:building_entrance", target: "minecraft:building_entrance",
		pool: "minecraft:empty", final_state: "minecraft:dirt_path", joint: "aligned",
	});
	p.jigsaw(11, 0, 2, "east_up", "warfront:vostok/trench_arm", "minecraft:dirt_path");
	p.soldier(5, 1, 2, "vostok", "soldier");
	p.emit("vostok", "trench_arm");

	const end = new Plan();
	end.fill(0, 0, 0, 2, 0, 4, "minecraft:coarse_dirt");
	end.set(1, 1, 2, "warfront:sandbag_station");
	end.set(2, 1, 0, "minecraft:dirt");
	end.set(2, 1, 4, "minecraft:dirt");
	end.set(0, 0, 2, "minecraft:jigsaw", { orientation: "west_up" }, {
		name: "minecraft:building_entrance", target: "minecraft:building_entrance",
		pool: "minecraft:empty", final_state: "minecraft:dirt_path", joint: "aligned",
	});
	end.emit("vostok", "trench_end");
}

function sarabArms() {
	const p = new Plan();
	p.fill(0, 0, 0, 9, 0, 2, "minecraft:dirt_path");
	p.set(4, 1, 0, "minecraft:acacia_fence");
	p.set(4, 2, 0, "minecraft:torch");
	p.set(0, 0, 1, "minecraft:jigsaw", { orientation: "west_up" }, {
		name: "minecraft:building_entrance", target: "minecraft:building_entrance",
		pool: "minecraft:empty", final_state: "minecraft:dirt_path", joint: "aligned",
	});
	p.jigsaw(9, 0, 1, "east_up", "warfront:sarab/subcamps", "minecraft:dirt_path");
	p.emit("sarab", "path_arm");

	const end = new Plan();
	end.fill(0, 0, 0, 1, 0, 2, "minecraft:dirt_path");
	end.set(1, 1, 1, "minecraft:acacia_fence");
	end.set(0, 0, 1, "minecraft:jigsaw", { orientation: "west_up" }, {
		name: "minecraft:building_entrance", target: "minecraft:building_entrance",
		pool: "minecraft:empty", final_state: "minecraft:dirt_path", joint: "aligned",
	});
	end.emit("sarab", "path_end");
}

function sarabSubcamp(name, builder) {
	const p = new Plan();
	builder(p);
	p.set(0, 0, Math.floor(p.depthMid ?? 9), "minecraft:jigsaw", { orientation: "west_up" }, {
		name: "minecraft:building_entrance", target: "minecraft:building_entrance",
		pool: "minecraft:empty", final_state: "minecraft:dirt_path", joint: "aligned",
	});
	p.emit("sarab", name);
}

function sarabSubcamps() {
	const L = loot("sarab", "outpost");
	sarabSubcamp("subcamp_a", (p) => {
		p.depthMid = 9;
		p.fill(0, 0, 0, 18, 0, 18, "minecraft:dirt_path");
		for (let i = 0; i <= 18; i += 3) {
			p.set(i, 1, 0, "minecraft:mud_brick_wall");
			p.set(i, 1, 18, "minecraft:mud_brick_wall");
			p.set(0, 1, i, "minecraft:mud_brick_wall");
			p.set(18, 1, i, "minecraft:mud_brick_wall");
		}
		p.stamp(pieceFile("sarab", "tent_1"), 2, 1, 3, "e", L);
		p.stamp(pieceFile("sarab", "tent_2"), 10, 1, 10, "n", L);
		p.set(9, 1, 5, "minecraft:campfire", { lit: "true" });
		p.set(15, 1, 3, "warfront:sandbag_station");
		p.soldier(9, 1, 7, "sarab", "soldier");
		p.soldier(13, 1, 12, "sarab", "soldier");
	});
	sarabSubcamp("subcamp_b", (p) => {
		p.depthMid = 10;
		p.fill(0, 0, 0, 20, 0, 20, "minecraft:dirt_path");
		p.stamp(pieceFile("sarab", "barracks_2"), 3, 1, 4, "s", L);
		p.stamp(pieceFile("sarab", "supply"), 13, 1, 12, "w", L);
		p.set(4, 1, 16, "warfront:sandbag_station");
		p.soldier(10, 1, 10, "sarab", "soldier");
		p.soldier(6, 1, 16, "sarab", "soldier");
	});
	sarabSubcamp("subcamp_tower", (p) => {
		p.depthMid = 9;
		p.fill(0, 0, 0, 18, 0, 18, "minecraft:dirt_path");
		const { w, d } = p.stamp(pieceFile("sarab", "watchtower"), 2, 1, 2, "s", L);
		const top = p.topPlatform(4, 4, w - 2, d - 2);
		if (top) {
			p.set(top.x, top.y, top.z, "warfront:sandbag_station");
			p.soldier(top.x + 1, top.y, top.z, "sarab", "soldier");
		}
	});
}

function tentPads() {
	for (const f of ["vostok", "sarab"]) {
		const p = new Plan();
		p.fill(0, 0, 0, 11, 0, 9, GROUND[f]);
		p.stamp(pieceFile(f, "tent_1"), 2, 1, 1, "s", loot(f, "outpost"));
		p.set(9, 1, 6, "minecraft:campfire", { lit: "true" });
		p.set(9, 1, 2, "warfront:sandbag_station");
		p.soldier(6, 1, 7, f, "soldier");
		p.set(5, 0, 0, "minecraft:jigsaw", { orientation: "north_up" }, {
			name: "minecraft:building_entrance", target: "minecraft:building_entrance",
			pool: "minecraft:empty", final_state: GROUND[f], joint: "aligned",
		});
		p.emit(f, "tent_pad");
	}
}

// ---------- run ----------
for (const f of ["vostok", "aegis", "sarab"]) {
	outpost(f, "a");
	outpost(f, "b");
	forwardBase(f);
	headquarters(f);
}
trenchArm();
sarabArms();
sarabSubcamps();
tentPads();
