#!/usr/bin/env node
// Structural regression checks for the three imported normal grand castles.
const fs = require("node:fs");
const path = require("node:path");
const { parse } = require("./nbt");

const rootDir = path.join(__dirname, "..");
const dataDir = path.join(rootDir, "src/main/resources/data/warfront");
const factions = ["aegis", "sarab", "vostok"];
// District centres follow the castle's footprint now, the same way the importer places them:
// a fixed inset from each edge, so a wider castle spreads its towns rather than growing them.
const TOWN_INSET = 72;
const townCentersFor = (size) => {
	const mid = Math.floor(size / 2);
	return [[mid, TOWN_INSET], [size - 1 - TOWN_INSET, mid], [mid, size - 1 - TOWN_INSET], [TOWN_INSET, mid]];
};
// Baked-soldier counts vary by a few per faction: the rampart-watch ring skips a
// position when the min-y cut leaves that column empty (real terrain shows through
// there instead), and a skipped position is safer than a soldier baked into the ground.
const expected = {
	aegis: { sizeX: 501, sizeY: 196, blocks: 4244844, loot: 24, garrison: [64, 80], officers: 12, rankAndFile: 34 },
	sarab: { sizeX: 801, sizeY: 119, blocks: 1125889, loot: 24, garrison: [72, 88], officers: 10, rankAndFile: 31 },
	vostok: { sizeX: 501, sizeY: 196, blocks: 1313425, loot: 24, garrison: [84, 96], officers: 11, rankAndFile: 34 },
};

function check(condition, message) {
	if (!condition) throw new Error(message);
}

// All four castles live in ONE set of their own, and `bases` keeps its distance with
// warfront:base_spread. That is a custom placement type rather than a vanilla exclusion
// zone for one measured reason: exclusion_zone.chunk_count is codec-bounded to [1:16], and
// a 501-block castle beside a 124-block metropolis needs 313 blocks — 20 chunks. The game
// refuses the datapack outright rather than degrading, so this is checked here and proven
// at runtime by ./gradlew runWorldgentest.
const CASTLE_WEIGHT = 15;
const DRACULA_WEIGHT = 1;
const structureSet = JSON.parse(fs.readFileSync(
	path.join(dataDir, "worldgen/structure_set/grand_castles.json"), "utf8"));
check(structureSet.structures.length === 4,
	`castle set must hold three faction castles plus Dracula, found ${structureSet.structures.length}`);
check(!fs.existsSync(path.join(dataDir, "worldgen/structure_set/dracula_castles.json")),
	"dracula_castles.json is back — Dracula shares the castle set so one clearance covers it");

const basesSet = JSON.parse(fs.readFileSync(
	path.join(dataDir, "worldgen/structure_set/bases.json"), "utf8"));
check(!basesSet.structures.some((entry) => entry.structure.includes("castle")),
	"no castle may sit in the bases set — its spacing is sized for 76-to-124-block plates");
const placement = basesSet.placement;
check(placement.type === "warfront:base_spread",
	`bases must use warfront:base_spread, found ${placement.type}; a vanilla random_spread `
	+ "cannot express more than 16 chunks of cross-set clearance");
check(placement.avoid_set === "warfront:grand_castles",
	"bases must avoid warfront:grand_castles, or nothing keeps a base out of a castle");
check(placement.exclusion_zone?.other_set === "minecraft:villages",
	"bases should still dodge villages — base_spread keeps the vanilla exclusion zone as well, "
	+ "so that was never a trade this design had to make");

for (const faction of factions) {
	const entry = structureSet.structures.find((candidate) => candidate.structure === `warfront:${faction}_castle`);
	check(entry?.weight === CASTLE_WEIGHT, `${faction} castle must have weight ${CASTLE_WEIGHT}`);
	const factionConfig = JSON.parse(fs.readFileSync(path.join(dataDir, `warfront_factions/${faction}.json`), "utf8"));
	check(JSON.stringify(factionConfig.population.garrison.castle) === JSON.stringify(expected[faction].garrison),
		`${faction}: castle garrison range changed unexpectedly`);
	const lootTable = fs.readFileSync(path.join(dataDir, `loot_table/castle/${faction}.json`), "utf8");
	for (const prize of ["minecraft:diamond", "minecraft:golden_apple", "minecraft:totem_of_undying"])
		check(lootTable.includes(prize), `${faction}: rich castle loot is missing ${prize}`);
}
const draculaEntries = structureSet.structures.filter((entry) => entry.structure.includes("dracula"));
check(draculaEntries.length === 1 && draculaEntries[0].weight === DRACULA_WEIGHT,
	`Dracula must appear exactly once at weight ${DRACULA_WEIGHT}`);

