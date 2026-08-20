#!/usr/bin/env node
// Structural regression checks for the three imported normal grand castles.
const fs = require("node:fs");
const path = require("node:path");
const { parse } = require("./nbt");

const rootDir = path.join(__dirname, "..");
const dataDir = path.join(rootDir, "src/main/resources/data/warfront");
const factions = ["aegis", "sarab", "vostok"];
const townCenters = [[250, 72], [428, 250], [250, 428], [72, 250]];
const expected = {
	aegis: { sizeY: 189, blocks: 2173213, loot: 17, garrison: [64, 80] },
	sarab: { sizeY: 106, blocks: 878814, loot: 24, garrison: [72, 88] },
	vostok: { sizeY: 192, blocks: 463156, loot: 24, garrison: [84, 96] },
};

function check(condition, message) {
	if (!condition) throw new Error(message);
}

// All four castles live in ONE set of their own, and `bases` carries an exclusion zone
// pointing at it. Minecraft enforces spacing only *within* a set, so nothing about the
// castles' own spacing keeps a base out of one — the exclusion zone is the only thing that
// does, and tools/verify-base-spacing.js re-derives its required radius from these NBTs on
// every run. Keeping all four in one set means that single reference covers every castle,
// including Dracula.
const CASTLE_WEIGHT = 15;
const DRACULA_WEIGHT = 1;
const structureSet = JSON.parse(fs.readFileSync(
	path.join(dataDir, "worldgen/structure_set/grand_castles.json"), "utf8"));
check(structureSet.structures.length === 4,
	`castle set must hold three faction castles plus Dracula, found ${structureSet.structures.length}`);
check(!fs.existsSync(path.join(dataDir, "worldgen/structure_set/dracula_castles.json")),
	"dracula_castles.json is back — Dracula shares the castle set so one exclusion zone covers it");

const basesSet = JSON.parse(fs.readFileSync(
	path.join(dataDir, "worldgen/structure_set/bases.json"), "utf8"));
check(!basesSet.structures.some((entry) => entry.structure.includes("castle")),
	"no castle may sit in the bases set — its spacing is sized for 76-to-124-block plates");
const zone = basesSet.placement.exclusion_zone;
check(zone && zone.other_set === "warfront:grand_castles",
	"bases must carry an exclusion zone pointing at warfront:grand_castles, or a 501-block "
	+ "castle has nothing keeping a base out of it");

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

// The weights are not taste. Dracula used to get its rarity from a set of its own at
// spacing 640 with frequency 0.35; sharing the castle set it gets it from weight instead,
// and 1 against 15/15/15 reproduces that rate. Pinning the arithmetic means a later weight
// edit cannot quietly turn the rarest thing in the mod into a common one.
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
	+ `bases exclude warfront:grand_castles at ${zone.chunk_count} chunks`);

const population = JSON.parse(fs.readFileSync(path.join(dataDir, "warfront_config/population.json"), "utf8"));
check(population.castle_citizens === 240, "castle economy must seed 240 citizens");

for (const faction of factions) {
	const file = path.join(dataDir, `structure/${faction}/castle.nbt`);
	const root = parse(fs.readFileSync(file)).root.v;
	const [sizeX, sizeY, sizeZ] = root.size.v.items;
	check(sizeX === 501 && sizeZ === 501 && sizeY === expected[faction].sizeY,
		`${faction}: imported structure dimensions changed unexpectedly`);
	check(root.blocks.v.items.length === expected[faction].blocks,
		`${faction}: imported structure block count changed unexpectedly`);
	const palette = root.palette.v.items.map((entry) => entry.Name.v);
	const occupied = new Uint8Array(sizeX * sizeY * sizeZ);
	const townFeatures = townCenters.map(() => ({ bunks: 0, farmland: 0, jobs: 0 }));
	let loot = 0;

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
			check(table === `warfront:castle/${faction}` || table === "minecraft:empty",
				`${faction}: container at ${x},${y},${z} has unmanaged inventory or loot`);
			if (table === `warfront:castle/${faction}`) loot++;
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
	for (let i = 0; i < townFeatures.length; i++) {
		const feature = townFeatures[i];
		check(feature.bunks >= 6 && feature.farmland >= 100 && feature.jobs >= 2,
			`${faction}: town ${i + 1} is missing housing, farming, or jobs`);
	}

	const soldiers = root.entities.v.items.filter((entity) => entity.nbt?.v?.id?.v === "warfront:soldier");
	check(soldiers.length === 33, `${faction}: expected 32 guards plus one king, got ${soldiers.length}`);
	check(soldiers.every((entity) => entity.nbt.v.warfront_faction?.v === faction
		&& entity.nbt.v.PersistenceRequired?.v === 1), `${faction}: invalid guard faction or persistence`);
	check(soldiers.filter((entity) => entity.nbt.v.warfront_rank?.v === "king").length === 1,
		`${faction}: expected exactly one king`);
	check(soldiers.filter((entity) => entity.nbt.v.warfront_rank?.v === "officer").length === 8,
		`${faction}: expected eight town officers`);
	check(soldiers.filter((entity) => entity.nbt.v.warfront_rank?.v === "soldier").length === 24,
		`${faction}: expected 24 town soldiers`);

	console.log(`${faction}: ${sizeX}x${sizeY}x${sizeZ}, ${root.blocks.v.items.length} blocks, `
		+ `${loot} rich-loot containers, 4 working towns, 32 guards, 1 king`);
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
		check(table === "warfront:castle/dracula" || table === "minecraft:empty",
			"Dracula contains an unmanaged inventory or loot table");
		if (table === "warfront:castle/dracula") draculaLoot++;
	}
}
check(draculaLoot === 16, `Dracula: expected 16 rich-loot containers, got ${draculaLoot}`);
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
