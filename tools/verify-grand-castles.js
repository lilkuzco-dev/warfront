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

const structureSet = JSON.parse(fs.readFileSync(
	path.join(dataDir, "worldgen/structure_set/grand_castles.json"), "utf8"));
check(structureSet.structures.length === 3, "normal castle set must contain exactly three entries");
for (const faction of factions) {
	const entry = structureSet.structures.find((candidate) => candidate.structure === `warfront:${faction}_castle`);
	check(entry?.weight === 1, `${faction} castle must have weight 1`);
	const factionConfig = JSON.parse(fs.readFileSync(path.join(dataDir, `warfront_factions/${faction}.json`), "utf8"));
	check(JSON.stringify(factionConfig.population.garrison.castle) === JSON.stringify(expected[faction].garrison),
		`${faction}: castle garrison range changed unexpectedly`);
	const lootTable = fs.readFileSync(path.join(dataDir, `loot_table/castle/${faction}.json`), "utf8");
	for (const prize of ["minecraft:diamond", "minecraft:golden_apple", "minecraft:totem_of_undying"])
		check(lootTable.includes(prize), `${faction}: rich castle loot is missing ${prize}`);
}
check(!structureSet.structures.some((entry) => entry.structure.includes("dracula")),
	"Dracula must not be in the normal castle set");

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

const draculaSet = JSON.parse(fs.readFileSync(
	path.join(dataDir, "worldgen/structure_set/dracula_castles.json"), "utf8"));
check(draculaSet.structures.length === 1 && draculaSet.structures[0].structure === "warfront:dracula_castle",
	"Dracula must remain in its own structure set");
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
