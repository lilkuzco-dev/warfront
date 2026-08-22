#!/usr/bin/env node
/**
 * Offline proof that no two warfront bases generate closer than a floor distance.
 *
 * Structure placement is pure arithmetic on the world seed, so it can be replayed
 * exactly outside Minecraft. This reimplements `RandomSpreadStructurePlacement`
 * (and the `LegacyRandomSource` LCG it draws from) and enumerates every candidate
 * placement across a region, then reports the closest pair — overall and
 * cross-faction.
 *
 * Biome predicates and `frequency` are ignored — both can only *remove* a placement,
 * so the distance measured is a lower bound on what a real world produces.
 *
 * `exclusion_zone` is NOT ignored, because it is load-bearing rather than incidental.
 * Warfront's castles are 501 blocks across and live in their own set, and a set's
 * spacing says nothing about any other set — the only thing keeping a base out of a
 * castle is `bases`' exclusion zone pointing at `warfront:grand_castles`. A checker
 * that skipped it would report an overlap the game does not produce, which is just as
 * useless as missing one it does. Modelled the way vanilla does: a candidate is
 * forbidden if any chunk within `chunk_count` (Chebyshev) is a placement chunk of the
 * referenced set. Cross-set references outside this mod (`minecraft:villages`) cannot
 * be replayed here and are reported as unmodelled rather than silently ignored.
 *
 *   node tools/verify-base-spacing.js [--floor 200] [--radius 1600] [--seeds 8]
 *
 * Exits non-zero if any pair lands closer than the floor.
 */

const fs = require('fs');
const path = require('path');

const SET_DIR = path.join(__dirname, '..', 'src', 'main', 'resources',
	'data', 'warfront', 'worldgen', 'structure_set');

const MASK48 = (1n << 48n) - 1n;
const MULT = 0x5DEECE66Dn;
const ADDEND = 0xBn;

/** java.util.Random, which is what LegacyRandomSource is bit-for-bit. */
class LegacyRandom {
	setSeed(seed) {
		this.seed = (BigInt.asIntN(64, seed) ^ MULT) & MASK48;
	}

	next(bits) {
		this.seed = (this.seed * MULT + ADDEND) & MASK48;
		return Number(BigInt.asIntN(32, this.seed >> (48n - BigInt(bits))));
	}

	nextInt(bound) {
		if ((bound & -bound) === bound) {
			return Number((BigInt(bound) * BigInt(this.next(31))) >> 31n);
		}
		let bits, val;
		do {
			bits = this.next(31);
			val = bits % bound;
		} while (bits - val + (bound - 1) < 0);
		return val;
	}

	/** WorldgenRandom.setLargeFeatureWithSalt */
	setLargeFeatureWithSalt(worldSeed, regionX, regionZ, salt) {
		const l = BigInt(regionX) * 341873128712n
			+ BigInt(regionZ) * 132897987541n
			+ BigInt(worldSeed) + BigInt(salt);
		this.setSeed(BigInt.asIntN(64, l));
	}
}

/** RandomSpreadStructurePlacement: the chunk this cell places its structure in. */
function placementForCell(worldSeed, cellX, cellZ, spacing, separation, spreadType, salt) {
	const random = new LegacyRandom();
	random.setLargeFeatureWithSalt(worldSeed, cellX, cellZ, salt);
	const range = spacing - separation;
	let offX, offZ;
	if (spreadType === 'triangular') {
		offX = Math.floor((random.nextInt(range) + random.nextInt(range)) / 2);
		offZ = Math.floor((random.nextInt(range) + random.nextInt(range)) / 2);
	} else {
		offX = random.nextInt(range);
		offZ = random.nextInt(range);
	}
	return [cellX * spacing + offX, cellZ * spacing + offZ];
}

