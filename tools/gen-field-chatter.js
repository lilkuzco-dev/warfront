#!/usr/bin/env node
// Builds the field-chatter corpus.
//
// The previous version reached 1,000 player lines by slotting a topic noun into ten
// shared question frames, and 600 replies by reusing two frames per faction across ten
// subjects. That is where the broken English came from: a frame cannot agree in number
// with a slot it has never seen ("ammunition reserves IS measured", "mountain passes
// ... curling around IT"), and a hundred copies of one question read as a machine.
//
// Nothing here is substituted. Every question and every reply is written for its own
// subject, in its own faction's rhetorical grammar (see dialogue/VOICE.md):
//   Vostok — parataxis, litotes, gallows understatement
//   Aegis  — anaphora, asyndeton, meiosis; procedure as rhythm
//   Sarab  — antithesis, metaphor, apophasis; the balanced proverb couplet
const fs = require("node:fs");
const path = require("node:path");

const OUTPUT = path.join(__dirname, "..", "dialogue", "src", "field_chatter.json");

// Each subject: `ask` are the player's own words (plain, unfactioned); the faction keys
// are that faction's two replies, authored for this subject alone.
const themes = [
	{
		id: "logistics",
		subjects: [
			{
				id: "supply_caches",
				ask: ["Where does a unit hide its supplies out here?", "How do you keep a cache from being found?"],
				vostok: ["Bury it, mark it, forget it until you need it. Half of them we never dig up.",
					"A cache is a promise to the man you'll be in February. Keep it."],
				aegis: ["Cached, logged, rotated on a schedule. A stockpile nobody inspects is a rumour.",
					"Two caches per sector, never within sight of each other. Redundancy is not paranoia."],
				sarab: ["The well you cannot find is the well that is still yours.",
					"We bury little in many places. A thief with one shovel learns nothing worth carrying."]
			},
			{
				id: "ration_convoys",
				ask: ["What's the hardest part of moving food to the front?", "Do convoys get through reliably?"],
				vostok: ["The cart always arrives. The question is what's left on it, and who's still hungry.",
					"We escort flour like it's gold. Gold you can't eat, so flour outranks it."],
				aegis: ["Timing, spacing, alternate routes. A convoy on a predictable road is a delivery to the enemy.",
					"Losses are recorded against the route, not the driver. Roads fail more often than people."],
				sarab: ["Bread travels best at night, and best when no one has been told it is bread.",
					"A full cart moves slowly. That is its danger and, handled well, its disguise."]
			},
			{
				id: "winter_stores",
				ask: ["How do you prepare for winter?", "What happens if the winter stores run short?"],
				vostok: ["We count sacks in autumn so we don't count graves in spring. It mostly works.",
					"Winter is not the enemy's ally. Winter has no allies. It takes from both sides equally."],
				aegis: ["Stores are calculated against a bad winter, not an average one. Averages kill garrisons.",
					"Short stores are reported early or not at all. Late is the same as never."],
				sarab: ["Cold is a slow siege. It asks nothing and takes everything, so we answer it in summer.",
					"The wise fill the jar before thirst. The thirsty argue about whose jar it was."]
			},
			{
				id: "water_discipline",
				ask: ["How strict are you about water?", "What does water discipline actually mean day to day?"],
				vostok: ["Drink when told. Complain after. Nobody's died of listening to a sergeant about water.",
					"Full canteen, quiet march. Empty canteen, loud mistakes."],
				aegis: ["Rationed, tested, logged. Bad water has cost us more people than any assault.",
					"We measure water by the day of march ahead, not by the thirst of the hour."],
				sarab: ["Water is not drunk. It is spent. Spend it as you would spend years.",
					"He who drinks all he carries has decided where he will stop walking."]
			},
			{
				id: "field_depots",
				ask: ["What makes a good forward depot?", "How close to the line do you put a depot?"],
				vostok: ["Close enough to reach, far enough to survive. Everyone argues about where that is.",
					"A depot is a target with a roof. We build them plain and we build them twice."],
				aegis: ["Sited for road access, defilade, and a clear withdrawal. Two of three is a bad depot.",
					"Every depot has a demolition plan written the day it opens. We hope to file it unused."],
				sarab: ["A depot is a shadow: useful, and it moves when the sun does.",
					"Keep the storehouse where the enemy expects only stones."]
			},
			{
				id: "salvage_crews",
				ask: ["Who picks the battlefield clean afterward?", "Is salvage work as grim as it sounds?"],
				vostok: ["Salvage is the quietest work we do. Nobody sings. Everybody goes.",
					"Arrows, straps, boots. The dead don't need them and the living do. That's the whole argument."],
				aegis: ["Salvage is a graded task with a manifest. Sentiment is respected; the manifest is completed.",
					"We recover materiel first, personal effects second, and we do not confuse the two."],
				sarab: ["The desert returns what it can and keeps what it wishes. We take only the first part.",
					"We gather with two hands: one for the war, one for the family that will ask."]
			}
		]
	},
	{
		id: "patrolcraft",
		subjects: [
			{
				id: "night_patrols",
				ask: ["What's a night patrol actually like?", "How do you keep your bearings in the dark?"],
				vostok: ["Cold, slow, and mostly nothing. It's the mostly that keeps you awake.",
					"You walk, you listen, you come back. Three good outcomes and we'll take any of them."],
				aegis: ["Route briefed, checkpoints timed, return window fixed. A patrol that improvises is a patrol we search for.",
					"We move by count and by landmark. Night removes the map, not the discipline."],
				sarab: ["The dark is not empty. It is only unlit, and we have learned its furniture.",
					"At night we walk as the fox walks: slowly, and never twice by the same path."]
			},
			{
				id: "sentry_rotations",
				ask: ["How long is a sentry shift?", "How do you stop sentries from drifting off?"],
				vostok: ["Short shifts. A tired sentry is a gift to the other side, and we don't give gifts.",
					"Two hours on. Long enough to matter, short enough to stay angry about it."],
				aegis: ["Rotation is fixed and posted. A sentry who does not know when relief comes stops watching for anything else.",
					"Overlap the changeover. The gap between two watches is the only door we ever leave open."],
				sarab: ["A watchman's eyes are a lamp. Lamps burn out, so we light them in turn.",
					"We do not ask a man to guard longer than he can stay curious."]
			},
			{
				id: "challenge_passwords",
				ask: ["How do challenge words work here?", "What happens if someone forgets the password?"],
				vostok: ["Wrong word, hands up, sort it out slowly. It has gone badly before. That's why it's slow now.",
					"We change it at dusk. Anyone who complains about remembering can stand the watch themselves."],
				aegis: ["Challenge and countersign, changed daily, never written down forward of the depot.",
					"Forget it and you halt, you comply, you are identified. Inconvenience is the cheapest possible outcome."],
				sarab: ["The word is a small door. Those who know it need no other key, and those who do not may wait.",
					"We choose words a stranger's mouth cannot hold. The accent is half the lock."]
			},
			{
				id: "trail_markers",
				ask: ["How do you mark a route others can follow?", "Doesn't marking a trail help the enemy too?"],
				vostok: ["Scratch it low, where a tired man looks. High marks are for people with time.",
					"Yes, it helps them. So we mark the way back, not the way in."],
				aegis: ["Markers are standardised, sparse, and removed on withdrawal. An unrecovered marker is a report.",
					"We mark decision points only. A trail signed at every step teaches the enemy to read us."],
				sarab: ["A stone turned the wrong way speaks only to the one who turned it.",
					"We leave signs the wind can erase and a friend can still find. Both are necessary."]
			},
			{
				id: "silent_signals",
				ask: ["How do you talk without making noise?", "How much can you really say with hand signals?"],
				vostok: ["Enough. Stop, down, enemy, go. Everything else can wait for the fire.",
					"Hands work when mouths would kill you. We drill them until the hands are smarter than we are."],
				aegis: ["Hand signals, cord tugs, shielded light. Each has a defined vocabulary; none of them is improvised.",
					"Signal, acknowledge, act. An unacknowledged signal was not sent."],
				sarab: ["Two fingers can carry a sentence if both people have agreed what the sentence is.",
					"Silence is the language. The hands are only its accent."]
			},
			{
				id: "relief_watches",
				ask: ["What does a proper relief look like?", "Is handing over a watch ever a weak moment?"],
				vostok: ["You tell him what you heard, even the stupid parts. Especially the stupid parts.",
					"Relief is when we're softest. So we do it fast and we do it quiet."],
				aegis: ["Brief the relief on sector, changes, and anything unresolved. An unmentioned oddity becomes tomorrow's incident.",
					"Both sentries stand until the handover is complete. The post is never briefly empty."],
				sarab: ["The one who leaves the watch owes the one who takes it the truth of the hour.",
					"Two shadows stand where one stood. Only then does the first go and sleep."]
			}
		]
	},
	{
		id: "terrain",
		subjects: [
			{
				id: "river_crossings",
				ask: ["How do you get a unit across a river?", "What makes a crossing go wrong?"],
				vostok: ["Wet boots, cold night, and everyone across before dawn. Simple. Never easy.",
					"The river doesn't care about the plan. Cross where it's dull, not where it's short."],
				aegis: ["Ford surveyed, far bank secured, crossing timed. Half a unit on each bank is not a crossing; it is two problems.",
					"We cross where we can also come back. A one-way ford is a decision, not a route."],
				sarab: ["A river is a wall that pretends to be a road. Both halves of that are true.",
					"Cross at the boring water. The beautiful water is where the bodies are."]
			},
			{
				id: "ridge_lines",
				ask: ["Is high ground always worth taking?", "How do you move along a ridge without being seen?"],
				vostok: ["High ground is good until you have to carry water up it. Then it's just a hill with opinions.",
					"You take the ridge because they want it. That's reason enough most days."],
				aegis: ["High ground buys observation and costs supply. We take it when observation is the objective.",
					"Move below the crest, never on it. A silhouette is a message we did not authorise."],
				sarab: ["Stand on the ridge and you can see everything, including yourself, from far away.",
					"The crest belongs to the sky. We walk a little beneath it and let the sky keep its claim."]
			},
			{
				id: "marsh_paths",
				ask: ["How do you cross a marsh safely?", "Can you even fight in wetland?"],
				vostok: ["Pole ahead of every step. Slow is fine. The marsh is patient and so are we.",
					"Fight in a marsh and the mud takes as many as the enemy. Sometimes more."],
				aegis: ["Probe, mark, single file, spacing maintained. A marsh punishes the second man for the first man's speed.",
					"We do not manoeuvre in wetland. We pass through it, and we pass through it once."],
				sarab: ["Green ground that shines is not welcoming you. It is waiting.",
					"The marsh takes the confident and returns the careful. Choose which to be before you step."]
			},
			{
				id: "ruined_bridges",
				ask: ["Do you rebuild broken bridges or route around?", "How much can you trust an old bridge?"],
				vostok: ["We rebuild if we must hold it. Otherwise let it lie. Broken bridges have their uses.",
					"Trust a ruin the way you trust a stranger. Test it with something you can afford to lose."],
				aegis: ["Assess the span, load-test it, or bypass. An untested bridge is a casualty estimate.",
					"A bridge we cannot deny to the enemy is a bridge we finish breaking."],
				sarab: ["Every broken bridge was once someone's certainty. Read it as a warning, then cross elsewhere.",
					"Stone remembers the shape of the arch. It does not remember how to hold it."]
			},
			{
				id: "mountain_passes",
				ask: ["How do you hold a mountain pass?", "Is there ever a way around a pass?"],
				vostok: ["A pass is a corridor. Few can hold it against many, and that is the only good news in mountains.",
					"You hold it with rocks, cold, and stubbornness. We are well supplied with all three."],
				aegis: ["Block the defile, cover the shoulders, keep a reserve behind the bend. A pass held only at the mouth is not held.",
					"We map the goat tracks first. Every pass has an argument against itself somewhere above it."],
				sarab: ["The impatient count the passes. The patient count the ways around them.",
					"A narrow place makes a few men many. It also makes them easy to find."]
			},
			{
				id: "dry_gullies",
				ask: ["Are dry riverbeds good cover?", "What's the risk of camping in a gully?"],
				vostok: ["Good cover, bad bed. It's dry until it's a river again, usually at night.",
					"Walk the gully, sleep on the bank. Cheap rule. Learned expensively."],
				aegis: ["Excellent concealment, unacceptable flood risk. We transit gullies; we do not occupy them.",
					"Rain twenty miles away is still rain in your gully. We watch the weather upstream, not overhead."],
				sarab: ["The dry bed is a door the water has left open. It always comes home.",
					"Sleep where the flood has never reached, and you will wake to argue about whether it could."]
			}
		]
	},
	{
		id: "weather",
		subjects: [
			{
				id: "hard_rain",
				ask: ["Does heavy rain stop the fighting?", "What does rain ruin fastest?"],
				vostok: ["Rain stops nothing. It just makes everything heavier, including the dying.",
					"Bowstrings first, then morale, then the roads. In that order, every time."],
				aegis: ["Rain degrades archery, visibility, and footing. It is a forecast line, not an excuse line.",
					"We plan wet-weather variants in advance. Weather surprises no one who reads."],
				sarab: ["Rain is generous and indiscriminate. It waters the field and drowns the ambush alike.",
					"When the sky empties, the clever move and the proud complain."]
			},
			{
				id: "deep_snow",
				ask: ["How do you keep a unit moving in deep snow?", "What does snow do to a supply line?"],
				vostok: ["We break trail in turns. The man at the front is spending his life faster than the rest.",
					"Snow is honest. It shows exactly where everyone went, including us."],
				aegis: ["Movement rates halve; consumption rises. We plan the route by calories, not by distance.",
					"Snow makes tracks permanent. We route the withdrawal before we commit to the advance."],
				sarab: ["We know snow only by report. Our cold is dry and it lies about how much it takes.",
					"A white road keeps every footprint. Walk it as if someone will read it, because someone will."]
			},
			{
				id: "dust_storms",
				ask: ["What do you do when a dust storm hits?", "Can you fight through one?"],
				vostok: ["Sit down, cover your face, hold the rope. Standing up is how you get lost fifty paces from soup.",
					"You don't fight it. You wait it out and count heads after."],
				aegis: ["Halt, mark positions, link personnel. Movement in zero visibility produces stragglers, not progress.",
					"We treat a dust storm as a communications failure with wind attached. Re-establish contact first."],
				sarab: ["The storm is not weather. It is the desert rearranging its furniture and asking you to wait outside.",
					"When the air turns to sand, the wise become stones. Stones are still there afterward."]
			},
			{
				id: "summer_heat",
				ask: ["How do you fight in real heat?", "When do you move in summer?"],
				vostok: ["Badly. We were built for winter. In summer we drink and we complain and we manage.",
					"Heat kills quieter than cold. Nobody notices a man going down until he's down."],
				aegis: ["Work shifted to dawn and dusk, water enforced, armour discipline relaxed under order.",
					"Heat casualties are preventable, therefore they are command failures. We treat them as such."],
				sarab: ["We do not fight the sun. We let it fight for us, and we meet you at noon.",
					"Move at the edges of the day. The middle belongs to something that has never lost."]
			},
			{
				id: "heavy_fog",
				ask: ["Is fog an advantage or a danger?", "How do you avoid getting lost in fog?"],
				vostok: ["Both, and you don't get to choose which. Fog hides them just as well as it hides us.",
					"Rope, count, and shout the password early. Better embarrassed than stabbed by a friend."],
				aegis: ["Fog favours the side that prepared for it. We navigate by bearing and pace count, not landmark.",
					"In fog we tighten intervals and slow the advance. Speed in fog is how units meet themselves."],
				sarab: ["Fog is the desert's rare mercy and its rarer trap. It hides the traveller and the well together.",
					"When you cannot see the horizon, trust the ground. It has not moved."]
			},
			{
				id: "sudden_floods",
				ask: ["How much warning do you get before a flash flood?", "What do you save first?"],
				vostok: ["Minutes. Sometimes less. You save people, then you argue about the rest.",
					"The water takes the camp in one breath. We rebuild. That's the whole procedure."],
				aegis: ["Little warning, so the response is pre-briefed: high ground, headcount, then materiel.",
					"We site camps above the historic waterline. History is cheaper to consult than to repeat."],
				sarab: ["The flood is a debt the sky collects without notice. Sleep where it cannot reach you.",
					"Save the living, then the water skins, then nothing. There is no time for a fourth thought."]
			}
		]
	},
	{
		id: "equipment",
		subjects: [
			{
				id: "blade_care",
				ask: ["How often do you sharpen a blade?", "Does blade care really matter that much?"],
				vostok: ["Every night. It's dull work for a dull edge. Both get done.",
					"A blunt blade doesn't fail loudly. It just takes two strokes where you had time for one."],
				aegis: ["Edge inspected at stand-down, honed on a schedule, replaced against a wear standard.",
					"Care is not devotion. It is arithmetic: a maintained edge survives more engagements."],
				sarab: ["The blade you tend at dusk is the friend who answers at dawn.",
					"Rust is patient. So it must be met by something more patient."]
			},
			{
				id: "armor_repairs",
				ask: ["Who repairs armour in the field?", "Can you patch armour properly on campaign?"],
				vostok: ["Anyone with wire and a rock. It won't be pretty. It'll be there.",
					"Field repair buys you one more fight. Nobody has ever asked for two."],
				aegis: ["Armourer at the depot; field expedients forward, documented and replaced at rotation.",
					"A patched plate is logged as patched. We do not let a repair disappear into a kit list."],
				sarab: ["Cloth over the gap, and the gap is still there. Know where your patches are.",
					"Mended armour teaches humility. It shows you exactly where you were wrong."]
			},
			{
				id: "worn_boots",
				ask: ["How long does a pair of boots last out here?", "What happens when the boots go?"],
				vostok: ["A season if you're lucky. Then you walk anyway, and then you stop walking.",
					"Feet lose more soldiers than fights do. Nobody puts that on a banner."],
				aegis: ["Footwear is a readiness item, inspected weekly. A unit is as mobile as its worst pair of boots.",
					"We replace at wear indicators, not at failure. Failure happens on a road, at night, under load."],
				sarab: ["The road eats leather first and men second. Feed it leather.",
					"A traveller is his feet. Everything else is luggage."]
			},
			{
				id: "signal_horns",
				ask: ["What can a horn tell a unit that a runner can't?", "Don't horns give away your position?"],
				vostok: ["A horn tells everyone at once. A runner tells one man, late, if he lives.",
					"They know we're here. They usually do. The horn is for us, not them."],
				aegis: ["Horn calls carry fixed meanings: rally, withdraw, gas the line, cease. Ambiguity is designed out.",
					"We accept disclosure of position in exchange for simultaneity. That trade is made deliberately."],
				sarab: ["A horn says one word to a valley. Choose the word before you fill your lungs.",
					"We sound the horn where we are not, and go where the horn is not."]
			},
			{
				id: "climbing_rope",
				ask: ["How much do you rely on rope?", "How do you know when a rope is done?"],
				vostok: ["More than you'd think. Rope gets you up, down, and out. Mostly out.",
					"When it fuzzes, it's finished. Arguing with a rope is a short conversation."],
				aegis: ["Rope is inspected end to end before each use and retired on a fixed count, not on appearance.",
					"We never load a line with a life that has not been checked twice. The second check is somebody else's."],
				sarab: ["Rope frays where you cannot see. So does a promise.",
					"Trust the rope you coiled yourself, and even then, tie the knot twice."]
			},
			{
				id: "lantern_oil",
				ask: ["Is light worth the risk at night?", "How do you ration lamp oil?"],
				vostok: ["Light is a target. We use it in holes, under cloth, and not for long.",
					"Oil runs out in the week you need it most. Always has."],
				aegis: ["Shielded light only, issued by task. Illumination discipline is enforced like any other.",
					"Oil is drawn against a task list. Light without a purpose is a beacon we paid for."],
				sarab: ["A small flame in a large dark is not a comfort. It is an announcement.",
					"Burn oil for the wounded and the map. The rest of the night can be walked blind."]
			}
		]
	},
	{
		id: "camp_life",
		subjects: [
			{
				id: "field_kitchens",
				ask: ["Is the food any good?", "How important is a hot meal, really?"],
				vostok: ["It's hot and there's enough. That's two miracles. Taste would be greedy.",
					"A hot meal is worth an hour of sleep. Ask anyone who's had to choose."],
				aegis: ["Adequate, scheduled, and inspected. Nutrition is a readiness input like any other.",
					"We feed before we brief. A hungry unit hears half of what it is told."],
				sarab: ["Bread shared is a wall built. It is the cheapest fortification we have.",
					"The pot is the true centre of the camp. The banner only says where the pot is."]
			},
			{
				id: "firewood_details",
				ask: ["Who gets stuck on firewood duty?", "Is gathering wood as dull as it sounds?"],
				vostok: ["Whoever annoyed the sergeant. It's fair, in its way.",
					"Dull until you're the one cold. Then it's the most important work in the world."],
				aegis: ["Rostered by rotation, with a cutting radius set to avoid disclosing the camp.",
					"We take deadfall at distance. A stripped treeline marks us on every map that matters."],
				sarab: ["We burn little and we burn dry. Smoke is a message we did not choose to send.",
					"Wood gathered far keeps the camp warm and unfound. Both are worth the walk."]
			},
			{
				id: "latrine_duty",
				ask: ["Why does everyone dread latrine duty?", "Does camp sanitation actually matter?"],
				vostok: ["Because it's foul and it never ends. Also because it matters, which makes it worse.",
					"Bad ground has killed more of us than any charge. Dig it far, dig it deep, shut up about it."],
				aegis: ["Sited downwind, downhill, and away from water. Sanitation failures are casualty events.",
					"Disease outranks the enemy in our records. We treat the spade as ordnance."],
				sarab: ["Foul the water and the desert will finish what no enemy could.",
					"The unclean camp defeats itself and lets the enemy claim it."]
			},
			{
				id: "evening_roll_call",
				ask: ["What's roll call like after a bad day?", "Why hold roll call every single night?"],
				vostok: ["Quiet. You hear the gaps. Nobody fills them in.",
					"You call the names because they were here this morning. It's the only ceremony we've got."],
				aegis: ["Accountability is absolute and daily. An unaccounted soldier is a search, not a statistic.",
					"We call the roll to know who to look for, and how far they can have gone."],
				sarab: ["We speak the names at dusk so that the night cannot take them quietly.",
					"A name unanswered is not yet a name lost. It is a question we are still asking."]
			},
			{
				id: "guard_dogs",
				ask: ["Do you keep dogs in camp?", "Are dogs better than sentries?"],
				vostok: ["Two. They eat what we eat and they hear what we can't. Fair trade.",
					"A dog wakes before you do and is never bored. No sentry can say both."],
				aegis: ["Working dogs are assigned, trained, and rostered like any other asset, with a handler of record.",
					"A dog extends the perimeter without extending the watch. It does not replace the watch."],
				sarab: ["The dog knows the stranger before the stranger knows himself.",
					"Feed the dog well. He is the only sentry who will never be bribed."]
			},
			{
				id: "camp_merchants",
				ask: ["Are the camp traders trustworthy?", "What do soldiers actually buy out here?"],
				vostok: ["Trustworthy as anyone selling to men who might die. Which is to say: watch the scales.",
					"Salt, thread, and something to burn. Everything else is a story they're selling."],
				aegis: ["Licensed, weighed, and subject to inspection. An unlicensed trader in camp is a security matter.",
					"We permit trade because forbidding it moves it outside the wire, where we cannot see it."],
				sarab: ["The merchant who follows an army is honest about one thing: he expects it to survive.",
					"Buy from the one who returns. A man who never comes back sold you nothing twice."]
			}
		]
	},
	{
		id: "battlecraft",
		subjects: [
			{
				id: "holding_a_breach",
				ask: ["How do you hold a breach in a wall?", "What goes through your head standing in a gap?"],
				vostok: ["Shoulder to shoulder and don't step back. The gap is only as wide as the men in it.",
					"You think about the next man's shoulder. That's it. That's the whole thought."],
				aegis: ["Narrow the gap with obstacles, rotate the front rank, keep a reserve out of the crush.",
					"A breach is a fight with a fixed frontage. Fresh arms win it, not brave ones."],
				sarab: ["The wound in the wall is where the wall is most awake.",
					"Stand in the gap and you are the wall. Walls are relieved in turn, or they fall."]
			},
			{
				id: "breaking_an_ambush",
				ask: ["What do you do the second an ambush starts?", "Is it better to push through or pull back?"],
				vostok: ["You move. Any direction beats standing. Standing is how the whole file goes down.",
					"Through, usually. Back is where they've already thought about you."],
				aegis: ["Immediate action: return pressure, break the kill zone by the shortest axis, rally at the marked point.",
					"We drill the response so nobody has to decide. Deciding costs the seconds we do not have."],
				sarab: ["An ambush is a held breath. Move before it is finished being released.",
					"They chose the ground. Leave it. Choosing new ground is the whole of the answer."]
			},
			{
				id: "defending_high_ground",
				ask: ["What makes high ground defensible?", "Can high ground ever be a trap?"],
				vostok: ["Slopes tire them and help us. Simple maths, and the only kind we trust.",
					"It's a trap when they stop climbing and just wait. Then it's a hill with no water."],
				aegis: ["Fields of observation, layered obstacles, secured flanks, and a supply route to the rear.",
					"High ground without a line of resupply is a siege you have volunteered for."],
				sarab: ["The summit gives sight and takes water. Know which you need more.",
					"To sit above is to be seen from everywhere. Height is not the same as safety."]
			},
			{
				id: "escorting_civilians",
				ask: ["How do you move civilians through a war zone?", "What's the hardest part of an escort?"],
				vostok: ["Slower than you want. They carry what they can't leave and you don't get to argue.",
					"Hardest part is that they trust you. That weighs more than the packs."],
				aegis: ["Column order fixed, pace set by the slowest, security forward and rear. Never by the shortest route.",
					"Escort is a protection task, not a movement task. The metric is arrivals, not hours."],
				sarab: ["The frightened walk in the wrong direction unless someone walks in the right one first.",
					"Carry the child and the mother will keep pace. Carry the water and everyone will."]
			},
			{
				id: "covering_a_retreat",
				ask: ["Who covers a withdrawal?", "How do you know when to break contact yourself?"],
				vostok: ["The ones who can be spared, which is nobody, so it's the ones who volunteer.",
					"When the last of them is past you, you go. Not before. Sometimes not at all."],
				aegis: ["A designated rearguard, phase lines, and a rally point briefed before the withdrawal begins.",
					"Break contact on the signal, not on the feeling. A rearguard that leaves early converts a withdrawal into a rout."],
				sarab: ["The last to leave carries the whole army on his back for a little while.",
					"Withdraw like water leaving sand: slowly, and taking the shape of the ground."]
			},
			{
				id: "surviving_a_siege",
				ask: ["What actually breaks a garrison under siege?", "How do you keep going when relief isn't coming?"],
				vostok: ["Water, then bread, then hope, in that order. Hope lasts longest, which is the cruel part.",
					"You stop counting days. You count sacks. Sacks are honest."],
				aegis: ["Stores, sanitation, and information discipline. Garrisons surrender to rumour more often than to assault.",
					"We ration the news as carefully as the flour. Both run out; only one can be replaced by silence."],
				sarab: ["A siege is a conversation about patience. The wall is only the table.",
					"They wait outside; we wait inside. The desert waits for both and does not take sides."]
			}
		]
	},
	{
		id: "intelligence",
		subjects: [
			{
				id: "captured_maps",
				ask: ["How much do you trust a captured map?", "What's the first thing you check on an enemy map?"],
				vostok: ["A map is a rumour with straight lines. We check it with our boots.",
					"First thing? Whether it was worth carrying. Men don't die holding useless paper."],
				aegis: ["Provenance first, then internal consistency, then verification against terrain we hold.",
					"A captured map tells you what the enemy believes. That is intelligence even when the map is wrong."],
				sarab: ["A map made by a stranger shows you his fears, not your road.",
					"The valuable part of a stolen map is the part he did not think worth drawing."]
			},
			{
				id: "coded_messages",
				ask: ["Do you use codes in the field?", "What do you do with an enemy message you can't read?"],
				vostok: ["Simple ones. Complicated codes lose more messages than the enemy does.",
					"Can't read it, so we send it back and let clever men upstream be clever."],
				aegis: ["Field ciphers are short-lived by design and changed on a schedule, not on suspicion.",
					"Unreadable traffic still yields volume, timing, and origin. Content is only the last of four answers."],
				sarab: ["A locked message tells you there is a lock, and where the key is likely kept.",
					"We read the hand and the haste before we read the words."]
			},
			{
				id: "false_trails",
				ask: ["Do you ever lay false trails?", "How do you spot one that's been laid for you?"],
				vostok: ["We do. We're not good at it. Sarab are, and it costs us.",
					"When it's too easy to follow, someone wanted you following."],
				aegis: ["Deception is planned and deconflicted so our own patrols are not caught by it.",
					"Look for effort. A real trail is careless; a false one has been maintained."],
				sarab: ["The clearest path is the one someone swept for you.",
					"We leave three roads and walk a fourth. This is not cleverness; it is habit."]
			},
			{
				id: "local_guides",
				ask: ["Do you use local guides?", "How do you know a guide isn't leading you wrong?"],
				vostok: ["We use them. We also watch them. Both, always, no offence meant.",
					"A guide who won't take his family's road is telling you something."],
				aegis: ["Guides are vetted, compensated, and never given the full objective.",
					"We brief the route in segments. A guide cannot betray a destination he has not been told."],
				sarab: ["The guide is the road. Choose him as carefully as you would choose the water.",
					"Ask him where his mother lives, and then whether he will walk you past it."]
			},
			{
				id: "deserter_stories",
				ask: ["How much weight do you give a deserter's account?", "What happens to deserters who come to you?"],
				vostok: ["Believe the parts that shame him. Nobody invents their own cowardice.",
					"We feed them and we ask questions. What happens after isn't ours to decide."],
				aegis: ["Debriefed, corroborated, and graded. A deserter's account is a source, subject to the same standards.",
					"They are held, they are recorded, and they are treated correctly. The record matters later."],
				sarab: ["A man who left one fire will speak honestly of its smoke and lie about its warmth.",
					"He brings his fear with him. Weigh the fear separately from the facts."]
			},
			{
				id: "missing_scouts",
				ask: ["How long do you wait for a scout who hasn't come back?", "Do you send someone after them?"],
				vostok: ["Past the window, past the second window, then we stop pretending.",
					"We send one pair. Not more. You don't feed a hole."],
				aegis: ["Overdue at the window, search initiated at the contingency time, both briefed beforehand.",
					"The search is bounded in advance. An unbounded search is how a missing scout becomes a missing section."],
				sarab: ["The desert is slow to answer. Sometimes it answers late and the answer walks in.",
					"We wait as long as the water allows, and not one hour past what the living need."]
			}
		]
	},
	{
		id: "morale",
		subjects: [
			{
				id: "battlefield_fear",
				ask: ["Does fear ever go away?", "How do you keep moving when you're afraid?"],
				vostok: ["No. You get used to carrying it. That's the whole trick and it isn't much of one.",
					"The man beside you is afraid too. You move so he doesn't have to move first."],
				aegis: ["Fear is expected and trained for. Drill supplies the action when judgement is degraded.",
					"We do not ask soldiers not to feel it. We ask them to perform the next step regardless."],
				sarab: ["Fear is a guest who arrives without invitation. Give it tea; do not give it the reins.",
					"The brave and the frightened do the same work. Only one of them talks about it after."]
			},
			{
				id: "homesickness",
				ask: ["Do people talk about home much?", "Is it easier not to think about it?"],
				vostok: ["Constantly, then never, then constantly again. It comes in weather.",
					"Easier, yes. Better, no. The ones who stop talking about home stop talking."],
				aegis: ["It is acknowledged, not indulged. Letters are encouraged; brooding is noticed and addressed.",
					"Longing is a readiness factor. We treat it as one, without pretending it is a fault."],
				sarab: ["Home is the water you remember tasting. It keeps a man walking and it makes him thirsty.",
					"Speak of home at the fire. Leave it at the fire when you rise."]
			},
			{
				id: "unit_pride",
				ask: ["What's your unit proud of?", "Does pride actually help in a fight?"],
				vostok: ["That we're still here. It isn't much. It's more than most can say.",
					"Pride doesn't stop a blade. It stops you running before the blade arrives."],
				aegis: ["Our record: sectors held, casualties accounted, obligations met. Pride is evidence-based here.",
					"Pride sustains standards when nobody is watching. That is its operational value."],
				sarab: ["We are not many. Neither are scorpions.",
					"Pride is a good horse and a poor rider. Let it carry you; do not let it steer."]
			},
			{
				id: "new_recruits",
				ask: ["How long until a recruit is useful?", "What's the first thing you teach them?"],
				vostok: ["One winter. If they last it, they're ours. If not, they weren't going to.",
					"Where to put their feet. Everything else can be taught to a living man."],
				aegis: ["Basic competence in weeks, judgement in seasons. We do not confuse the two.",
					"First lesson: report accurately, including your own errors. Everything else is built on that."],
				sarab: ["The young walk loudly. We teach the feet before we teach the hands.",
					"A new blade is bright and brittle. Both conditions pass."]
			},
			{
				id: "letters_from_home",
				ask: ["Does mail reach you out here?", "What's it like when the letters come?"],
				vostok: ["Late and in bundles. Three months of news in one hour. Then quiet for a while.",
					"Good day, mostly. Not for everyone. You learn to watch who walks off alone."],
				aegis: ["Mail is moved with the same priority as materiel. Its effect on readiness justifies the transport.",
					"Distribution is supervised. Undelivered mail for a casualty is handled, not left in a sack."],
				sarab: ["A letter is water from a well you cannot reach. Drink slowly.",
					"We read them aloud for those whose eyes were taken. The words belong to whoever is listening."]
			},
			{
				id: "funeral_rites",
				ask: ["How do you bury your dead out here?", "Is there time for proper rites?"],
				vostok: ["Deep as the ground allows, name on wood, and the line moves on. We come back if we can.",
					"There's never time. We take it anyway. That's the one order nobody enforces and everybody follows."],
				aegis: ["Recovered where possible, identified, recorded, and returned. The obligation does not expire.",
					"Rites are brief and observed. A unit that buries carelessly stops trusting that it would be collected."],
				sarab: ["The sand keeps what it is given. We give it names first, so it knows what it holds.",
					"We do not hurry the dead. They have already done the hardest walking."]
			}
		]
	},
	{
		id: "society",
		subjects: [
			{
				id: "officer_promotions",
				ask: ["How does someone become an officer here?", "Are promotions fair?"],
				vostok: ["Someone above you dies and you're standing closest. That's most of it.",
					"Fair enough. The line notices a bad officer faster than any board would."],
				aegis: ["Assessed against a standard, boarded, and recorded. Merit is documented or it is favouritism.",
					"We promote for judgement under load, not for courage. Courage is assumed."],
				sarab: ["The one the others already follow is made to carry the name as well. Little changes.",
					"A title given to a man nobody obeys is a joke told slowly."]
			},
			{
				id: "soldier_pay",
				ask: ["Do soldiers get paid on time?", "Is the pay worth the work?"],
				vostok: ["Sometimes. Late pay is normal. No pay is a problem, and problems get discussed loudly.",
					"Nobody's here for the money. That's true, and it's also what they say to avoid paying us."],
				aegis: ["Paid on schedule, against a published scale, with arrears tracked. This is not generosity; it is discipline.",
					"An unpaid garrison is an unreliable garrison. The ledger is a readiness document."],
				sarab: ["Coin is the shortest kind of loyalty. Necessary, and never sufficient.",
					"Pay a man honestly and you need not watch him. That is the cheaper arrangement."]
			},
			{
				id: "camp_justice",
				ask: ["Who handles crime in camp?", "Is camp justice harsh?"],
				vostok: ["The sergeant, mostly. Quick and rough and finished. Slow justice rots a company.",
					"Harsh for theft. Harsher for sleeping on watch. One steals bread; the other steals everyone."],
				aegis: ["Charges recorded, heard, and reviewed. Punishment without a record is not justice; it is temper.",
					"Proportionate, documented, appealable. A garrison that fears its own officers watches the wrong direction."],
				sarab: ["Judge in the open, where every witness may remember the scale.",
					"A quiet punishment breeds three loud rumours. Let it be seen, and let it be finished."]
			},
			{
				id: "military_medals",
				ask: ["Do medals mean anything to you?", "Who actually gets decorated?"],
				vostok: ["Tin. It means someone wrote it down. That part matters, a little.",
					"The ones who came back. The ones who didn't get a line in a book, if they're lucky."],
				aegis: ["Awarded against citation criteria and recorded permanently. The record is the point; the metal is a receipt.",
					"We decorate the act, not the outcome. Outcomes involve too much weather."],
				sarab: ["A ribbon is a story someone else has agreed to tell about you.",
					"We remember deeds in speech, not in metal. Metal is heavy and speech travels."]
			},
			{
				id: "oath_taking",
				ask: ["What oath do you swear?", "Does the oath still mean something after years of this?"],
				vostok: ["Short one. To the line and the people behind it. Nothing about glory, thankfully.",
					"It means more now, not less. It's the only thing that hasn't been revised."],
				aegis: ["To the mandate and to the rules that bind us while we hold it. Both halves, or neither.",
					"An oath is a standing order you gave yourself. We treat it with the same seriousness."],
				sarab: ["A word given at the well is heavier than a word given at the gate.",
					"We swear little and slowly. What is rarely said is more easily kept."]
			},
			{
				id: "peace_negotiations",
				ask: ["Do you think this war ends in a treaty?", "Would you trust a truce?"],
				vostok: ["It ends when someone runs out. Treaties are what they write down afterward.",
					"I'd trust a truce for as long as I could see it. That's about a hundred paces."],
				aegis: ["Terms are honoured while they hold and verified while they are honoured. Trust is not a clause.",
					"We negotiate from a held position. A truce agreed from weakness is a schedule for the next war."],
				sarab: ["Peace made in the heat is a shade that moves. Sit in it, but do not build there.",
					"Both sides will say the war ended. The desert will say only that the noise stopped."]
			}
		]
	}
];

const options = [];
const responses = {};
let subjectCount = 0;

for (const theme of themes) {
	for (const subject of theme.subjects) {
		subjectCount++;
		const cls = `field_${theme.id}_${subject.id}`;
		subject.ask.forEach((text, index) => options.push({
			id: `${cls}_q${index + 1}`,
			text,
			response: cls,
			tone: "neutral"
		}));
		const bands = {};
		for (const faction of ["vostok", "aegis", "sarab"]) {
			const lines = subject[faction];
			if (!lines || lines.length < 2) {
				throw new Error(`${cls}: ${faction} needs at least two authored lines`);
			}
			bands[faction] = { neutral: lines };
		}
		responses[cls] = bands;
	}
}

fs.writeFileSync(OUTPUT, JSON.stringify({ category: "field_chatter", options, responses }, null, 2) + "\n");
console.log(`field_chatter: ${subjectCount} subjects, ${options.length} player options, `
	+ `${Object.values(responses).reduce((n, b) => n + Object.values(b)
		.reduce((m, x) => m + x.neutral.length, 0), 0)} authored response lines`);
