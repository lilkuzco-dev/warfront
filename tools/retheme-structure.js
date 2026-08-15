#!/usr/bin/env node
// The faction retheme pipeline (Stage 3 force multiplier): reads a structure NBT,
// remaps its block palette through a per-faction material map JSON, rewires jigsaw
// blocks, and writes a new NBT. One sourced build becomes three faction skins.
//
// Built-in rules on top of the map:
//   - any *_bed            -> warfront:bunk (both halves; props stripped) — the
//                             reinforcement driver blocks land automatically
//   - any *_banner         -> <_banner_color>_banner / _wall_banner (props kept)
//   - jigsaw blocks named minecraft:building_entrance / minecraft:entrance are KEPT
//     (normalized to building_entrance semantics, pool -> minecraft:empty) so pieces
//     stay socketable; every other jigsaw (decor/villager/corridor sprouts) is BAKED
//     to its remapped final_state
//
// Usage:
//   node tools/retheme-structure.js --map tools/retheme-maps/vostok.json --in a.nbt --out b.nbt
//   node tools/retheme-structure.js --batch tools/retheme-batch.json
const fs = require("node:fs");
const path = require("node:path");
const { TAG, N, parse, write } = require("./nbt");

// blocks that intentionally pass through unmapped without a warning
const NEUTRAL = new Set(["minecraft:air", "minecraft:cave_air", "minecraft:water", "minecraft:jigsaw",
	"minecraft:structure_void", "minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path",
	"minecraft:podzol", "minecraft:gravel", "minecraft:sand", "minecraft:torch", "minecraft:wall_torch",
	"minecraft:lantern", "minecraft:campfire", "minecraft:soul_campfire", "minecraft:chest", "minecraft:barrel",
	"minecraft:crafting_table", "minecraft:smithing_table", "minecraft:grindstone", "minecraft:furnace",
	"minecraft:blast_furnace", "minecraft:smoker", "minecraft:lectern", "minecraft:bookshelf",
	"minecraft:ladder", "minecraft:iron_bars", "minecraft:iron_door", "minecraft:flower_pot",
	"minecraft:cobweb", "minecraft:dead_bush", "minecraft:fern", "minecraft:large_fern", "minecraft:poppy",
	"minecraft:short_grass", "minecraft:redstone_torch", "minecraft:glowstone", "minecraft:cactus",
	"minecraft:carved_pumpkin", "minecraft:pumpkin", "minecraft:terracotta", "minecraft:orange_terracotta",
	"minecraft:black_wool", "minecraft:potted_cactus", "minecraft:potted_dead_bush"]);

function parseBlockstateString(s) {
	const m = s.match(/^([a-z0-9_:]+)(?:\[(.*)\])?$/);
	if (!m) return { name: s, props: null };
	const props = m[2] ? Object.fromEntries(m[2].split(",").map((kv) => kv.split("="))) : null;
	return { name: m[1], props };
}

function blockstateToString(name, props) {
	if (!props || Object.keys(props).length === 0) return name;
	return `${name}[${Object.entries(props).map(([k, v]) => `${k}=${v}`).join(",")}]`;
}

/** Applies map + built-in rules to a block id; returns {name, props: 'keep'|'strip'|object}. */
function remapId(map, bannerColor, name) {
	if (/^minecraft:[a-z_]*_bed$/.test(name)) {
		return { name: "warfront:bunk", props: "strip" };
	}
	const banner = name.match(/^minecraft:[a-z_]*?(_wall)?_banner$/);
	if (banner) {
		return { name: `minecraft:${bannerColor}${banner[1] ?? ""}_banner`, props: "keep" };
	}
	const entry = map[name];
	if (entry === undefined) return { name, props: "keep", unmapped: !NEUTRAL.has(name) };
	if (typeof entry === "string") return { name: entry, props: entry === "minecraft:air" ? "strip" : "keep" };
	return { name: entry.name, props: entry.props ?? "keep" };
}