// Dracula used to get its rarity from a set of its own at spacing 640 with frequency 0.35;
// sharing the castle set it gets it from weight instead, and 1 against 15/15/15 reproduces
// that rate. Pinned so a later weight edit cannot quietly turn the rarest thing in the mod
// into a common one.
const totalWeight = structureSet.structures.reduce((sum, entry) => sum + entry.weight, 0);
const castlePlacementsPerRegion = (3200 ** 2) / (160 ** 2);
const expectDracula = (3200 ** 2) / (640 ** 2) * 0.35;
const expectGrand = castlePlacementsPerRegion - expectDracula;
const actualDracula = DRACULA_WEIGHT / totalWeight * castlePlacementsPerRegion;
const actualGrand = (3 * CASTLE_WEIGHT) / totalWeight * castlePlacementsPerRegion;
check(Math.abs(actualDracula - expectDracula) / expectDracula < 0.10,
	`Dracula rate drifted: ${actualDracula.toFixed(2)} vs ${expectDracula.toFixed(2)} per region`);
check(Math.abs(actualGrand - expectGrand) / expectGrand < 0.05,
	`grand castle rate drifted: ${actualGrand.toFixed(1)} vs ${expectGrand.toFixed(1)} per region`);
console.log(`castle set: grand ${actualGrand.toFixed(0)}/region (was ${expectGrand.toFixed(0)}), `
	+ `dracula ${actualDracula.toFixed(2)} (was ${expectDracula.toFixed(2)}); `
	+ `bases avoid warfront:grand_castles at ${placement.avoid_chunks} chunks via base_spread`);

const population = JSON.parse(fs.readFileSync(path.join(dataDir, "warfront_config/population.json"), "utf8"));
check(population.castle_citizens === 240, "castle economy must seed 240 citizens");

for (const faction of factions) {
	const file = path.join(dataDir, `structure/${faction}/castle.nbt`);
	const root = parse(fs.readFileSync(file)).root.v;
	const [sizeX, sizeY, sizeZ] = root.size.v.items;
	// Castles are no longer all 501: the importer's footprint follows its source radius, so
	// a build that warrants a wider crop gets one. Warfront pastes castles itself, so the
	// vanilla 128-block structure reach no longer caps them. What must not drift silently is
	// the size of a GIVEN castle, so each is pinned individually.
	check(sizeX === expected[faction].sizeX && sizeZ === expected[faction].sizeX
			&& sizeY === expected[faction].sizeY,
		`${faction}: imported structure dimensions changed unexpectedly `
		+ `(${sizeX}x${sizeY}x${sizeZ}, expected ${expected[faction].sizeX}x${expected[faction].sizeY})`);
	check(sizeX >= 501, `${faction}: a castle must be at least 501 blocks across, got ${sizeX}`);
	check(root.blocks.v.items.length === expected[faction].blocks,
		`${faction}: imported structure block count changed unexpectedly`);
	const palette = root.palette.v.items.map((entry) => entry.Name.v);
	const occupied = new Uint8Array(sizeX * sizeY * sizeZ);
	const townCenters = townCentersFor(sizeX);
	const townFeatures = townCenters.map(() => ({ bunks: 0, farmland: 0, jobs: 0 }));
	let loot = 0;
	let vault = 0;
	let common = 0;

	for (const block of root.blocks.v.items) {
		const [x, y, z] = block.pos.v.items;
		check(x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ,
			`${faction}: out-of-bounds block at ${x},${y},${z}`);
		check(block.state.v >= 0 && block.state.v < palette.length,
			`${faction}: invalid palette state ${block.state.v}`);
		const flat = (y * sizeZ + z) * sizeX + x;
		check(occupied[flat] === 0, `${faction}: duplicate block at ${x},${y},${z}`);
		occupied[flat] = 1;
		if (palette[block.state.v] === "minecraft:chest" || palette[block.state.v] === "minecraft:barrel") {
			const table = block.nbt?.v?.LootTable?.v;
			// Every container answers: rich, vault, or common. "minecraft:empty" is no
			// longer allowed — a castle where 97% of chests are deliberately blank reads
			// as broken loot, and was reported as exactly that.
			check(table === `warfront:castle/${faction}` || table === "warfront:castle/hidden_vault"
					|| table === "warfront:castle/common",
				`${faction}: container at ${x},${y},${z} has unmanaged inventory or loot (${table})`);
			if (table === `warfront:castle/${faction}`) loot++;
			if (table === "warfront:castle/hidden_vault") vault++;
			if (table === "warfront:castle/common") common++;
		}
		for (let i = 0; i < townCenters.length; i++) {
			const [cx, cz] = townCenters[i];
			if (Math.abs(x - cx) > 34 || Math.abs(z - cz) > 34) continue;
			const name = palette[block.state.v];
			if (name === "warfront:bunk") townFeatures[i].bunks++;
			if (name === "minecraft:farmland") townFeatures[i].farmland++;
			if (["minecraft:composter", "minecraft:smithing_table", "minecraft:crafting_table"].includes(name))
				townFeatures[i].jobs++;
		}
	}

	check(loot === expected[faction].loot, `${faction}: expected ${expected[faction].loot} rich-loot containers, got ${loot}`);
	check(vault >= 2, `${faction}: expected at least 2 hidden-vault chests, got ${vault}`);
	check(common > 0, `${faction}: expected common-loot containers, got none`);
	for (let i = 0; i < townFeatures.length; i++) {
		const feature = townFeatures[i];
		check(feature.bunks >= 6 && feature.farmland >= 100 && feature.jobs >= 2,
			`${faction}: town ${i + 1} is missing housing, farming, or jobs`);
	}

	// 32 town guards, a rampart watch of up to 12, 2 royal guards, 2 cellar sentries,
	// and the king.
	const soldiers = root.entities.v.items.filter((entity) => entity.nbt?.v?.id?.v === "warfront:soldier");
	const expectedBaked = 1 + expected[faction].officers + expected[faction].rankAndFile;
	check(soldiers.length === expectedBaked,
		`${faction}: expected ${expectedBaked} baked soldiers, got ${soldiers.length}`);
	check(soldiers.every((entity) => entity.nbt.v.warfront_faction?.v === faction
		&& entity.nbt.v.PersistenceRequired?.v === 1), `${faction}: invalid guard faction or persistence`);
	const kings = soldiers.filter((entity) => entity.nbt.v.warfront_rank?.v === "king");
	check(kings.length === 1, `${faction}: expected exactly one king`);
	check(soldiers.filter((entity) => entity.nbt.v.warfront_rank?.v === "officer").length === expected[faction].officers,
		`${faction}: officer count changed unexpectedly`);
	check(soldiers.filter((entity) => entity.nbt.v.warfront_rank?.v === "soldier").length === expected[faction].rankAndFile,
		`${faction}: rank-and-file count changed unexpectedly`);
	// The king presides from a chamber, not a basement: he must stand well above the
	// template floor. (Reported from play: "the king was spawned in the basement.")
	const kingY = kings[0].blockPos.v.items[1];
	check(kingY >= 20, `${faction}: king stands at template y=${kingY}, which is a basement`);

	// The occupancy sidecar is what shapes terrain blending; a stale one carves the
	// wrong columns silently. Regenerate with tools/gen-castle-occupancy.js after any
	// re-import.
	const occ = parse(fs.readFileSync(path.join(dataDir, `structure/${faction}/castle_occupancy.nbt`))).root.v;
	check(occ.width.v === sizeX, `${faction}: occupancy width ${occ.width.v} != castle ${sizeX}`);
	check(occ.min_y.v.length === sizeX * sizeX,
		`${faction}: occupancy has ${occ.min_y.v.length} columns, expected ${sizeX * sizeX}`);

	console.log(`${faction}: ${sizeX}x${sizeY}x${sizeZ}, ${root.blocks.v.items.length} blocks, `
		+ `${loot} rich + ${vault} vault + ${common} common loot, 4 working towns, `
		+ `${soldiers.length - 1} guards, 1 king at y=${kingY}`);
}

