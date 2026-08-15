#!/usr/bin/env node
// Prints a structure NBT's size, palette, jigsaw blocks, and notable furnishing counts.
// Usage: node tools/nbt-info.js <file.nbt> [more.nbt ...]
const fs = require("node:fs");
const { parse } = require("./nbt");

for (const file of process.argv.slice(2)) {
	const { root } = parse(fs.readFileSync(file));
	const r = root.v;
	// list items are raw payloads: compound items are plain objects of name -> {t,v}
	const size = r.size.v.items;
	const palette = (r.palette ?? { v: { items: r.palettes.v.items[0].items } }).v.items;
	const names = palette.map((p) => p.Name.v);
	const blocks = r.blocks.v.items;
	const counts = new Map();
	for (const b of blocks) counts.set(names[b.state.v], (counts.get(names[b.state.v]) ?? 0) + 1);
	console.log(`== ${file}`);
	console.log(`size ${size.join("x")}  blocks ${blocks.length}  palette ${palette.length}  DataVersion ${r.DataVersion?.v}`);
	const interesting = [...counts.entries()].filter(([n]) => /bed|chest|barrel|jigsaw|banner|smithing|fletching|grindstone|lectern|furnace|anvil/.test(n));
	if (interesting.length) console.log("  furnishing:", interesting.map(([n, c]) => `${n.replace("minecraft:", "")}x${c}`).join(" "));
	for (const b of blocks) {
		if (names[b.state.v].includes("jigsaw") && b.nbt) {
			const j = b.nbt.v;
			console.log(`  jigsaw @${b.pos.v.items.join(",")} name=${j.name?.v} target=${j.target?.v} pool=${j.pool?.v} final=${j.final_state?.v} joint=${j.joint?.v}`);
		}
	}
	const top = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8);
	console.log("  top blocks:", top.map(([n, c]) => `${n.replace("minecraft:", "")}x${c}`).join(" "));
}
