#!/usr/bin/env node
// Generates 100 coherent topics × 10 sequential layers × 3 consequential choices.
// The result is exactly 3,000 player options arranged as genuinely deep branches,
// plus faction-specific replies for every choice.
const fs = require("node:fs");
const path = require("node:path");

const OUTPUT = path.join(__dirname, "..", "dialogue", "src", "deep_branches.json");
const slug = (value) => value.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");
const title = (value) => value.replace(/\b\w/g, (letter) => letter.toUpperCase());

const themes = [
	{
		id: "frontline", label: "Frontline Operations",
		subjects: ["contested river bridge", "ruined city district", "forest supply corridor", "mountain observation post", "marshland defensive line", "nighttime trench raid", "besieged border village", "exposed artillery ridge", "collapsed tunnel network", "winter forward position"],
		principle: "ground matters only when people, supply, and information can still move across it",
		failure: "commanders confused holding a map coordinate with controlling the situation around it",
		human: "the troops and civilians trapped closest to the objective pay for every delayed decision",
		dilemma: "taking the position quickly may save the campaign while destroying what made it worth taking",
		future: "a defensible settlement with roads, warning, and local trust must replace the temporary battlefield"
	},
	{
		id: "logistics", label: "War Logistics",
		subjects: ["winter ration reserve", "ammunition distribution chain", "wounded evacuation route", "contested water supply", "replacement armor program", "field repair workshop", "pack animal convoy", "captured equipment stockpile", "long-range supply column", "emergency fuel allotment"],
		principle: "a force fights only as long as its least glamorous supply remains dependable",
		failure: "planners optimized totals on paper and ignored distance, theft, breakage, and frightened drivers",
		human: "shortages turn ordinary soldiers into competitors and place impossible choices on quartermasters",
		dilemma: "fair distribution can weaken the decisive unit while military priority can condemn everyone else",
		future: "redundant local production and honest accounting must outlast the emergency convoy system"
	},
	{
		id: "intelligence", label: "Intelligence and Deception",
		subjects: ["captured enemy map", "double agent report", "missing scout patrol", "forged command order", "civilian informant network", "intercepted signal traffic", "deserter testimony", "false retreat rumor", "hidden observation team", "compromised courier route"],
		principle: "information becomes useful only after its source, motive, timing, and alternatives are tested",
		failure: "leaders accepted the report that best matched what they already wanted to believe",
		human: "scouts, informants, and accused civilians bear risks that analysts can too easily reduce to symbols",
		dilemma: "acting early exploits a narrow chance while waiting for certainty may protect innocents from a lie",
		future: "independent sources and accountable handling must replace personality-driven secret keeping"
	},
	{
		id: "command", label: "Command and Authority",
		subjects: ["unpopular retreat order", "inexperienced field officer", "refused civilian evacuation", "broken chain of command", "rival unit commander", "discipline after defeat", "promotion under fire", "unauthorized rescue mission", "political command interference", "soldier refusing an order"],
		principle: "authority survives pressure only when competence, responsibility, and trust point in the same direction",
		failure: "rank was treated as proof of judgment and obedience as proof that the order was sound",
		human: "subordinates carry out decisions while families and civilians inherit consequences they never approved",
		dilemma: "disobedience may prevent a crime or destroy the coordination keeping everyone alive",
		future: "clear review, trained successors, and permission to report failure must strengthen legitimate command"
	},
	{
		id: "morale", label: "Morale and Memory",
		subjects: ["unit after heavy losses", "homesick replacement troops", "burial after retreat", "fear before an assault", "veteran unable to rest", "rumor of certain defeat", "company victory tradition", "letters censored by command", "soldier grieving alone", "survivor blamed by comrades"],
		principle: "morale is not cheerfulness but the belief that effort, sacrifice, and leadership still have meaning",
		failure: "pain was hidden until silence became isolation and isolation became collapse",
		human: "each soldier carries a private history that formations and casualty lists cannot show",
		dilemma: "the truth may wound readiness today while a comforting lie can poison trust for years",
		future: "ritual, honest witness, rest, and useful work must give memory somewhere to live"
	},
	{
		id: "civilians", label: "Civilians in the War",
		subjects: ["occupied farming community", "refugee camp near the front", "merchant accused of smuggling", "village sheltering deserters", "children crossing a checkpoint", "hospital claimed by both sides", "harvest requisition dispute", "partisan attack aftermath", "evacuation during bombardment", "local council under occupation"],
		principle: "military necessity does not erase civilian agency, survival, or the duty to distinguish threat from inconvenience",
		failure: "fear and haste turned entire communities into suspects and every protest into supposed evidence",
		human: "people lose homes, livelihoods, relatives, and the right to make ordinary plans long after troops move on",
		dilemma: "tight control may stop an attack while collective punishment manufactures the next generation of enemies",
		future: "local voice, restitution, transparent rules, and civilian institutions must replace indefinite military control"
	},
	{
		id: "politics", label: "Politics and History",
		subjects: ["failed prewar treaty", "disputed border province", "faction founding myth", "old military alliance", "assassinated peace envoy", "wartime tax revolt", "neutral territory agreement", "propaganda about the enemy", "contested royal succession", "planned postwar election"],
		principle: "history guides judgment only when memory includes inconvenient causes as well as heroic conclusions",
		failure: "leaders used a selective past to make present compromise look like betrayal",
		human: "families divided by borders and loyalties live inside arguments officials describe as abstract",
		dilemma: "a flawed settlement may stop killing now while preserving the injustice that caused it",
		future: "shared records, enforceable guarantees, and institutions broader than one victor must carry the peace"
	},
	{
		id: "equipment", label: "Weapons and Technology",
		subjects: ["new armor field trial", "unreliable signal device", "captured siege weapon", "experimental medical kit", "scarce enchanted blade", "long-range observation glass", "prototype defensive barrier", "standardized field tool", "damaged transport machine", "weapon issued without training"],
		principle: "equipment creates an advantage only when training, maintenance, supply, and doctrine change with it",
		failure: "novelty was mistaken for readiness and demonstrations replaced testing under fatigue and confusion",
		human: "operators become unwilling test subjects when designers hide uncertainty behind technical language",
		dilemma: "early deployment may answer an urgent threat while transferring unknown risk to the least powerful users",
		future: "repairable designs, recorded failures, broad training, and restrained expectations must define adoption"
	},
	{
		id: "survival", label: "Field Survival",
		subjects: ["three-day blizzard march", "desert water emergency", "lost patrol at night", "flooded defensive camp", "disease in close quarters", "crossing without a map", "shelter under pursuit", "foraging in hostile country", "fire discipline in winter", "carrying a badly wounded soldier"],
		principle: "survival begins by controlling panic, inventorying reality, and protecting the group's ability to decide",
		failure: "people spent strength solving the loudest discomfort instead of the danger most likely to kill them",
		human: "exhaustion makes every burden personal and every fair division harder to recognize",
		dilemma: "saving the weakest preserves the group’s humanity while delay may expose every member to death",
		future: "shared skills, simple equipment, practiced signals, and trust must exist before the emergency begins"
	},
	{
		id: "peace", label: "The War's End",
		subjects: ["disarming rival militias", "returning displaced families", "trying accused officers", "rebuilding a destroyed town", "integrating former enemies", "paying veteran pensions", "clearing abandoned fortifications", "sharing contested farmland", "finding missing prisoners", "remembering the war publicly"],
		principle: "ending organized fighting is the opening condition of peace, not proof that peace already exists",
		failure: "victors treated silence as consent and reconstruction as a reward rather than a shared necessity",
		human: "survivors return with incompatible losses, expectations, guilt, and claims to the same damaged places",
		dilemma: "swift stability can conceal crimes while perfect justice pursued without restraint can restart the war",
		future: "security, truth, repair, and political belonging must advance together or undermine one another"
	}
];

