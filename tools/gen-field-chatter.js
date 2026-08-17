#!/usr/bin/env node
// Builds the large, deterministic field-chatter authoring corpus. The compact
// theme table below expands to 1,000 player lines and 600 faction replies.
const fs = require("node:fs");
const path = require("node:path");

const OUTPUT = path.join(__dirname, "..", "dialogue", "src", "field_chatter.json");

const questions = [
	"What can you tell me about {subject}?",
	"How does your unit handle {subject}?",
	"What's the first lesson a recruit gets about {subject}?",
	"I've heard soldiers argue about {subject}. Where do you stand?",
	"What usually goes wrong with {subject}?",
	"Is there a right way to approach {subject}?",
	"Have the realities of {subject} changed much since the war began?",
	"What do civilians misunderstand about {subject}?",
	"If I had to deal with {subject} tomorrow, what should I remember?",
	"Tell me one thing your officers never mention about {subject}."
];

const themes = [
	{
		id: "logistics",
		subjects: ["supply caches", "ammunition reserves", "ration convoys", "winter stores", "water discipline",
			"spare parts", "field depots", "pack trains", "fuel allotments", "salvage crews"],
		replies: {
			vostok: ["We learn {subject} by shortage and remember it by scars.",
				"On the line, {subject} is measured by whether dawn finds us standing."],
			aegis: ["Our handling of {subject} is tracked, audited, and corrected before it becomes a battlefield failure.",
				"For {subject}, procedure is simply memory that does not die with the last patrol."],
			sarab: ["With {subject}, the wise store enough for tomorrow and never reveal where tomorrow is hidden.",
				"The desert prices {subject} dearly; patience keeps us from paying twice."]
		}
	},
	{
		id: "patrolcraft",
		subjects: ["night patrols", "sentry rotations", "challenge passwords", "trail markers", "scout spacing",
			"silent signals", "relief watches", "border posts", "pursuit tracks", "return routes"],
		replies: {
			vostok: ["For {subject}, trust the soldier beside you and distrust every quiet patch ahead.",
				"We practice {subject} until tired feet can do it without asking the brain."],
			aegis: ["Success with {subject} depends on understanding every interval and contingency before departure.",
				"Our rule for {subject} is clear communication, overlapping coverage, and no improvised heroics."],
			sarab: ["Good practice around {subject} leaves no story for the road to repeat.",
				"In {subject}, we listen to the empty spaces; danger is often the sound that chose not to happen."]
		}
	},
	{
		id: "terrain",
		subjects: ["river crossings", "ridge lines", "marsh paths", "forest roads", "ruined bridges",
			"mountain passes", "dry gullies", "cave shelters", "open fields", "village lanes"],
		replies: {
			vostok: ["Around {subject}, the advantage goes to the side willing to dig in before the shooting starts.",
				"We study {subject} with our boots; maps rarely mention the mud that kills momentum."],
			aegis: ["We survey and classify {subject}, then assign a controlled route through.",
				"Before approaching {subject}, we mark sight lines, exits, and the cost of every exposed meter."],
			sarab: ["Every stretch involving {subject} has a mood. Read it before asking it to carry your life.",
				"The impatient see only {subject}; the patient see the hidden roads curling around it."]
		}
	},
	{
		id: "weather",
		subjects: ["hard rain", "deep snow", "dust storms", "summer heat", "freezing nights",
			"heavy fog", "strong crosswinds", "sudden floods", "lightning", "long droughts"],
		replies: {
			vostok: ["When {subject} arrives, everyone suffers, so we make certain the enemy suffers first.",
				"You do not defeat {subject}; you keep working until it grows tired before you do."],
			aegis: ["We put {subject} in the operational forecast, not in excuses written afterward.",
				"We adjust equipment, timing, and exposure for {subject}; adaptation is a scheduled task."],
			sarab: ["The arrival of {subject} is the sky speaking plainly. Only fools argue with the message.",
				"When {subject} arrives, slow your breath and let the world reveal its new shape." ]
		}
	},
	{
		id: "equipment",
		subjects: ["blade care", "armor repairs", "worn boots", "field packs", "signal horns",
			"lantern oil", "climbing rope", "spare shields", "tool kits", "helmet straps"],
		replies: {
			vostok: ["Care around {subject} feels like dull work until neglect tries to kill you.",
				"We handle {subject} at day's end, no matter how badly the hands shake."],
			aegis: ["Every item connected with {subject} has an inspection standard; reliability should never depend on mood.",
				"For {subject}, document the fault, correct it, and verify the correction before issue."],
			sarab: ["Treat {subject} with respect and it may remember your hands when darkness comes.",
				"With {subject}, small care given early saves a great price paid late." ]
		}
	},
	{
		id: "camp_life",
		subjects: ["field kitchens", "bunk assignments", "firewood details", "laundry lines", "latrine duty",
			"morning drills", "evening roll call", "mess tins", "guard dogs", "camp merchants"],
		replies: {
			vostok: ["Attention to {subject} keeps soldiers human when the front is trying to turn us into tools.",
				"Everyone complains about {subject}; everyone notices the instant it stops."],
			aegis: ["Work around {subject} is routine, and routine keeps a camp functional under pressure.",
				"Good order around {subject} prevents small friction from becoming operational damage."],
			sarab: ["Life around {subject} is part of the campfire's bargain: each person gives a little so all may rest.",
				"You learn a company's true character by watching how it treats {subject}." ]
		}
	},
	{
		id: "battlecraft",
		subjects: ["holding a breach", "breaking an ambush", "defending high ground", "escorting civilians", "fighting at night",
			"covering a retreat", "taking prisoners", "clearing ruins", "protecting healers", "surviving a siege"],
		replies: {
			vostok: ["With {subject}, the first rule is hold long enough for the next soldier to breathe.",
				"The work of {subject} strips away speeches; discipline and stubborn feet are what remain."],
			aegis: ["Success at {subject} demands assigned sectors, clear authority, and reserves kept outside the first mistake.",
				"We rehearse {subject} because confusion consumes more soldiers than a prepared enemy."],
			sarab: ["In {subject}, survive the enemy's first certainty and their doubt will open a door.",
				"The task of {subject} is a contest of patience disguised as violence; spend neither carelessly." ]
		}
	},
	{
		id: "intelligence",
		subjects: ["captured maps", "enemy rumors", "coded messages", "false trails", "local guides",
			"deserter stories", "signal fires", "intercepted orders", "missing scouts", "suspicious travelers"],
		replies: {
			vostok: ["For {subject}, believe the part that costs the speaker something and test the rest.",
				"We put {subject} beside what our boots have seen; truth usually survives the argument."],
			aegis: ["Reports involving {subject} are graded by source, corroboration, and how badly the enemy wants them believed.",
				"One report about {subject} is noise; several independent reports become intelligence."],
			sarab: ["Information about {subject} is a footprint in wind: useful only if you know which way the liar was walking.",
				"Listen twice when considering {subject}—once to the words, once to the silence around them." ]
		}
	},
	{
		id: "morale",
		subjects: ["battlefield fear", "homesickness", "bad news", "unit pride", "veteran superstitions",
			"new recruits", "letters from home", "funeral rites", "victory songs", "the quiet before battle"],
		replies: {
			vostok: ["The burden of {subject} is carried like every other load: shared when possible, alone when necessary.",
				"We do not pretend the weight of {subject} is weakness. We simply refuse to let it choose our direction."],
			aegis: ["Command treats {subject} as a readiness concern, acknowledging it instead of hiding it.",
				"We give {subject} a place and a time; outside those bounds, the mission still requires us."],
			sarab: ["The matter of {subject} visits every fire. Wisdom offers it tea without surrendering the best seat.",
				"Carry {subject} gently; a clenched hand can hold nothing else when help arrives." ]
		}
	},
	{
		id: "society",
		subjects: ["officer promotions", "soldier pay", "war taxes", "civilian volunteers", "camp justice",
			"military medals", "faction banners", "oath taking", "veteran pensions", "peace negotiations"],
		replies: {
			vostok: ["The value of {subject} lies in serving the people holding the line, not the chairs behind it.",
				"We judge {subject} by who bears the weight and who merely signs the paper."],
			aegis: ["Decisions about {subject} require a transparent standard, a record, and review when power is abused.",
				"Without accountable rules, decisions around {subject} become favoritism wearing a uniform."],
			sarab: ["Choices around {subject} must balance the living, the absent, and those who will inherit the consequence.",
				"A wise camp handles {subject} in full light, where every witness may remember the scale." ]
		}
	}
];

const fill = (template, subject) => template
	.replaceAll("{subject}", subject)
	.replaceAll("{Subject}", subject.charAt(0).toUpperCase() + subject.slice(1));
const slug = (value) => value.replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");

const options = [];
const responses = {};
for (const theme of themes) {
	for (const subject of theme.subjects) {
		const subjectId = slug(subject);
		const response = `field_${theme.id}_${subjectId}`;
		questions.forEach((question, index) => options.push({
			id: `${response}_${index + 1}`,
			text: fill(question, subject),
			response,
			conditions: { disposition: ["neutral", "positive"] },
			weight: 8
		}));
		responses[response] = {};
		for (const [faction, lines] of Object.entries(theme.replies)) {
			responses[response][faction] = { neutral: lines.map((line) => fill(line, subject)) };
		}
	}
}

fs.writeFileSync(OUTPUT, JSON.stringify({ category: "field_chatter", options, responses }, null, 2) + "\n");
console.log(`gen-field-chatter: ${options.length} options, ${Object.keys(responses).length * 6} response lines`);
