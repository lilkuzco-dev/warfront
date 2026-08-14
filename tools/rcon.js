#!/usr/bin/env node
// Minimal Minecraft RCON client for headless dev-server testing.
// Usage: node tools/rcon.js <port> <password> "<command>" ["<command>" ...]
// Prints each command's response on its own line (prefixed with ">" echo).

const net = require("node:net");

const [port, password, ...commands] = process.argv.slice(2);
if (!port || !password || commands.length === 0) {
	console.error('usage: node tools/rcon.js <port> <password> "<cmd>" ...');
	process.exit(2);
}

function packet(id, type, body) {
	const payload = Buffer.from(body, "utf8");
	const buf = Buffer.alloc(14 + payload.length);
	buf.writeInt32LE(10 + payload.length, 0);
	buf.writeInt32LE(id, 4);
	buf.writeInt32LE(type, 8);
	payload.copy(buf, 12);
	return buf;
}

const sock = net.connect(Number(port), "127.0.0.1");
let buffer = Buffer.alloc(0);
let nextId = 1;
let state = "auth";
let cmdIndex = 0;

sock.on("connect", () => sock.write(packet(nextId, 3, password)));
sock.on("error", (err) => {
	console.error(`rcon error: ${err.message}`);
	process.exit(1);
});
sock.on("data", (chunk) => {
	buffer = Buffer.concat([buffer, chunk]);
	while (buffer.length >= 4) {
		const len = buffer.readInt32LE(0);
		if (buffer.length < 4 + len) break;
		const id = buffer.readInt32LE(4);
		const body = buffer.toString("utf8", 12, 4 + len - 2);
		buffer = buffer.subarray(4 + len);
		if (state === "auth") {
			if (id === -1) {
				console.error("rcon auth failed");
				process.exit(1);
			}
			state = "cmd";
			send();
		} else {
			console.log(`> ${commands[cmdIndex]}`);
			console.log(body.replace(/§./g, ""));
			cmdIndex++;
			if (cmdIndex >= commands.length) {
				sock.end();
				process.exit(0);
			}
			send();
		}
	}
});
function send() {
	sock.write(packet(++nextId, 2, commands[cmdIndex]));
}
setTimeout(() => {
	console.error("rcon timeout");
	process.exit(1);
}, 300000);