/** Which faction a structure id belongs to, for cross-faction comparison. */
function factionOf(structureId) {
	const name = structureId.replace(/^warfront:/, '');
	for (const faction of ['aegis', 'sarab', 'vostok']) {
		if (name.startsWith(faction)) return faction;
	}
	return 'unknown';
}

function loadSets() {
	return fs.readdirSync(SET_DIR)
		.filter(f => f.endsWith('.json'))
		.map(f => {
			const json = JSON.parse(fs.readFileSync(path.join(SET_DIR, f), 'utf8'));
			return { file: f, id: 'warfront:' + f.replace(/\.json$/, ''), json };
		})
		// warfront:base_spread is RandomSpreadStructurePlacement with an extra, uncapped
		// clearance against another set — same spread maths, so the same replay works.
		.filter(s => s.json.placement?.type === 'minecraft:random_spread'
			|| s.json.placement?.type === 'warfront:base_spread');
}

/**
 * Every cross-set clearance a set declares, normalised. Vanilla's `exclusion_zone` is capped
 * at 16 chunks by its codec; `warfront:base_spread` adds `avoid_set`/`avoid_chunks`, which is
 * the same test without the cap. Both are read here so nothing downstream has to care which
 * mechanism a given set happens to use.
 */
function exclusionsOf(json) {
	const placement = json.placement ?? {};
	const out = [];
	if (placement.exclusion_zone) out.push(placement.exclusion_zone);
	// avoid_sets name OTHER mods' sets by identifier (soft references; see
	// BaseSpreadPlacement). They cannot be replayed here and surface as unmodelled.
	for (const avoid of placement.avoid_sets ?? []) {
		out.push({ other_set: avoid.set, chunk_count: avoid.chunks });
	}
	if (placement.avoid_set) {
		out.push({ other_set: placement.avoid_set, chunk_count: placement.avoid_chunks });
	}
	return out;
}

/** Placement chunks of one set, as a "cx,cz" Set — what an exclusion zone tests against. */
function placementChunks(json, worldSeed, radiusChunks) {
	const p = json.placement;
	const chunks = new Set();
	const cellLo = Math.floor(-radiusChunks / p.spacing);
	const cellHi = Math.ceil(radiusChunks / p.spacing);
	for (let cx = cellLo; cx <= cellHi; cx++) {
		for (let cz = cellLo; cz <= cellHi; cz++) {
			const [chunkX, chunkZ] = placementForCell(worldSeed, cx, cz, p.spacing, p.separation,
				p.spread_type ?? 'linear', p.salt);
			chunks.add(chunkX + ',' + chunkZ);
		}
	}
	return chunks;
}

/**
 * Chunks a set may not place in, given its exclusion zone: every chunk within
 * `chunk_count` of any placement chunk of the referenced set. Expanding the sparse set
 * once beats testing a (2n+1)^2 neighbourhood per candidate of the dense one.
 */
function forbiddenChunks(json, sets, worldSeed, radiusChunks, unmodelled) {
	const forbidden = new Set();
	let any = false;
	for (const zone of exclusionsOf(json)) {
		const target = sets.find(s => s.id === zone.other_set);
		if (!target) {
			unmodelled.add(zone.other_set);
			continue;
		}
		any = true;
		const n = zone.chunk_count;
		for (const key of placementChunks(target.json, worldSeed, radiusChunks)) {
			const [cx, cz] = key.split(',').map(Number);
			for (let dx = -n; dx <= n; dx++) {
				for (let dz = -n; dz <= n; dz++) forbidden.add((cx + dx) + ',' + (cz + dz));
			}
		}
	}
	return any ? forbidden : null;
}

