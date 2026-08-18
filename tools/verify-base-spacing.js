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
 * Deliberately conservative: biome predicates, exclusion zones and `frequency`
 * are all ignored. Every one of them can only *remove* a placement, so the
 * distance measured here is a lower bound on the distance a real world produces.
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
function placementForCell(worldSeed, cellX, cellZ, spacing, separation, spreadType) {
	const random = new LegacyRandom();
	random.setLargeFeatureWithSalt(worldSeed, cellX, cellZ, placementForCell.salt);
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
			return { file: f, json };
		})
		.filter(s => s.json.placement?.type === 'minecraft:random_spread');
}

function collectPlacements(sets, worldSeed, radiusChunks) {
	const points = [];
	for (const { file, json } of sets) {
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
				placementForCell.salt = p.salt;
				const [chunkX, chunkZ] = placementForCell(worldSeed, cx, cz, spacing, separation, spreadType);
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
		console.log(`  ${file}: spacing=${p.spacing} separation=${p.separation} `
			+ `structures=${json.structures.length}`);
	}
	console.log(`\nRegion: +/-${radius} chunks (${radius * 32} x ${radius * 32} blocks), `
		+ `${seedCount} seeds, floor ${floor} blocks\n`);

	let worstOverall = Infinity;
	let worstCross = Infinity;
	let failures = 0;

	for (let s = 0; s < seedCount; s++) {
		// Fixed, arbitrary seeds — reproducible across runs.
		const worldSeed = BigInt(s) * 6364136223846793005n + 1442695040888963407n;
		const points = collectPlacements(sets, worldSeed, radius);
		const { overall, cross } = closestPairs(points, floor);
		worstOverall = Math.min(worstOverall, overall.dist);
		worstCross = Math.min(worstCross, cross.dist);
		const bad = cross.dist < floor;
		if (bad) failures++;
		console.log(`seed ${worldSeed}  (${points.length} placements)`);
		console.log(`  closest any-pair:   ${describe(overall)}`);
		console.log(`  closest cross-fac:  ${describe(cross)} ${bad ? '  <== BELOW FLOOR' : ''}`);
	}

	console.log(`\nWorst closest any-pair across all seeds:   ${worstOverall.toFixed(1)} blocks`);
	console.log(`Worst closest cross-faction across seeds:  ${worstCross.toFixed(1)} blocks`);
	console.log(`Required floor: ${floor} blocks`);

	if (failures > 0) {
		console.log(`\nFAIL: ${failures}/${seedCount} seeds place two different factions closer than ${floor} blocks.`);
		process.exit(1);
	}
	console.log(`\nPASS: no cross-faction pair closer than ${floor} blocks on any tested seed.`);
}

main();
