#!/usr/bin/env node
// The corpus quality gate (Stage 4C.4). Validates dialogue/src/*.json:
//   - schema + condition/effect vocabulary
//   - globally unique ids; no duplicate / near-duplicate texts
//   - every option's response class resolves to >=2 lines per applicable faction,
//     with a non-empty resolution for every band the option can appear in
//   - HARD GATES: substantial corpus plus ten fully authored ten-layer branches
//   - deep-response craft: silent gates, bounded length, and no colon chains
//   - coverage matrix (category x faction x band-group), empty cells flagged
// Usage: node tools/validate-dialogue.js   (exit 1 on failure)
const fs = require("node:fs");
const path = require("node:path");

const SRC = path.join(__dirname, "..", "dialogue/src");
const FACTIONS = ["vostok", "aegis", "sarab"];
const BANDS = ["vengeful", "hostile", "cold", "neutral", "warm", "friendly", "devoted"];
const GROUPS = { vengeful: "negative", hostile: "negative", cold: "negative", neutral: "neutral",
	warm: "positive", friendly: "positive", devoted: "positive" };
const STANDINGS = ["hostile", "wary", "neutral", "friendly", "trusted"];
const ROLES = ["grunt", "officer", "quartermaster"];
const PERSONALITIES = ["patient", "professional", "proud", "volatile"];
const LOCATIONS = ["in_base", "patrol", "wilderness"];
const TIMES = ["day", "night"];
const CONTRACT_STATES = ["none", "offered", "active", "complete_ready"];
const CONDITION_KEYS = new Set(["faction", "standing", "disposition", "role", "location", "time",
	"personality", "recent_combat", "has_killed_this_faction", "active_contract", "tech_level_min", "tech_level_max",
	"requires_item", "requires_count"]);
const OPTION_KEYS = new Set(["id", "text", "response", "tone", "conditions", "effects", "weight", "once_per",
	"cooldown_minutes", "exit", "branch", "branch_depth", "next_depth", "topic"]);
const EFFECT_TYPES = new Set(["standing", "disposition", "take_items", "give_items", "open_trade",
	"offer_order", "accept_order", "decline_order", "turn_in_order", "abandon_order", "intel",
	"provoke", "end"]);
const EVENT_TYPES = new Set(["attacked_soldier", "killed_soldier", "destroyed_property", "trespassed",
	"traded", "gifted", "contract_completed", "contract_failed", "contract_target", "aided_in_combat",
	"apology_tribute_paid", "penance_completed", "insulted", "bribed", "friendly_words", "threatened"]);

const errors = [];
const warnings = [];
const options = [];
const responseClasses = {}; // class -> faction -> band -> count
const allTexts = new Map(); // normalized -> id
const allIds = new Set();
const allProse = []; // [where, line] for the corpus-wide prose gates
const forbiddenNpcMechanics = /\b(?:your standing|trust level|conversation depth|respect earns|standing improves|dialogue option)\b/i;
const forbiddenDeepPreamble = /\b(?:here is the field version|recorded finding|assessment confirmed|a careful question gets daylight|follow the second footprint|throw sand if you must|request heard|objection recorded|the curtain stays drawn|a quiet knock is still a knock)\b/i;

const normalize = (t) => t.toLowerCase().replace(/[^a-z0-9 ]+/g, "").replace(/\s+/g, " ").trim();
const tokens = (t) => new Set(normalize(t).split(" "));
function jaccard(a, b) {
	let inter = 0;
	for (const x of a) if (b.has(x)) inter++;
	return inter / (a.size + b.size - inter);
}

const listOf = (v) => v === undefined ? [] : Array.isArray(v) ? v : [v];