const stages = [
	{
		name: "first principles", detail: (t) => t.principle,
		positive: (s) => `Ask respectfully: ${s}.`,
		neutral: (s) => `Discuss: ${s}.`,
		negative: (s) => `Challenge them on: ${s}.`
	},
	{
		name: "a concrete case", detail: (t) => `the clearest cases show that ${t.principle}`,
		positive: () => `Ask whose fate it changed.`,
		neutral: () => `Request one concrete case.`,
		negative: () => `Demand proof that it mattered.`
	},
	{
		name: "how failure began", detail: (t) => t.failure,
		positive: () => `Ask which warnings were missed.`,
		neutral: () => `Trace the first bad decision.`,
		negative: () => `Call the failure incompetence.`
	},
	{
		name: "competing priorities", detail: (t) => `every plan collides with time, uncertainty, and the fact that ${t.principle}`,
		positive: () => `Ask which duty is hardest to honor.`,
		neutral: () => `Identify the controlling tradeoff.`,
		negative: () => `Argue that force would solve it.`
	},
	{
		name: "the human cost", detail: (t) => t.human,
		positive: () => `Ask who bears the unheard burden.`,
		neutral: () => `Account for the human cost.`,
		negative: () => `Dismiss sympathy as a wartime luxury.`
	},
	{
		name: "doctrine under pressure", detail: (t) => `doctrine is useful only if it can face this fact: ${t.dilemma}`,
		positive: () => `Ask where their doctrine falls short.`,
		neutral: () => `Compare doctrine with field results.`,
		negative: () => `Accuse their faction of hiding behind dogma.`
	},
	{
		name: "the point of failure", detail: (t) => `the recurring point of failure is that ${t.failure}`,
		positive: () => `Ask which mistake must never recur.`,
		neutral: () => `Name the worst failure mode.`,
		negative: () => `Question whether they can be trusted.`
	},
	{
		name: "the moral boundary", detail: (t) => t.dilemma,
		positive: () => `Ask which moral line must hold.`,
		neutral: () => `State the governing ethical limit.`,
		negative: () => `Claim victory justifies any method.`
	},
	{
		name: "the strongest objection", detail: (t) => `the strongest objection remains that ${t.dilemma}`,
		positive: () => `Ask them to respect the opposing case.`,
		neutral: () => `Request the strongest counterargument.`,
		negative: () => `Suggest they are hiding something.`
	},
	{
		name: "what must endure", detail: (t) => t.future,
		positive: () => `Ask which lesson deserves to endure.`,
		neutral: () => `Request a lasting policy.`,
		negative: () => `Question whether their lesson matters.`
	}
];

