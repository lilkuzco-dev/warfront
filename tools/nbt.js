// Minimal full-spec NBT reader/writer (big-endian, gzip-aware). Zero dependencies.
// Values are {t, v}: compounds are plain objects of name -> value (insertion-ordered),
// lists are {itemType, items}. Longs are BigInt. Shared by retheme-structure.js and
// nbt-info.js; the write side supersedes the inline writer in gen-structures.js.
const zlib = require("node:zlib");

const TAG = { end: 0, byte: 1, short: 2, int: 3, long: 4, float: 5, double: 6,
	byteArray: 7, string: 8, list: 9, compound: 10, intArray: 11, longArray: 12 };

function parse(buf) {
	if (buf[0] === 0x1f && buf[1] === 0x8b) {
		buf = zlib.gunzipSync(buf);
	}
	const state = { buf, off: 0 };
	const rootType = u8(state);
	const rootName = readString(state);
	return { rootName, root: { t: rootType, v: readPayload(state, rootType) } };
}

function u8(s) { return s.buf[s.off++]; }
function i16(s) { const v = s.buf.readInt16BE(s.off); s.off += 2; return v; }
function u16(s) { const v = s.buf.readUInt16BE(s.off); s.off += 2; return v; }
function i32(s) { const v = s.buf.readInt32BE(s.off); s.off += 4; return v; }
function i64(s) { const v = s.buf.readBigInt64BE(s.off); s.off += 8; return v; }
function f32(s) { const v = s.buf.readFloatBE(s.off); s.off += 4; return v; }
function f64(s) { const v = s.buf.readDoubleBE(s.off); s.off += 8; return v; }
function readString(s) {
	const len = u16(s);
	const v = s.buf.toString("utf8", s.off, s.off + len);
	s.off += len;
	return v;
}

function readPayload(s, type) {
	switch (type) {
		case TAG.byte: { const v = s.buf.readInt8(s.off); s.off += 1; return v; }
		case TAG.short: return i16(s);
		case TAG.int: return i32(s);
		case TAG.long: return i64(s);
		case TAG.float: return f32(s);
		case TAG.double: return f64(s);
		case TAG.byteArray: { const n = i32(s); const v = s.buf.subarray(s.off, s.off + n); s.off += n; return Buffer.from(v); }
		case TAG.string: return readString(s);
		case TAG.list: {
			const itemType = u8(s);
			const n = i32(s);
			const items = [];
			for (let i = 0; i < n; i++) items.push(readPayload(s, itemType));
			return { itemType, items };
		}
		case TAG.compound: {
			const obj = {};
			for (;;) {
				const t = u8(s);
				if (t === TAG.end) return obj;
				const name = readString(s);
				obj[name] = { t, v: readPayload(s, t) };
			}
		}
		case TAG.intArray: { const n = i32(s); const v = []; for (let i = 0; i < n; i++) v.push(i32(s)); return v; }
		case TAG.longArray: { const n = i32(s); const v = []; for (let i = 0; i < n; i++) v.push(i64(s)); return v; }
		default: throw new Error(`unknown tag type ${type} at ${s.off}`);
	}
}

// ---------- writer ----------
function writePayload(out, type, v) {
	switch (type) {
		case TAG.byte: { const b = Buffer.alloc(1); b.writeInt8(v); out.push(b); break; }
		case TAG.short: { const b = Buffer.alloc(2); b.writeInt16BE(v); out.push(b); break; }
		case TAG.int: { const b = Buffer.alloc(4); b.writeInt32BE(v); out.push(b); break; }
		case TAG.long: { const b = Buffer.alloc(8); b.writeBigInt64BE(BigInt(v)); out.push(b); break; }
		case TAG.float: { const b = Buffer.alloc(4); b.writeFloatBE(v); out.push(b); break; }
		case TAG.double: { const b = Buffer.alloc(8); b.writeDoubleBE(v); out.push(b); break; }
		case TAG.byteArray: { const b = Buffer.alloc(4); b.writeInt32BE(v.length); out.push(b, Buffer.from(v)); break; }
		case TAG.string: { const s = Buffer.from(v, "utf8"); const b = Buffer.alloc(2); b.writeUInt16BE(s.length); out.push(b, s); break; }
		case TAG.list: {
			out.push(Buffer.from([v.itemType]));
			const b = Buffer.alloc(4);
			b.writeInt32BE(v.items.length);
			out.push(b);
			for (const item of v.items) writePayload(out, v.itemType, item);
			break;
		}
		case TAG.compound: {
			for (const [name, val] of Object.entries(v)) {
				out.push(Buffer.from([val.t]));
				const s = Buffer.from(name, "utf8");
				const b = Buffer.alloc(2);
				b.writeUInt16BE(s.length);
				out.push(b, s);
				writePayload(out, val.t, val.v);
			}
			out.push(Buffer.from([TAG.end]));
			break;
		}
		case TAG.intArray: { const b = Buffer.alloc(4 + v.length * 4); b.writeInt32BE(v.length); v.forEach((x, i) => b.writeInt32BE(x, 4 + i * 4)); out.push(b); break; }
		case TAG.longArray: { const b = Buffer.alloc(4 + v.length * 8); b.writeInt32BE(v.length); v.forEach((x, i) => b.writeBigInt64BE(BigInt(x), 4 + i * 8)); out.push(b); break; }
		default: throw new Error(`unknown tag type ${type}`);
	}
}

function write(root, rootName = "") {
	const out = [Buffer.from([root.t])];
	const s = Buffer.from(rootName, "utf8");
	const b = Buffer.alloc(2);
	b.writeUInt16BE(s.length);
	out.push(b, s);
	writePayload(out, root.t, root.v);
	return zlib.gzipSync(Buffer.concat(out));
}

// convenience constructors mirroring the reader's value shape
const N = {
	byte: (v) => ({ t: TAG.byte, v }),
	short: (v) => ({ t: TAG.short, v }),
	int: (v) => ({ t: TAG.int, v }),
	long: (v) => ({ t: TAG.long, v }),
	float: (v) => ({ t: TAG.float, v }),
	double: (v) => ({ t: TAG.double, v }),
	string: (v) => ({ t: TAG.string, v }),
	list: (itemType, items) => ({ t: TAG.list, v: { itemType, items } }),
	compound: (obj) => ({ t: TAG.compound, v: obj }),
};

module.exports = { TAG, N, parse, write };
