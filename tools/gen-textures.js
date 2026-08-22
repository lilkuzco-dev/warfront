#!/usr/bin/env node
// Warfront textures: per-faction soldier uniforms are recolors of the vanilla player
// skin; citizen skins are CC0 legacy skins converted to the modern 64x64 layout; the
// sandbag station texture and mod icon are procedural.
// Requires `unzip` + a populated Loom cache. Usage: node tools/gen-textures.js

const zlib = require("node:zlib");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

// faction id -> uniform color (keep in sync with data/warfront/warfront_factions/*.json)
const FACTIONS = {
	vostok: 0x8b1a1a,
	aegis: 0x1e4d8b,
	sarab: 0x8b7b3d,
};

// ---------- PNG decode/encode (same minimal codec as the vibranium pipeline) ----------
function decodePng(buf) {
	let off = 8;
	let w, h, bitDepth, colorType;
	const palette = [], trns = [], idat = [];
	while (off < buf.length) {
		const len = buf.readUInt32BE(off);
		const type = buf.toString("ascii", off + 4, off + 8);
		const data = buf.subarray(off + 8, off + 8 + len);
		if (type === "IHDR") {
			w = data.readUInt32BE(0); h = data.readUInt32BE(4);
			bitDepth = data[8]; colorType = data[9];
		} else if (type === "PLTE") for (let i = 0; i < data.length; i += 3) palette.push([data[i], data[i + 1], data[i + 2]]);
		else if (type === "tRNS") trns.push(...data);
		else if (type === "IDAT") idat.push(data);
		off += 12 + len;
	}
	const raw = zlib.inflateSync(Buffer.concat(idat));
	const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
	const bpp = Math.max(1, (channels * bitDepth) / 8);
	const stride = Math.ceil((w * channels * bitDepth) / 8);
	const out = Buffer.alloc(h * stride);
	let prev = Buffer.alloc(stride);
	for (let y = 0; y < h; y++) {
		const filter = raw[y * (stride + 1)];
		const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
		for (let x = 0; x < stride; x++) {
			const a = x >= bpp ? line[x - bpp] : 0;
			const b = prev[x];
			const c = x >= bpp ? prev[x - bpp] : 0;
			if (filter === 1) line[x] = (line[x] + a) & 0xff;
			else if (filter === 2) line[x] = (line[x] + b) & 0xff;
			else if (filter === 3) line[x] = (line[x] + ((a + b) >> 1)) & 0xff;
			else if (filter === 4) {
				const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
				line[x] = (line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xff;
			}
			out[y * stride + x] = line[x];
		}
		prev = line;
	}
	const bitAt = (row, i) => {
		const bitPos = i * bitDepth;
		return (out[row * stride + (bitPos >> 3)] >> (8 - bitDepth - (bitPos & 7))) & ((1 << bitDepth) - 1);
	};
	const px = Buffer.alloc(w * h * 4);
	for (let y = 0; y < h; y++)
		for (let x = 0; x < w; x++) {
			const i = (y * w + x) * 4;
			if (colorType === 6) out.copy(px, i, y * stride + x * 4, y * stride + x * 4 + 4);
			else if (colorType === 2) { out.copy(px, i, y * stride + x * 3, y * stride + x * 3 + 3); px[i + 3] = 255; }
			else if (colorType === 3) {
				const idx = bitAt(y, x);
				const [r, g, b] = palette[idx] ?? [0, 0, 0];
				px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = trns[idx] ?? 255;
			} else if (colorType === 0) {
				const v = Math.round(bitAt(y, x) * (255 / ((1 << bitDepth) - 1)));
				px[i] = px[i + 1] = px[i + 2] = v; px[i + 3] = 255;
			}
		}
	return { w, h, px };
}
const CRC_TABLE = (() => {
	const t = new Int32Array(256);
	for (let n = 0; n < 256; n++) {
		let c = n;
		for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		t[n] = c;
	}
	return t;
})();
const crc32 = (buf) => {
	let c = 0xffffffff;
	for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
	return (c ^ 0xffffffff) >>> 0;
};
function chunk(type, data) {
	const len = Buffer.alloc(4);
	len.writeUInt32BE(data.length);
	const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(body));
	return Buffer.concat([len, body, crc]);
}
function encodePng(w, h, px) {
	const ihdr = Buffer.alloc(13);
	ihdr.writeUInt32BE(w, 0);
	ihdr.writeUInt32BE(h, 4);
	ihdr[8] = 8;
	ihdr[9] = 6;
	const raw = Buffer.alloc(h * (1 + w * 4));
	for (let y = 0; y < h; y++) {
		raw[y * (1 + w * 4)] = 0;
		px.copy(raw, y * (1 + w * 4) + 1, y * w * 4, (y + 1) * w * 4);
	}
	return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}