const voices = {
	vostok: {
		positive: [
			(s, stage, detail) => `You ask seriously about the ${s}. At ${stage}, remember: ${detail}.`,
			(s, stage, detail) => `Respect earns the direct answer on the ${s}: ${detail}.`
		],
		neutral: [
			(s, stage, detail) => `The field answer on the ${s}, at ${stage}, is this: ${detail}.`,
			(s, stage, detail) => `Maps simplify the ${s}; boots teach that ${detail}.`
		],
		negative: [
			(s, stage, detail) => `Mock the ${s} after carrying its dead. At ${stage}, ${detail}.`,
			(s, stage, detail) => `That challenge is cheap. The price paid around the ${s} proved that ${detail}.`
		]
	},
	aegis: {
		positive: [
			(s, stage, detail) => `A responsible question. Our ${stage} review of the ${s} found that ${detail}.`,
			(s, stage, detail) => `Your concern is warranted. The record on the ${s} shows that ${detail}.`
		],
		neutral: [
			(s, stage, detail) => `The assessed finding on the ${s}, under ${stage}, is that ${detail}.`,
			(s, stage, detail) => `Separate preference from evidence: on the ${s}, ${detail}.`
		],
		negative: [
			(s, stage, detail) => `Hostility does not invalidate the ${stage} record on the ${s}: ${detail}.`,
			(s, stage, detail) => `Accusation logged. The answer on the ${s} remains: ${detail}.`
		]
	},
	sarab: {
		positive: [
			(s, stage, detail) => `You approach the ${s} openly. At ${stage}, the road teaches that ${detail}.`,
			(s, stage, detail) => `A careful question deserves water. On the ${s}, ${detail}.`
		],
		neutral: [
			(s, stage, detail) => `Look past the nearest footprint in the ${s}; you will see that ${detail}.`,
			(s, stage, detail) => `The patient account of the ${s}, at ${stage}, says that ${detail}.`
		],
		negative: [
			(s, stage, detail) => `A thorny question about the ${s} still has an answer: ${detail}.`,
			(s, stage, detail) => `Spite covers the ${s} with sand; the wind reveals that ${detail}.`
		]
	}
};

const options = [];
const responses = {};
for (const theme of themes) {
	for (const subject of theme.subjects) {
		const branch = `${theme.id}_${slug(subject)}`;
		for (let depth = 0; depth < stages.length; depth++) {
			const stage = stages[depth];
			for (const tone of ["positive", "neutral", "negative"]) {
				const response = `deep_${branch}_${depth + 1}_${tone}`;
				options.push({
					id: response,
					text: stage[tone](subject),
					response,
					tone,
					branch,
					branch_depth: depth,
					next_depth: depth === stages.length - 1 ? -1 : depth + 1,
					topic: `${theme.label}: ${title(subject)}`,
					weight: depth === 0 ? 14 : 10
				});
				responses[response] = {};
				for (const [faction, factionVoices] of Object.entries(voices)) {
					const detail = stage.detail(theme);
					responses[response][faction] = {
						neutral: factionVoices[tone].map((line) => line(subject, stage.name, detail))
					};
				}
			}
		}
	}
}

if (options.length !== 3000) throw new Error(`Expected exactly 3000 options, generated ${options.length}`);
fs.writeFileSync(OUTPUT, JSON.stringify({ category: "deep_branches", options, responses }, null, 2) + "\n");
console.log(`gen-deep-dialogue: ${options.length} branch options, ${Object.keys(responses).length * 6} response lines`);