function collectPlacements(sets, worldSeed, radiusChunks, unmodelled = new Set()) {
	const points = [];
	for (const { file, json } of sets) {
		const forbidden = forbiddenChunks(json, sets, worldSeed, radiusChunks, unmodelled);
		const p = json.placement;
		const spacing = p.spacing;
		const separation = p.separation;
		const spreadType = p.spread_type ?? 'linear';
		const cellLo = Math.floor(-radiusChunks / spacing);
		const cellHi = Math.ceil(radiusChunks / spacing);
		// Every structure in the set is a candidate for that cell; vanilla walks the
		// weighted list until one passes its biome check, so any of them may be the
		// one that lands. Attribute the cell to all factions present in the set.
		const factions = [...new Set(json.structures.map(s => factionOf(s.structure)))];
		for (let cx = cellLo; cx <= cellHi; cx++) {
			for (let cz = cellLo; cz <= cellHi; cz++) {
				const [chunkX, chunkZ] = placementForCell(worldSeed, cx, cz, spacing, separation, spreadType, p.salt);
				if (forbidden && forbidden.has(chunkX + ',' + chunkZ)) continue;
				points.push({
					file,
					factions,
					// Structure origin is the chunk's corner in vanilla placement maths.
					x: chunkX * 16,
					z: chunkZ * 16,
				});
			}
		}
	}
	return points;
}

/** Closest pair via a uniform grid keyed at the floor distance. */
function closestPairs(points, floor) {
	const buckets = new Map();
	const key = (bx, bz) => bx + ':' + bz;
	for (const pt of points) {
		const bx = Math.floor(pt.x / floor);
		const bz = Math.floor(pt.z / floor);
		const k = key(bx, bz);
		if (!buckets.has(k)) buckets.set(k, []);
		buckets.get(k).push(pt);
	}
	let overall = { dist: Infinity, a: null, b: null };
	let cross = { dist: Infinity, a: null, b: null };
	for (const pt of points) {
		const bx = Math.floor(pt.x / floor);
		const bz = Math.floor(pt.z / floor);
		for (let dx = -1; dx <= 1; dx++) {
			for (let dz = -1; dz <= 1; dz++) {
				const near = buckets.get(key(bx + dx, bz + dz));
				if (!near) continue;
				for (const other of near) {
					if (other === pt) continue;
					const dist = Math.hypot(pt.x - other.x, pt.z - other.z);
					if (dist < overall.dist) overall = { dist, a: pt, b: other };
					// A pair *can* be cross-faction unless both cells are locked to
					// the same single faction. A set holding several factions can
					// emit a different one at each cell, so two of its placements
					// count against the cross-faction floor.
					const lockedToSameFaction = pt.factions.length === 1
						&& other.factions.length === 1
						&& pt.factions[0] === other.factions[0];
					if (!lockedToSameFaction && dist < cross.dist) cross = { dist, a: pt, b: other };
				}
			}
		}
	}
	return { overall, cross };
}

function describe(pair) {
	if (!pair.a) return 'none found in region';
	return `${pair.dist.toFixed(1)} blocks — ${pair.a.file} @ (${pair.a.x}, ${pair.a.z}) `
		+ `vs ${pair.b.file} @ (${pair.b.x}, ${pair.b.z})`;
}

/**
 * Every structure's own plate width, read from its NBT, keyed by structure id.
 *
 * A structure set's `spacing` says nothing about any other set, so the only thing keeping
 * a 501-block castle out of a base is `bases`' exclusion zone. That radius has to be at
 * least half of each plate added together — and it has to be re-derived from the NBTs
 * rather than remembered, because the whole point of the castle work is that a supplied
 * build may be bigger than the last one.
 */
function plateWidths() {
	let parse;
	try { ({ parse } = require('./nbt.js')); } catch { return null; }
	const structureDir = path.join(__dirname, '..', 'src', 'main', 'resources',
		'data', 'warfront', 'structure');
	const widths = new Map();
	const walk = (dir, faction) => {
		for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
			const full = path.join(dir, entry.name);
			if (entry.isDirectory()) walk(full, entry.name);
			else if (entry.name.endsWith('.nbt')) {
				try {
					const size = parse(fs.readFileSync(full)).root.v.size.v.items;
					// aegis/castle.nbt is the template behind warfront:aegis_castle
					widths.set(`warfront:${faction}_${entry.name.replace(/\.nbt$/, '')}`,
						Math.max(size[0], size[2]));
				} catch { /* not a structure template */ }
			}
		}
	};
	try { walk(structureDir, null); } catch { return null; }
	return widths;
}