for (const file of fs.readdirSync(SRC).filter((f) => f.endsWith(".json")).sort()) {
	let src;
	try {
		src = JSON.parse(fs.readFileSync(path.join(SRC, file), "utf8"));
	} catch (e) {
		errors.push(`${file}: JSON parse error: ${e.message}`);
		continue;
	}
	const category = src.category;
	if (!category) {
		errors.push(`${file}: missing category`);
		continue;
	}
	for (const option of src.options ?? []) {
		const where = `${file}:${option.id ?? "?"}`;
		for (const key of Object.keys(option)) {
			if (!OPTION_KEYS.has(key)) errors.push(`${where}: unknown option field "${key}"`);
		}
		if (!option.id || !option.text || !option.response) {
			errors.push(`${where}: id, text, response are required`);
			continue;
		}
		if (option.tone !== undefined && !["positive", "neutral", "negative", "exit"].includes(option.tone)) {
			errors.push(`${where}: bad tone "${option.tone}"`);
		}
		if (option.branch) {
			if (!option.topic) errors.push(`${where}: branched option needs a topic`);
			if (!Number.isInteger(option.branch_depth) || option.branch_depth < 0) {
				errors.push(`${where}: branch_depth must be a non-negative integer`);
			}
			if (!Number.isInteger(option.next_depth) || option.next_depth < -1) {
				errors.push(`${where}: next_depth must be -1 or a non-negative integer`);
			}
		}
		// Branched follow-ups repeat by design: the screen shows the topic above them,
		// so "What happened during the latest attempt?" is contextual, not templated.
		allProse.push([`option ${where}`, option.text, Boolean(option.branch)]);
		if (allIds.has(option.id)) errors.push(`${where}: duplicate id`);
		allIds.add(option.id);
		const norm = normalize((option.branch ? `${option.topic} ` : "") + option.text);
		if (allTexts.has(norm)) errors.push(`${where}: duplicate text of ${allTexts.get(norm)}`);
		else allTexts.set(norm, option.id);
		const c = option.conditions ?? {};
		for (const key of Object.keys(c)) {
			if (!CONDITION_KEYS.has(key)) errors.push(`${where}: unknown condition "${key}"`);
		}
		const checkVocab = (key, allowed) => {
			for (const v of listOf(c[key])) {
				if (!allowed.includes(v)) errors.push(`${where}: bad ${key} value "${v}"`);
			}
		};
		checkVocab("faction", FACTIONS);
		checkVocab("standing", STANDINGS);
		checkVocab("disposition", [...BANDS, "negative", "positive"]);
		checkVocab("role", ROLES);
		checkVocab("personality", PERSONALITIES);
		checkVocab("location", LOCATIONS);
		checkVocab("time", TIMES);
		checkVocab("active_contract", CONTRACT_STATES);
		for (const effect of option.effects ?? []) {
			if (!EFFECT_TYPES.has(effect.type)) errors.push(`${where}: unknown effect type "${effect.type}"`);
			if (effect.type === "disposition" && !EVENT_TYPES.has(effect.event)) {
				errors.push(`${where}: unknown disposition event "${effect.event}"`);
			}
			if (effect.type === "take_items" && (!c.requires_item || c.requires_item !== effect.item)) {
				errors.push(`${where}: take_items needs matching conditions.requires_item`);
			}
			if (effect.type === "open_trade" && !listOf(c.role).includes("quartermaster")) {
				errors.push(`${where}: open_trade must be role-gated to quartermaster`);
			}
		}
		options.push({ ...option, category, file });
	}
	for (const [cls, byFaction] of Object.entries(src.responses ?? {})) {
		const clsMap = responseClasses[cls] ?? (responseClasses[cls] = {});
		for (const [faction, byBand] of Object.entries(byFaction)) {
			if (faction !== "any" && !FACTIONS.includes(faction)) {
				errors.push(`${file}:${cls}: bad faction "${faction}"`);
				continue;
			}
			const fMap = clsMap[faction] ?? (clsMap[faction] = {});
			for (const [band, lines] of Object.entries(byBand)) {
				if (![...BANDS, "negative", "positive", "neutral"].includes(band)) {
					errors.push(`${file}:${cls}.${faction}: bad band "${band}"`);
					continue;
				}
				fMap[band] = (fMap[band] ?? 0) + lines.length;
					for (const line of lines) {
						const norm = normalize(line);
						allProse.push([`${file}:${cls}.${faction}.${band}`, line]);
						if (forbiddenNpcMechanics.test(line)) {
							errors.push(`${file}:${cls}.${faction}.${band}: NPC line leaks a dialogue mechanic: "${line}"`);
						}
						if (file === "deep_branches.json") {
							if (line.length > 220) errors.push(`${file}:${cls}.${faction}.${band}: line exceeds 220 characters`);
							if (forbiddenDeepPreamble.test(line)) {
								errors.push(`${file}:${cls}.${faction}.${band}: templated preamble instead of a direct answer`);
							}
							if ((line.match(/:/g) ?? []).length > 1) {
								errors.push(`${file}:${cls}.${faction}.${band}: colon-chained response`);
							}
							if (normalize(line).split(" ").length < 7) {
								errors.push(`${file}:${cls}.${faction}.${band}: response is too thin to convey content or a natural refusal`);
							}
						}
					if (file !== "deep_branches.json" && norm.length > 0 && allTexts.has(norm)) {
						errors.push(`${file}:${cls}.${faction}.${band}: duplicate line "${line.slice(0, 40)}..."`);
					} else if (file !== "deep_branches.json") allTexts.set(norm, `${cls}.${faction}.${band}`);
				}
			}
		}
	}
}

