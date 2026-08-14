#!/usr/bin/env node
// Warfront textures: per-faction soldier uniforms are recolors of the vanilla player
// skin (clothing/limb UV regions hue-mapped to the faction color, head untouched);
// the sandbag station texture and mod icon are procedural.
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