function rgbToHsl(r, g, b) {
	r /= 255; g /= 255; b /= 255;
	const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
	if (mx === mn) return [0, 0, l];
	const d = mx - mn;
	const s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
	let h;
	if (mx === r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
	else if (mx === g) h = ((b - r) / d + 2) / 6;
	else h = ((r - g) / d + 4) / 6;
	return [h, s, l];
}
function hslToRgb(h, s, l) {
	if (s === 0) {
		const v = Math.round(l * 255);
		return [v, v, v];
	}
	const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
	const p = 2 * l - q;
	const f = (t) => {
		t = ((t % 1) + 1) % 1;
		if (t < 1 / 6) return p + (q - p) * 6 * t;
		if (t < 1 / 2) return q;
		if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
		return p;
	};
	return [Math.round(f(h + 1 / 3) * 255), Math.round(f(h) * 255), Math.round(f(h - 1 / 3) * 255)];
}

function findClientJar() {
	const root = path.join(os.homedir(), ".gradle/caches/fabric-loom");
	for (const v of fs.readdirSync(root)) {
		for (const name of ["minecraft-client-only.jar", "minecraft-client.jar"]) {
			const jar = path.join(root, v, name);
			if (fs.existsSync(jar)) {
				try {
					execFileSync("unzip", ["-l", jar, "assets/minecraft/textures/entity/player/wide/steve.png"], { stdio: "pipe" });
					return jar;
				} catch { /* keep looking */ }
			}
		}
	}
	throw new Error("no client jar with player skin found in loom cache");
}

const ROOT = path.join(__dirname, "..");
const ASSETS = path.join(ROOT, "src/main/resources/assets/warfront");
const jar = findClientJar();
const steve = decodePng(execFileSync("unzip", ["-p", jar, "assets/minecraft/textures/entity/player/wide/steve.png"], { maxBuffer: 1 << 22 }));

// Minetest Skins Pack 1 uses the legacy 64x32 Minecraft layout. Modern player
// models have independent left-limb UVs in the lower half, so mirror the old
// right-limb faces into those slots exactly as Minecraft's legacy loader does.
function legacySkinToModern(legacy) {
	if (legacy.w !== 64 || legacy.h !== 32) {
		throw new Error(`citizen source must be a legacy 64x32 skin, got ${legacy.w}x${legacy.h}`);
	}
	const out = Buffer.alloc(64 * 64 * 4);
	legacy.px.copy(out, 0);
	const copy = (sx, sy, w, h, dx, dy, mirrorX) => {
		for (let y = 0; y < h; y++) {
			for (let x = 0; x < w; x++) {
				const sourceX = sx + (mirrorX ? w - 1 - x : x);
				const source = (sy * 64 + sourceX + y * 64) * 4;
				const target = ((dy + y) * 64 + dx + x) * 4;
				legacy.px.copy(out, target, source, source + 4);
			}
		}
	};
	// left leg: top, bottom, right, front, left, back
	copy(4, 16, 4, 4, 20, 48, true);
	copy(8, 16, 4, 4, 24, 48, true);
	copy(0, 20, 4, 12, 24, 52, true);
	copy(4, 20, 4, 12, 20, 52, true);
	copy(8, 20, 4, 12, 16, 52, true);
	copy(12, 20, 4, 12, 28, 52, true);
	// left arm: top, bottom, right, front, left, back
	copy(44, 16, 4, 4, 36, 48, true);
	copy(48, 16, 4, 4, 40, 48, true);
	copy(40, 20, 4, 12, 40, 52, true);
	copy(44, 20, 4, 12, 36, 52, true);
	copy(48, 20, 4, 12, 32, 52, true);
	copy(52, 20, 4, 12, 44, 52, true);

	// Fully opaque legacy files use filler pixels in the optional hat area. The
	// vanilla legacy loader clears that area rather than rendering a black shell.
	let hatHasTransparency = false;
	for (let y = 0; y < 16; y++) for (let x = 32; x < 64; x++) {
		if (out[(y * 64 + x) * 4 + 3] < 128) hatHasTransparency = true;
	}
	if (!hatHasTransparency) for (let y = 0; y < 16; y++) for (let x = 32; x < 64; x++) {
		out[(y * 64 + x) * 4 + 3] = 0;
	}
	return out;
}

for (const profession of ["miner", "farmer", "builder", "trader", "laborer"]) {
	const source = decodePng(fs.readFileSync(path.join(ROOT, `tools/assets/citizen-skins/${profession}.png`)));
	const file = path.join(ASSETS, `textures/entity/citizen/${profession}.png`);
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, encodePng(64, 64, legacySkinToModern(source)));
	console.log(`wrote citizen/${profession}.png`);
}

