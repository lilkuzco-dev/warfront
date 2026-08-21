#!/usr/bin/env node
/**
 * How big is a supplied castle build, actually?
 *
 * import-grand-castle.js reads a window of fixed radius (default 170, so 341x341) out of a
 * supplied world and writes a fixed 501x501 structure. Both numbers are assumptions about
 * the build, and neither was ever checked against one. Measured 2026-08-20:
 *
 *   Celestial Castle (Aegis)     341x341 captures 97.6%   - the assumption held
 *   Mug Castle (Sarab)           341x341 captures 87.5%
 *   Cinderella Armored (Vostok)  341x341 captures 36.5%   - two thirds left behind
 *
 * So this exists to answer the question before an import, rather than discovering the answer
 * in a screenshot afterwards. It counts, per chunk, blocks that are not plausible natural
 * terrain, and reports the extent of the result.
 *
 * Pre-1.13 worlds store numeric block ids and must be upgraded first:
 *   java -jar <26.2 server bundle> --forceUpgrade --eraseCache --universe <dir> --world <name> nogui
 * The upgraded overworld lands in <world>/dimensions/minecraft/overworld, which this accepts
 * directly. NOTE: the server keeps running after the upgrade finishes - watch its log for
 * "Upgrade done" and stop it, or it sits paused forever holding the port.
 *
 * Usage:
 *   node tools/measure-build.js <world-or-dimension-dir> [threshold]
 */

"use strict";

const fs = require("node:fs"), path = require("node:path"), zlib = require("node:zlib");
const { parse } = require("./nbt.js");

const world = process.argv[2];
const THRESHOLD = Number(process.argv[3] ?? 200);
if (!world) { console.error("usage: node tools/measure-build.js <world-dir> [threshold]"); process.exit(2); }

const NATURAL = new Set(["minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:stone",
 "minecraft:dirt","minecraft:grass_block","minecraft:bedrock","minecraft:granite","minecraft:diorite",
 "minecraft:andesite","minecraft:gravel","minecraft:sand","minecraft:red_sand","minecraft:clay",
 "minecraft:water","minecraft:lava","minecraft:coal_ore","minecraft:iron_ore","minecraft:gold_ore",
 "minecraft:redstone_ore","minecraft:lapis_ore","minecraft:diamond_ore","minecraft:emerald_ore",
 "minecraft:copper_ore","minecraft:deepslate","minecraft:tuff","minecraft:sandstone","minecraft:snow",
 "minecraft:snow_block","minecraft:ice","minecraft:packed_ice","minecraft:podzol","minecraft:mycelium",
 "minecraft:oak_log","minecraft:oak_leaves","minecraft:birch_log","minecraft:birch_leaves",
 "minecraft:spruce_log","minecraft:spruce_leaves","minecraft:jungle_log","minecraft:jungle_leaves",
 "minecraft:acacia_log","minecraft:acacia_leaves","minecraft:dark_oak_log","minecraft:dark_oak_leaves",
 "minecraft:grass","minecraft:tall_grass","minecraft:fern","minecraft:large_fern","minecraft:dead_bush",
 "minecraft:seagrass","minecraft:tall_seagrass","minecraft:kelp","minecraft:kelp_plant","minecraft:vine",
 "minecraft:dandelion","minecraft:poppy","minecraft:blue_orchid","minecraft:allium","minecraft:azure_bluet",
 "minecraft:oxeye_daisy","minecraft:cornflower","minecraft:lily_of_the_valley","minecraft:sugar_cane",
 "minecraft:cactus","minecraft:sunflower","minecraft:lilac","minecraft:rose_bush","minecraft:peony",
 "minecraft:gravel","minecraft:magma_block","minecraft:obsidian","minecraft:moss_block"]);

/** Exactly warfront/tools/import-grand-castle.js's decoder: 1.16+ pads, older spans longs. */
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
  const bitIndex = index * bits;
  const longIndex = Math.floor(bitIndex / 64);
  const shift = BigInt(bitIndex % 64);
  let value = (BigInt.asUintN(64, longs[longIndex]) >> shift) & mask;
  if (shift + BigInt(bits) > 64n && longIndex + 1 < longs.length) {
    value |= (BigInt.asUintN(64, longs[longIndex + 1]) << (64n - shift)) & mask;
  }
  return Number(value);
}

function chunkPayload(region, slot) {
  const location = region.readUInt32BE(slot * 4);
  if (!location) return null;
  const offset = (location >>> 8) * 4096;
  if (offset + 5 > region.length) return null;
  const length = region.readUInt32BE(offset);
  if (length < 2 || offset + 4 + length > region.length) return null;
  const compression = region[offset + 4];
  const body = region.subarray(offset + 5, offset + 4 + length);
  return compression === 1 ? zlib.gunzipSync(body) : zlib.inflateSync(body);
}

const built = new Map();
// A world root has region/ directly; an upgraded world puts the overworld under
// dimensions/minecraft/overworld. Accept either, so the caller does not have to know which
// era of world they were handed.
const candidates = [path.join(world, "region"),
	path.join(world, "dimensions", "minecraft", "overworld", "region")];