// near-duplicate scan on option texts (token Jaccard)
const optionTexts = options.map((o) => {
	const comparable = (o.branch ? `${o.topic} ` : "") + o.text;
	return { id: o.id, tok: tokens(comparable), len: tokens(comparable).size };
});
for (let i = 0; i < optionTexts.length; i++) {
	for (let j = i + 1; j < optionTexts.length; j++) {
		const a = optionTexts[i], b = optionTexts[j];
		if (a.len >= 5 && b.len >= 5 && jaccard(a.tok, b.tok) >= 0.85) {
			errors.push(`near-duplicate option texts: ${a.id} ~ ${b.id}`);
		}
	}
}

// response resolution per option
const resolvedLineCount = (cls, faction, band) => {
	const byFaction = responseClasses[cls];
	if (!byFaction) return -1;
	const byBand = byFaction[faction] ?? byFaction.any;
	if (!byBand) return 0;
	return byBand[band] ?? byBand[GROUPS[band]] ?? byBand.neutral ?? Math.max(0, ...Object.values(byBand), 0);
};
for (const option of options) {
	const factions = listOf(option.conditions?.faction).length ? listOf(option.conditions.faction) : FACTIONS;
	const dispositions = listOf(option.conditions?.disposition);
	const bands = dispositions.length
		? BANDS.filter((b) => dispositions.includes(b) || dispositions.includes(GROUPS[b]))
		: BANDS;
	for (const faction of factions) {
		if (!responseClasses[option.response]) {
			errors.push(`${option.id}: response class "${option.response}" does not exist`);
			break;
		}
		let total = 0;
		for (const band of bands) {
			const n = resolvedLineCount(option.response, faction, band);
			if (n === 0) {
				errors.push(`${option.id}: class ${option.response} resolves to 0 lines for ${faction}/${band}`);
			}
			total += n;
		}
		const byBand = responseClasses[option.response][faction] ?? responseClasses[option.response].any ?? {};
		const factionTotal = Object.values(byBand).reduce((a, b) => a + b, 0);
		if (factionTotal < 2) {
			errors.push(`${option.id}: class ${option.response} has <2 total lines for ${faction}`);
		}
	}
}

// coverage matrix: category x faction x band-group
const categories = [...new Set(options.map((o) => o.category))].sort();
const matrix = {};
for (const option of options) {
	const factions = listOf(option.conditions?.faction).length ? listOf(option.conditions.faction) : FACTIONS;
	const dispositions = listOf(option.conditions?.disposition);
	const groups = dispositions.length
		? [...new Set(BANDS.filter((b) => dispositions.includes(b) || dispositions.includes(GROUPS[b])).map((b) => GROUPS[b]))]
		: ["negative", "neutral", "positive"];
	for (const faction of factions) {
		for (const group of groups) {
			matrix[`${option.category}|${faction}|${group}`] = (matrix[`${option.category}|${faction}|${group}`] ?? 0) + 1;
		}
	}
}
console.log("\nCoverage matrix (options usable per category x faction x band-group):");
console.log("category".padEnd(16) + FACTIONS.map((f) => (f + " n/-/+").padEnd(18)).join(""));
for (const category of categories) {
	let row = category.padEnd(16);
	for (const faction of FACTIONS) {
		const neg = matrix[`${category}|${faction}|negative`] ?? 0;
		const neu = matrix[`${category}|${faction}|neutral`] ?? 0;
		const pos = matrix[`${category}|${faction}|positive`] ?? 0;
		row += `${neu}/${neg}/${pos}`.padEnd(18);
		if (neu === 0 && neg === 0 && pos === 0) {
			errors.push(`EMPTY coverage cell: ${category} x ${faction}`);
		} else if (neg === 0 || pos === 0) {
			warnings.push(`thin coverage: ${category} x ${faction} (${neu}/${neg}/${pos})`);
		}
	}
	console.log(row);
}

// ---------------------------------------------------------------------------
// Prose gates (0.3.1). The first corpus reached scale by slot-filling a topic
// noun into shared sentence frames, which is what produced "ammunition reserves
// IS measured", "mountain passes ... curling around IT", and a hundred copies of
// one question. These three checks make that class of failure fail the build.
// ---------------------------------------------------------------------------

// 1. This war has bows, blades and siege engines. It has no guns.
const FIREARMS = /\b(guns?|gunfire|gunshots?|gunpowder|rifles?|pistols?|bullets?|muskets?|firearms?|cartridges?|snipers?)\b/i;
for (const [where, line] of allProse) {
	const hit = line.match(FIREARMS);
	if (hit) errors.push(`${where}: firearms vocabulary "${hit[0]}" — this setting has no guns`);
}

// 2. (Removed.) A regex cannot resolve an antecedent, and English has too many
//    singular nouns ending in -s: "another pair of eyes is ready", "a stockpile
//    nobody inspects is a rumour", "Friends is a rank you haven't earned" all
//    tripped it. Forty-nine false positives and no true ones is not a gate, it is
//    noise that teaches people to ignore warnings. The frame check below prevents
//    the slot-filling that caused the real disagreements in the first place.

