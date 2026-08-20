#!/usr/bin/env node
// Binary bridge used by render-structure.py so rendering reuses Warfront's tested
// NBT parser instead of carrying a second JavaScript-NBT implementation.
const fs = require("node:fs");
const { parse } = require("./nbt");

const file = process.argv[2];
if (!file) process.exit(2);
const root = parse(fs.readFileSync(file)).root.v;
const size = root.size.v.items;
const palette = root.palette.v.items.map((entry) => entry.Name.v);
const blocks = root.blocks.v.items;
const chunks = [];
const header = Buffer.alloc(24);
header.write("WFVX", 0, "ascii");
for (let i = 0; i < 3; i++) header.writeUInt32BE(size[i], 4 + i * 4);
header.writeUInt32BE(palette.length, 16);
header.writeUInt32BE(blocks.length, 20);
chunks.push(header);
for (const name of palette) {
	const bytes = Buffer.from(name, "utf8");
	const length = Buffer.alloc(2);
	length.writeUInt16BE(bytes.length);
	chunks.push(length, bytes);
}
const records = Buffer.alloc(blocks.length * 8);
for (let i = 0; i < blocks.length; i++) {
	const [x, y, z] = blocks[i].pos.v.items;
	records.writeUInt16BE(x, i * 8);
	records.writeUInt16BE(y, i * 8 + 2);
	records.writeUInt16BE(z, i * 8 + 4);
	records.writeUInt16BE(blocks[i].state.v, i * 8 + 6);
}
chunks.push(records);
process.stdout.write(Buffer.concat(chunks));