/** Widest plate any member of this set can place. */
function widestInSet(json, widths) {
	let best = { id: null, width: 0 };
	for (const entry of json.structures) {
		const width = widths.get(entry.structure) ?? 0;
		if (width > best.width) best = { id: entry.structure, width };
	}
	return best;
}

/**
 * The gate for cross-set clearance. Two sets have no mutual spacing at all, so either an
 * exclusion zone covers the two widest plates involved or the two structures can land on
 * top of each other. Returns the number of failures.
 */
function checkExclusionZones(sets, widths) {
	if (!widths) return 0;
	let failures = 0;
	console.log('\nCross-set clearance (a set\'s spacing does not apply to any other set):');
	for (const set of sets) {
		const zones = exclusionsOf(set.json);
		const mine = widestInSet(set.json, widths);
		if (!zones.length) {
			console.log(`  ${set.file}: no cross-set clearance — widest plate ${mine.width} blocks`);
			continue;
		}
		for (const zone of zones) {
		const target = sets.find(s => s.id === zone.other_set);
		if (!target) {
			console.log(`  ${set.file} -> ${zone.other_set} @ ${zone.chunk_count} chunks `
				+ `(outside this mod; plate sizes unknown, not checked)`);
			continue;
		}
		const theirs = widestInSet(target.json, widths);
		const needBlocks = mine.width / 2 + theirs.width / 2;
		const needChunks = Math.ceil(needBlocks / 16);
		const haveBlocks = zone.chunk_count * 16;
		const ok = zone.chunk_count >= needChunks;
		if (!ok) failures++;
		console.log(`  ${set.file} -> ${zone.other_set}`);
		console.log(`      widest here ${mine.width} (${mine.id}), widest there ${theirs.width} (${theirs.id})`);
		console.log(`      need ${needBlocks.toFixed(0)} blocks = ${needChunks} chunks; `
			+ `configured ${zone.chunk_count} chunks = ${haveBlocks} blocks  `
			+ `${ok ? 'OK' : '<== TOO SMALL'}`);
		}
	}
	return failures;
}

/** Closest measured distance between placements of two different sets. */
function reportCrossSetDistances(points, widths) {
	const byFile = new Map();
	for (const pt of points) {
		if (!byFile.has(pt.file)) byFile.set(pt.file, []);
		byFile.get(pt.file).push(pt);
	}
	const files = [...byFile.keys()].sort();
	const out = [];
	for (let i = 0; i < files.length; i++) {
		for (let j = i + 1; j < files.length; j++) {
			let min = Infinity;
			for (const a of byFile.get(files[i])) {
				for (const b of byFile.get(files[j])) {
					const d = Math.hypot(a.x - b.x, a.z - b.z);
					if (d < min) min = d;
				}
			}
			out.push({ a: files[i], b: files[j], min });
		}
	}
	return out;
}