// Count Dracula: TenPlus1's CC BY-SA 4.0 Vampire skin from Simple Skins, pinned
// and attributed in tools/assets/vampire-skins/README.md. Keep the source bytes
// intact and perform only the standard legacy-to-modern limb conversion here.
{
	const source = decodePng(fs.readFileSync(path.join(ROOT, "tools/assets/vampire-skins/dracula.png")));
	const file = path.join(ASSETS, "textures/entity/dracula.png");
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, encodePng(64, 64, legacySkinToModern(source)));
	console.log("wrote entity/dracula.png");
}

// Count Dracula's cape: authored here, no upstream source. Vanilla's cape model samples
// the 22x17 region at the origin of a 64x32 texture (a 10x16x1 box unwrapped at 0,0).
// A charcoal cape was READ against his black suit in the battery and did not exist to
// the eye, so it is crimson: deep crimson outer face with a black border, brighter
// crimson inner face, black one-pixel edges.
{
	const px = Buffer.alloc(64 * 32 * 4);
	const set = (x, y, r, g, b) => {
		const i = (y * 64 + x) * 4;
		px[i] = r; px[i + 1] = g; px[i + 2] = b; px[i + 3] = 255;
	};
	for (let y = 0; y < 17; y++) {
		for (let x = 0; x < 22; x++) {
			const fold = ((x * 7 + y * 3) % 5 === 0) ? -18 : 0;
			const outer = x >= 1 && x < 11 && y >= 1;
			const inner = x >= 12 && y >= 1;
			const border = (outer && (x === 1 || x === 10 || y === 16)) || (inner && (x === 12 || x === 21 || y === 16));
			if (border) set(x, y, 18, 12, 16);
			else if (outer) set(x, y, 128 + fold, 10, 22);
			else if (inner) set(x, y, 176 + fold, 22, 34);
			else set(x, y, 18, 12, 16);
		}
	}
	fs.writeFileSync(path.join(ASSETS, "textures/entity/dracula_cape.png"), encodePng(64, 32, px));
	console.log("wrote entity/dracula_cape.png");
}

// uniform = every body/limb pixel below the head rows (y >= 16) hue-mapped to the
// faction color, keeping per-pixel luminance for cloth shading
for (const [faction, color] of Object.entries(FACTIONS)) {
	const [fh, fsat] = rgbToHsl((color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff);
	const out = Buffer.from(steve.px);
	for (let y = 16; y < steve.h; y++) {
		for (let x = 0; x < steve.w; x++) {
			const i = (y * steve.w + x) * 4;
			if (out[i + 3] < 8) continue;
			const [, , l] = rgbToHsl(out[i], out[i + 1], out[i + 2]);
			const [r, g, b] = hslToRgb(fh, Math.min(0.75, fsat * 0.9), Math.max(0.12, l * 0.9));
			out[i] = r;
			out[i + 1] = g;
			out[i + 2] = b;
		}
	}
	const file = path.join(ASSETS, `textures/entity/soldier/${faction}.png`);
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, encodePng(steve.w, steve.h, out));
	console.log(`wrote soldier/${faction}.png`);
}

// sandbag station: procedural stacked-sack pattern, tan with stitch shadows
{
	const px = Buffer.alloc(16 * 16 * 4);
	const base = [181, 160, 118], dark = [140, 122, 86], light = [203, 183, 141], stitch = [110, 95, 66];
	for (let y = 0; y < 16; y++)
		for (let x = 0; x < 16; x++) {
			const row = Math.floor(y / 4);
			const offset = row % 2 === 0 ? 0 : 4;
			const inRow = (x + offset) % 8;
			let c = base;
			if (y % 4 === 3) c = stitch; // horizontal seams
			else if (inRow === 7) c = dark; // vertical sack edges
			else if (inRow === 0) c = light;
			else if ((x * 7 + y * 13) % 11 === 0) c = dark; // burlap noise
			const i = (y * 16 + x) * 4;
			px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = 255;
		}
	const file = path.join(ASSETS, "textures/block/sandbag_station.png");
	fs.mkdirSync(path.dirname(file), { recursive: true });
	fs.writeFileSync(file, encodePng(16, 16, px));
	console.log("wrote block/sandbag_station.png");
}

