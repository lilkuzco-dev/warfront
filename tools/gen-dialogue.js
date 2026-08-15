#!/usr/bin/env node
// Builds the shipped dialogue corpus from the authoring sources.
//   dialogue/src/<category>.json  (inline text)  →
//     data/warfront/warfront_dialogue/options/<category>.json   (lang keys)
//     data/warfront/warfront_dialogue/responses/<category>.json (lang keys)
//     assets/warfront/lang/en_us.json = tools/lang-base.json + all corpus text
// Keys: dialogue.warfront.opt.<id> and dialogue.warfront.resp.<class>.<faction>.<band>.<n>
// Usage: node tools/gen-dialogue.js
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const SRC = path.join(ROOT, "dialogue/src");
const DATA = path.join(ROOT, "src/main/resources/data/warfront/warfront_dialogue");
const LANG_FILE = path.join(ROOT, "src/main/resources/assets/warfront/lang/en_us.json");

const lang = JSON.parse(fs.readFileSync(path.join(__dirname, "lang-base.json"), "utf8"));
let optionCount = 0;
let responseLineCount = 0;

for (const file of fs.readdirSync(SRC).filter((f) => f.endsWith(".json")).sort()) {
	const src = JSON.parse(fs.readFileSync(path.join(SRC, file), "utf8"));
	const category = src.category;

	if (src.options && src.options.length) {
		const outOptions = [];
		for (const option of src.options) {
			const key = `dialogue.warfront.opt.${option.id}`;
			lang[key] = option.text;
			const out = { ...option, text: key };
			outOptions.push(out);
			optionCount++;
		}
		fs.mkdirSync(path.join(DATA, "options"), { recursive: true });
		fs.writeFileSync(path.join(DATA, "options", `${category}.json`),
			JSON.stringify({ category, options: outOptions }, null, 1) + "\n");
	}

	if (src.responses && Object.keys(src.responses).length) {
		const outClasses = {};
		for (const [cls, byFaction] of Object.entries(src.responses)) {
			outClasses[cls] = {};
			for (const [faction, byBand] of Object.entries(byFaction)) {
				outClasses[cls][faction] = {};
				for (const [band, lines] of Object.entries(byBand)) {
					outClasses[cls][faction][band] = lines.map((text, i) => {
						const key = `dialogue.warfront.resp.${cls}.${faction}.${band}.${i + 1}`;
						lang[key] = text;
						responseLineCount++;
						return key;
					});
				}
			}
		}
		fs.mkdirSync(path.join(DATA, "responses"), { recursive: true });
		fs.writeFileSync(path.join(DATA, "responses", `${category}.json`),
			JSON.stringify({ classes: outClasses }, null, 1) + "\n");
	}
}

fs.writeFileSync(LANG_FILE, JSON.stringify(lang, null, 2) + "\n");
console.log(`gen-dialogue: ${optionCount} options, ${responseLineCount} response lines, ${Object.keys(lang).length} lang entries`);