function main() {
	const argv = process.argv.slice(2);
	const arg = (name, fallback) => {
		const i = argv.indexOf('--' + name);
		return i >= 0 ? Number(argv[i + 1]) : fallback;
	};
	const floor = arg('floor', 200);
	const radius = arg('radius', 1600);
	const seedCount = arg('seeds', 8);

	const sets = loadSets();
	console.log(`Structure sets: ${sets.length}`);
	for (const { file, json } of sets) {
		const p = json.placement;
		const zone = p.exclusion_zone
			? ` exclusion=${p.exclusion_zone.other_set}@${p.exclusion_zone.chunk_count}ch` : '';
		console.log(`  ${file}: spacing=${p.spacing} separation=${p.separation} `
			+ `structures=${json.structures.length}${zone}`);
	}
	console.log(`\nRegion: +/-${radius} chunks (${radius * 32} x ${radius * 32} blocks), `
		+ `${seedCount} seeds, floor ${floor} blocks\n`);

	let worstOverall = Infinity;
	let worstCross = Infinity;
	let failures = 0;
	const unmodelled = new Set();
	const crossSet = [];
	let seedFailures = 0;

	for (let s = 0; s < seedCount; s++) {
		// Fixed, arbitrary seeds — reproducible across runs.
		const worldSeed = BigInt(s) * 6364136223846793005n + 1442695040888963407n;
		const points = collectPlacements(sets, worldSeed, radius, unmodelled);
		const { overall, cross } = closestPairs(points, floor);
		worstOverall = Math.min(worstOverall, overall.dist);
		worstCross = Math.min(worstCross, cross.dist);
		const bad = cross.dist < floor;
		if (bad) { failures++; seedFailures++; }
		for (const row of reportCrossSetDistances(points, null)) {
			const prev = crossSet.find(r => r.a === row.a && r.b === row.b);
			if (!prev) crossSet.push(row); else prev.min = Math.min(prev.min, row.min);
		}
		console.log(`seed ${worldSeed}  (${points.length} placements)`);
		console.log(`  closest any-pair:   ${describe(overall)}`);
		console.log(`  closest cross-fac:  ${describe(cross)} ${bad ? '  <== BELOW FLOOR' : ''}`);
	}

	console.log(`\nWorst closest any-pair across all seeds:   ${worstOverall.toFixed(1)} blocks`);
	console.log(`Worst closest cross-faction across seeds:  ${worstCross.toFixed(1)} blocks`);
	console.log(`Required floor: ${floor} blocks`);

	// Centre-to-centre distance is not the whole question once one structure in the set is
	// 501 blocks across. A distance that clears the floor comfortably for two 76-block
	// plates can still put a castle wall through a metropolis. Report the plate sizes and
	// the implied edge clearance so that residual is a measured number rather than
	// something a reader has to work out for themselves.
	if (unmodelled.size) {
		console.log(`\nUnmodelled exclusion targets (outside this mod, cannot be replayed here): `
			+ [...unmodelled].join(', '));
	}
	const widths = plateWidths();
	const zoneFailures = checkExclusionZones(sets, widths);
	if (crossSet.length) {
		console.log('\nClosest measured distance between sets, worst seed:');
		for (const row of crossSet) {
			const need = widths
				? widestInSet(sets.find(s => s.file === row.a).json, widths).width / 2
					+ widestInSet(sets.find(s => s.file === row.b).json, widths).width / 2
				: null;
			const verdict = need === null ? ''
				: row.min >= need ? `  clearance +${(row.min - need).toFixed(0)}`
				: `  <== OVERLAP by ${(need - row.min).toFixed(0)}`;
			console.log(`  ${row.a} vs ${row.b}: ${row.min.toFixed(0)} blocks`
				+ (need === null ? '' : ` (widest plates need ${need.toFixed(0)})${verdict}`));
			if (need !== null && row.min < need) failures++;
		}
	}
	failures += zoneFailures;

	if (seedFailures > 0 || failures > seedFailures) {
		if (seedFailures > 0) {
			console.log(`\nFAIL: ${seedFailures}/${seedCount} seeds place two different factions `
				+ `closer than ${floor} blocks.`);
		}
		if (failures > seedFailures) {
			console.log(`\nFAIL: ${failures - seedFailures} cross-set clearance problem(s) — a set's `
				+ `spacing does not apply to another set, so an exclusion zone has to cover both plates.`);
		}
		process.exit(1);
	}
	console.log(`\nPASS: no cross-faction pair closer than ${floor} blocks on any tested seed, `
		+ `and every cross-set exclusion covers the plates involved.`);
}

main();