function retheme(mapFile, inFile, outFile) {
	const mapJson = JSON.parse(fs.readFileSync(mapFile, "utf8"));
	const map = mapJson.map;
	const bannerColor = mapJson._banner_color;
	const { root } = parse(fs.readFileSync(inFile));
	const r = root.v;
	const palette = r.palette.v.items; // raw compound payloads: {Name:{t,v}, Properties?:{t,v}}
	const names = palette.map((p) => p.Name.v);
	const unmapped = new Set();

	// 1) remap every palette entry in place
	for (const entry of palette) {
		const res = remapId(map, bannerColor, entry.Name.v);
		if (res.unmapped) unmapped.add(entry.Name.v);
		entry.Name = N.string(res.name);
		if (res.props === "strip") delete entry.Properties;
		else if (res.props !== "keep") {
			entry.Properties = N.compound(Object.fromEntries(
				Object.entries(res.props).map(([k, v]) => [k, N.string(String(v))])));
		}
	}

	// 2) jigsaw blocks: keep entrances (normalized), bake the rest to final_state
	const paletteKey = (name, props) => blockstateToString(name,
		props ? Object.fromEntries(Object.entries(props.v).map(([k, v]) => [k, v.v])) : null);
	const paletteIndex = new Map(palette.map((p, i) => [paletteKey(p.Name.v, p.Properties), i]));
	const ensurePaletteEntry = (name, props) => {
		const key = blockstateToString(name, props);
		if (paletteIndex.has(key)) return paletteIndex.get(key);
		const entry = { Name: N.string(name) };
		if (props && Object.keys(props).length) {
			entry.Properties = N.compound(Object.fromEntries(Object.entries(props).map(([k, v]) => [k, N.string(String(v))])));
		}
		palette.push(entry);
		paletteIndex.set(key, palette.length - 1);
		return palette.length - 1;
	};

	let kept = 0, baked = 0, bunks = 0;
	for (const b of r.blocks.v.items) {
		const idx = b.state.v;
		if (palette[idx].Name.v === "warfront:bunk") bunks++;
		if (names[idx] !== "minecraft:jigsaw" || !b.nbt) continue;
		const j = b.nbt.v;
		const jName = j.name?.v ?? "";
		if (jName === "minecraft:building_entrance" || jName === "minecraft:entrance") {
			// normalize to the vanilla street-socket convention our plates use
			j.name = N.string("minecraft:building_entrance");
			j.target = N.string("minecraft:building_entrance");
			j.pool = N.string("minecraft:empty");
			if (j.final_state) {
				const fs_ = parseBlockstateString(j.final_state.v);
				const res = remapId(map, bannerColor, fs_.name);
				j.final_state = N.string(blockstateToString(res.name,
					res.props === "keep" ? fs_.props : (res.props === "strip" ? null : res.props)));
			}
			kept++;
		} else {
			const fs_ = parseBlockstateString(j.final_state?.v ?? "minecraft:air");
			const res = remapId(map, bannerColor, fs_.name === "minecraft:structure_void" ? "minecraft:air" : fs_.name);
			b.state = N.int(ensurePaletteEntry(res.name,
				res.props === "keep" ? fs_.props : (res.props === "strip" ? null : res.props)));
			delete b.nbt;
			baked++;
		}
	}

	fs.mkdirSync(path.dirname(outFile), { recursive: true });
	fs.writeFileSync(outFile, write(root));
	const rel = path.relative(process.cwd(), outFile);
	console.log(`${rel}: entrances=${kept} baked=${baked} bunks=${bunks}`
		+ (unmapped.size ? `  UNMAPPED: ${[...unmapped].join(" ")}` : ""));
}

const args = process.argv.slice(2);
function argOf(flag) {
	const i = args.indexOf(flag);
	return i >= 0 ? args[i + 1] : null;
}

if (argOf("--batch")) {
	const batch = JSON.parse(fs.readFileSync(argOf("--batch"), "utf8"));
	const baseIn = batch.source_root, baseOut = batch.out_root;
	for (const [faction, jobs] of Object.entries(batch.factions)) {
		const mapFile = path.join(__dirname, "retheme-maps", `${faction}.json`);
		for (const job of jobs) {
			retheme(mapFile, path.join(baseIn, `${job.src}.nbt`), path.join(baseOut, faction, `${job.out}.nbt`));
		}
	}
} else {
	retheme(argOf("--map"), argOf("--in"), argOf("--out"));
}