const regionDir = candidates.find((dir) => fs.existsSync(dir));
if (!regionDir) {
	console.error(`no region/ under ${world} (looked in ${candidates.join(", ")})`);
	process.exit(2);
}
for (const name of fs.readdirSync(regionDir).filter(n => n.endsWith(".mca"))) {
  const m = name.match(/^r\.(-?\d+)\.(-?\d+)\.mca$/); if (!m) continue;
  const rx = Number(m[1]), rz = Number(m[2]);
  if (Math.abs(rx) > 8 || Math.abs(rz) > 8) continue;   // ignore absurd stray regions
  const region = fs.readFileSync(path.join(regionDir, name));
  for (let slot = 0; slot < 1024; slot++) {
    let raw; try { raw = chunkPayload(region, slot); } catch { continue; }
    if (!raw) continue;
    let root; try { root = parse(raw).root.v; } catch { continue; }
    const level = root.Level?.v ?? root;
    const cx = level.xPos?.v, cz = level.zPos?.v;
    if (cx === undefined) continue;
    const sections = level.sections?.v.items ?? level.Sections?.v.items ?? [];
    let count = 0;
    for (const s of sections) {
      const sv = s.v ?? s;
      const bs = sv.block_states?.v ?? sv;
      const palette = bs.palette?.v?.items ?? sv.Palette?.v?.items;
      if (!palette) continue;
      const longs = bs.data?.v ?? sv.BlockStates?.v;   // TAG_Long_Array: a plain array of BigInt
      const names = palette.map(p => (p.v ?? p).Name.v);
      if (!longs || !longs.length) { // single-state section
        if (!NATURAL.has(names[0])) count += 4096;
        continue;
      }
      const bits = Math.max(4, Math.ceil(Math.log2(names.length)));
      for (let i = 0; i < 4096; i++) {
        const n = names[stateAt(longs, bits, i)];
        if (n && !NATURAL.has(n)) count++;
      }
    }
    if (count > 0) built.set(`${cx},${cz}`, count);
  }
}

// ---------------------------------------------------------------------------
//  Where should the crop actually be centred, and how wide?
// ---------------------------------------------------------------------------
// The importer took a centre and a radius on faith. Sarab shipped a 341x341 window of
// jungle canopy and a snowy cliff with no keep in it, because the centre it was given was
// not where the castle is. This searches for the window that captures the most built
// material, which is a question with an answer rather than a judgement call.
function recommendCrop(points, total) {
	const byChunk = new Map(points.map((p) => [p.x + "," + p.z, p.c]));
	const sum = (cx, cz, rChunks) => {
		let acc = 0;
		for (let dx = -rChunks; dx <= rChunks; dx++) {
			for (let dz = -rChunks; dz <= rChunks; dz++) acc += byChunk.get((cx + dx) + "," + (cz + dz)) ?? 0;
		}
		return acc;
	};
	console.log("\nbest crop centre by window size (block coords, and what it captures):");
	const out = [];
	for (const radius of [170, 250, 320, 400, 500]) {
		const rChunks = Math.ceil(radius / 16);
		let best = { c: -1, x: 0, z: 0 };
		for (const p of points) {
			const c = sum(p.x, p.z, rChunks);
			if (c > best.c) best = { c, x: p.x, z: p.z };
		}
		const centreX = best.x * 16 + 8;
		const centreZ = best.z * 16 + 8;
		const pct = (best.c / total * 100).toFixed(1);
		console.log(`  radius ${String(radius).padStart(3)} (${String(2 * radius + 1).padStart(4)} wide): `
			+ `centre (${centreX}, ${centreZ})  captures ${pct}%`);
		out.push({ radius, centreX, centreZ, pct });
	}
	return out;
}

const hits = [...built.entries()].filter(([, c]) => c >= THRESHOLD).map(([k, c]) => {
  const [x, z] = k.split(",").map(Number); return { x, z, c };
});
if (!hits.length) { console.log("no built chunks above threshold"); process.exit(0); }
const xs = hits.map(h => h.x), zs = hits.map(h => h.z);
const [minX, maxX, minZ, maxZ] = [Math.min(...xs), Math.max(...xs), Math.min(...zs), Math.max(...zs)];
const total = hits.reduce((s, h) => s + h.c, 0);
console.log(`built chunks (>=${THRESHOLD} built blocks): ${hits.length}`);
console.log(`chunk bbox X ${minX}..${maxX}  Z ${minZ}..${maxZ}`);
console.log(`BLOCK EXTENT: ${(maxX - minX + 1) * 16} x ${(maxZ - minZ + 1) * 16} blocks`);
console.log(`centre approx: (${((minX + maxX + 1) / 2 * 16) | 0}, ${((minZ + maxZ + 1) / 2 * 16) | 0})`);
console.log(`built blocks counted: ${total.toLocaleString()}`);
// densest core, to separate the main build from outbuildings
const sorted = hits.sort((a, b) => b.c - a.c).slice(0, Math.max(1, Math.floor(hits.length * 0.9)));
const sx = sorted.map(h => h.x), sz = sorted.map(h => h.z);
recommendCrop(hits, hits.reduce((s2, h) => s2 + h.c, 0));
console.log(`core 90% of chunks: ${(Math.max(...sx) - Math.min(...sx) + 1) * 16} x ${(Math.max(...sz) - Math.min(...sz) + 1) * 16} blocks`);