const dracula = parse(fs.readFileSync(path.join(dataDir, "structure/dracula/castle.nbt"))).root.v;
check(JSON.stringify(dracula.size.v.items) === JSON.stringify([501, 79, 501]),
	"Dracula: imported structure dimensions changed unexpectedly");
check(dracula.blocks.v.items.length === 1016249, "Dracula: imported structure block count changed unexpectedly");
const draculaPalette = dracula.palette.v.items.map((entry) => entry.Name.v);
let draculaLoot = 0;
for (const block of dracula.blocks.v.items) {
	const name = draculaPalette[block.state.v];
	check(name !== "warfront:bunk", "Dracula must not contain normal-castle town housing");
	if (name === "minecraft:chest" || name === "minecraft:barrel") {
		const table = block.nbt?.v?.LootTable?.v;
		check(table === "warfront:castle/dracula" || table === "warfront:castle/common",
			`Dracula contains an unmanaged inventory or loot table (${table})`);
		if (table === "warfront:castle/dracula") draculaLoot++;
	}
}
check(draculaLoot === 16, `Dracula: expected 16 rich-loot containers, got ${draculaLoot}`);
const draculaOcc = parse(fs.readFileSync(path.join(dataDir, "structure/dracula/castle_occupancy.nbt"))).root.v;
check(draculaOcc.width.v === 501 && draculaOcc.min_y.v.length === 501 * 501,
	"Dracula: occupancy sidecar missing or mismatched");
for (const wrapper of dracula.entities.v.items) {
	const entity = wrapper.nbt.v;
	check(!entity.WorldUUIDMost && !entity.WorldUUIDLeast, "Dracula: source-world UUID leaked into an entity");
	check(!["minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting"].includes(entity.id?.v),
		"Dracula: unsupported hanging entity would fail arbitrary worldgen placement");
}
check(dracula.entities.v.items.filter((wrapper) => wrapper.nbt.v.id?.v === "minecraft:armor_stand").length === 20,
	"Dracula: expected 20 free-standing decorative armor stands");
check(dracula.entities.v.items.filter((wrapper) => wrapper.nbt.v.id?.v === "warfront:dracula").length === 1,
	"Dracula: expected exactly one Count Dracula entity");
check(dracula.entities.v.items.length === 21, "Dracula: unexpected entity set");
console.log("dracula: 501x79x501, 1016249 blocks, 16 rich-loot containers, isolated, entities worldgen-safe");

console.log("grand castle selection, structures, garrisons, loot, towns, and economy: OK");