// garrison bunk (v0.2.0): olive blanket with pillow band on top, canvas + frame sides
{
	const top = Buffer.alloc(16 * 16 * 4);
	const blanket = [86, 104, 60], blanketDark = [70, 86, 48], pillow = [196, 192, 178], pillowShade = [168, 164, 150], frame = [92, 74, 52];
	for (let y = 0; y < 16; y++)
		for (let x = 0; x < 16; x++) {
			let c;
			if (x === 0 || x === 15) c = frame; // side rails
			else if (y < 4) c = (x + y) % 5 === 0 ? pillowShade : pillow; // pillow band
			else if (y === 4) c = blanketDark; // blanket fold
			else c = (x * 5 + y * 3) % 7 === 0 ? blanketDark : blanket; // wool weave
			const i = (y * 16 + x) * 4;
			top[i] = c[0]; top[i + 1] = c[1]; top[i + 2] = c[2]; top[i + 3] = 255;
		}
	const side = Buffer.alloc(16 * 16 * 4);
	const canvas = [122, 116, 96], canvasDark = [102, 96, 78];
	for (let y = 0; y < 16; y++)
		for (let x = 0; x < 16; x++) {
			let c;
			if (y < 10) c = [0, 0, 0]; // above the 6px cot: unused (uv maps 10..16)
			else if (y === 10 || x === 0 || x === 15) c = frame; // top edge + legs
			else c = (x * 3 + y * 7) % 6 === 0 ? canvasDark : canvas;
			const i = (y * 16 + x) * 4;
			side[i] = c[0]; side[i + 1] = c[1]; side[i + 2] = c[2]; side[i + 3] = y < 10 ? 0 : 255;
		}
	fs.writeFileSync(path.join(ASSETS, "textures/block/bunk_top.png"), encodePng(16, 16, top));
	fs.writeFileSync(path.join(ASSETS, "textures/block/bunk_side.png"), encodePng(16, 16, side));
	console.log("wrote block/bunk_top.png, block/bunk_side.png");
}

// C2 displays: original phosphor-black military electronics, authored as pixels here.
{
	const screen = Buffer.alloc(16 * 16 * 4);
	const projector = Buffer.alloc(16 * 16 * 4);
	for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
		let c = (x === 0 || y === 0 || x === 15 || y === 15) ? [45, 55, 48]
			: ((x + y) % 9 === 0 ? [17, 34, 28] : [10, 20, 17]);
		if ((x === 2 || x === 13) && y === 13) c = [70, 180, 92];
		let i = (y * 16 + x) * 4;
		screen[i] = c[0]; screen[i + 1] = c[1]; screen[i + 2] = c[2]; screen[i + 3] = 255;

		c = (x === 0 || y === 0 || x === 15 || y === 15) ? [36, 42, 38] : [61, 70, 64];
		if ((x + y * 3) % 13 === 0) c = [78, 88, 80];
		if (x >= 5 && x <= 10 && y >= 5 && y <= 10) c = [28, 90, 61];
		i = (y * 16 + x) * 4;
		projector[i] = c[0]; projector[i + 1] = c[1]; projector[i + 2] = c[2]; projector[i + 3] = 255;
	}
	fs.writeFileSync(path.join(ASSETS, "textures/block/screen_case.png"), encodePng(16, 16, screen));
	fs.writeFileSync(path.join(ASSETS, "textures/block/projector.png"), encodePng(16, 16, projector));
	console.log("wrote block/screen_case.png, block/projector.png");
}

// icon: three faction-color chevrons on dark field
{
	const px = Buffer.alloc(16 * 16 * 4);
	const colors = Object.values(FACTIONS);
	for (let y = 0; y < 16; y++)
		for (let x = 0; x < 16; x++) {
			const i = (y * 16 + x) * 4;
			let c = [22, 26, 22];
			const band = Math.floor((y - Math.abs(x - 8) * 0.6 - 2) / 4);
			if (band >= 0 && band < 3 && y - Math.abs(x - 8) * 0.6 - 2 - band * 4 < 2.2) {
				const color = colors[band];
				c = [(color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff];
			}
			px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = 255;
		}
	// upscale 8x
	const big = Buffer.alloc(128 * 128 * 4);
	for (let y = 0; y < 128; y++)
		for (let x = 0; x < 128; x++) {
			const src = ((y >> 3) * 16 + (x >> 3)) * 4;
			px.copy(big, (y * 128 + x) * 4, src, src + 4);
		}
	fs.writeFileSync(path.join(ASSETS, "icon.png"), encodePng(128, 128, big));
	console.log("wrote icon.png");
}