// 3. A sentence frame that reappears is a template growing back.
function frameOf(text) {
	const w = text.toLowerCase().match(/[a-z']+/g) ?? [];
	if (w.length < 6) return null;
	return w.slice(0, 4).join(" ") + " \u2026 " + w.slice(-4).join(" ");
}
const RESPONSE_FRAME_LIMIT = 4;
const OPTION_FRAME_LIMIT = 3;
const respFrames = new Map();
const optFrames = new Map();
for (const [where, line, branched] of allProse) {
	const f = frameOf(line);
	if (!f || branched) continue;
	const bucket = where.startsWith("option ") ? optFrames : respFrames;
	bucket.set(f, (bucket.get(f) ?? 0) + 1);
}
for (const [f, n] of respFrames) {
	if (n > RESPONSE_FRAME_LIMIT) errors.push(`response frame reused ${n} times (limit ${RESPONSE_FRAME_LIMIT}): "${f}"`);
}
for (const [f, n] of optFrames) {
	if (n > OPTION_FRAME_LIMIT) errors.push(`player-option frame reused ${n} times (limit ${OPTION_FRAME_LIMIT}): "${f}"`);
}

// exit options sanity
const universalExits = options.filter((o) => o.exit
	&& !listOf(o.conditions?.faction).length && !listOf(o.conditions?.disposition).length
	&& !listOf(o.conditions?.role).length && !listOf(o.conditions?.location).length);
if (universalExits.length < 3) {
	errors.push(`need >=3 universally-available exit options, have ${universalExits.length}`);
}

// Deep-branch integrity: each branch must be ten sequential layers, with one
// positive, neutral, and negative route at every layer and a terminal tenth layer.
const branches = new Map();
for (const option of options.filter((o) => o.branch)) {
	const depths = branches.get(option.branch) ?? new Map();
	const atDepth = depths.get(option.branch_depth) ?? [];
	atDepth.push(option);
	depths.set(option.branch_depth, atDepth);
	branches.set(option.branch, depths);
}
for (const [branch, depths] of branches) {
	for (let depth = 0; depth < 10; depth++) {
		const atDepth = depths.get(depth) ?? [];
		const tones = new Set(atDepth.map((o) => o.tone));
		if (atDepth.length !== 3 || !["positive", "neutral", "negative"].every((tone) => tones.has(tone))) {
			errors.push(`${branch}: depth ${depth} must contain exactly positive/neutral/negative options`);
		}
		for (const option of atDepth) {
			const expected = depth === 9 ? -1 : depth + 1;
			if (option.next_depth !== expected) errors.push(`${option.id}: expected next_depth ${expected}`);
		}
	}
}
if (branches.size < 10) errors.push(`need >=10 authored deep dialogue branches, have ${branches.size}`);

// Every deep answer must have a real silent disclosure gate: refusal/deflection for
// negative disposition, a guarded fact for neutral, and fuller substance for positive.
for (const option of options.filter((o) => o.category === "deep_branches")) {
	const byFaction = responseClasses[option.response] ?? {};
	for (const faction of FACTIONS) {
		const pools = byFaction[faction] ?? {};
		for (const band of ["negative", "neutral", "positive"]) {
			if ((pools[band] ?? 0) < 1) errors.push(`${option.id}: missing ${faction}/${band} disclosure pool`);
		}
	}
}

let responseLineTotal = 0;
for (const byFaction of Object.values(responseClasses)) {
	for (const byBand of Object.values(byFaction)) {
		for (const n of Object.values(byBand)) responseLineTotal += n;
	}
}

console.log(`\n${options.length} player options, ${responseLineTotal} response lines, ${Object.keys(responseClasses).length} classes`);
if (warnings.length) {
	console.log(`\n${warnings.length} warnings` + (process.env.VERBOSE ? ":\n  " + warnings.join("\n  ") : " (VERBOSE=1 to list)"));
}
// Count gates lowered in 0.3.1. The previous floors (2700/4600) were only reachable
// by slot-filling a noun into shared frames — the very thing that produced the broken
// English. Breadth is now bought with authored writing, so the floor reflects what can
// actually be written well. Raise these only by writing more, never by templating.
if (options.length < 1700) {
	errors.push(`HARD COUNT GATE: ${options.length} player options < 1700`);
}
if (responseLineTotal < 4200) {
	errors.push(`HARD COUNT GATE: ${responseLineTotal} response lines < 4200`);
}
if (errors.length) {
	console.error(`\nFAIL — ${errors.length} errors:`);
	for (const error of errors.slice(0, 60)) console.error("  " + error);
	if (errors.length > 60) console.error(`  ... and ${errors.length - 60} more`);
	process.exit(1);
}
console.log("PASS");
