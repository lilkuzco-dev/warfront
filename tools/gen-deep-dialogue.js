#!/usr/bin/env node
// Generates ten authored topics with ten sequential layers and three honest
// player approaches per layer. Each topic owns concrete guarded/full facts;
// disposition silently controls disclosure, while faction and player approach
// control the register. No response names the gate that selected it.
const fs = require("node:fs");
const path = require("node:path");

const OUTPUT = path.join(__dirname, "..", "dialogue", "src", "deep_branches.json");
const slug = (value) => value.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");

const prompts = [
	["What can your crews tell me about the {subject}?", "What does the {subject} actually do?", "Is there any proof the {subject} works?"],
	["Tell me about the latest attempt.", "What happened during the latest attempt?", "Did the latest attempt fail as badly as people say?"],
	["Where is it most likely to fail?", "What is its worst failure mode?", "What flaw did the planners miss?"],
	["How would you use it without betting the operation on it?", "What limited field use justifies the risk?", "Is there any use that justifies the risk?"],
	["Who carries the greatest risk?", "Who pays when it goes wrong?", "Who was volunteered without being asked?"],
	["You've dealt with this before?", "Has this happened to your side before?", "So your side has made this mistake before?"],
	["Which mistake cannot happen again?", "What first decision caused the failure?", "What was the first avoidable mistake?"],
	["What line should commanders refuse to cross?", "What is the non-negotiable limit?", "Would victory excuse crossing that line?"],
	["What is the strongest case against proceeding?", "What objection worries you most?", "What risk are your officers downplaying?"],
	["What must change before the next attempt?", "What should happen next?", "What would it take to make this safe?"]
];

const openings = {
	"contested river bridge": [
		"What can you tell me about the bridge your people are contesting?",
		"What state is the river bridge actually in?",
		"Is that bridge really worth what it is costing you?"
	],
	"winter ration reserve": [
		"Can you tell me how the winter reserve is holding?",
		"How long will the winter ration reserve actually last?",
		"Is the reserve as thin as people are saying?"
	],
	"double agent report": [
		"Is there something you can tell me about the informer trouble?",
		"What does the double agent report actually allege?",
		"Are you sure there is an informer at all, or is this a witch hunt?"
	],
	"unauthorized rescue mission": [
		"May I ask about the rescue that went out without orders?",
		"What happened on the unauthorized rescue?",
		"Was that rescue heroism, or just someone ignoring orders?"
	],
	"unit after heavy losses": [
		"How is the company holding up after the losses?",
		"How badly was this unit hit?",
		"Is what is left of this unit still fit to fight?"
	],
	"hospital claimed by both sides": [
		"What can you tell me about the hospital both sides claim?",
		"Who actually holds the disputed hospital?",
		"Is that hospital being protected, or just used as cover?"
	],
	"failed prewar treaty": [
		"Would you tell me about the treaty that failed before the war?",
		"What did the prewar treaty actually promise?",
		"Did anyone ever intend to keep that treaty?"
	],
	"prototype defensive barrier": [
		"What can your crews tell me about the new barrier?",
		"What does the prototype barrier actually do?",
		"Is there any proof the barrier works?"
	],
	"desert water emergency": [
		"Can you tell me how the water shortage is being handled?",
		"How serious is the water situation here?",
		"Is the water crisis bad luck, or bad planning?"
	],
	"disarming rival militias": [
		"What can you tell me about the effort to disarm the militias?",
		"How is the disarmament of the militias meant to work?",
		"Does anyone believe those militias will actually disarm?"
	],
};

const topics = [
	{
		theme: "Frontline Operations", subject: "contested river bridge",
		facts: [
			["Only the north stone arch still carries carts; the timber span holds infantry but not supply wagons.", "The north arch carries carts, and two hidden rope ferries move wounded after dark; losing either route cuts the eastern bank off by dawn."],
			["The last assault reached the tollhouse, then stalled when a mule cart broke an axle on the approach.", "The last assault held the tollhouse for eleven minutes. A broken mule cart blocked ammunition, and the survivors crossed back under arrow fire."],
			["The bridge deck is not the weak point. The exposed eastern approach gives archers a clear shot for sixty paces.", "The eastern approach is the trap: sixty open paces, no drainage, and one overturned wagon can stop every shield behind it."],
			["Use the bridge only to move supplies after scouts clear both banks; infantry can cross elsewhere.", "The bridge is worth holding for carts, not glory. Infantry should ford upstream while engineers keep one lane open for food and wounded."],
			["The mill families live beside the eastern ramp and cannot leave while both armies watch the road.", "Nine mill families shelter under the east ramp. Every exchange of arrows drops stone and burning pitch onto their roofs."],
			["Vostok packs the road until one wreck stops everyone.", "Vostok turns numbers into a traffic jam here. Three smaller columns would survive what one grand column cannot."],
			["No cart should enter until the far-bank archers are silent.", "The failed assault sent the ammunition cart first. Its burning canvas marked the range for every archer on the far bank."],
			["Do not destroy the bridge while civilians are trapped on the eastern bank.", "Blowing the arch would stop pursuit, but it would also strand the mill families without food or a winter road."],
			["Holding the tollhouse may cost more soldiers than the supply route is worth.", "The strongest objection is arithmetic: the bridge saves half a day of travel but has consumed two squads in a week."],
			["Build covered bends on the approach and repair the upstream ford before another assault.", "Add two covered turns, clear the ford, and station a repair cart west of the arch; then one wreck cannot close the whole route."]
		],
		history: {
			vostok: ["We pack the road until one wreck stops everyone.", "We mistake a full column for strength. Three smaller crossings would survive what one grand push cannot."],
			aegis: ["Our control plan has too many dependencies for one narrow bridge.", "Aegis waits for scouts, engineers, archers, and signals to align. Here, one missed report freezes the entire crossing."],
			sarab: ["We admire the hidden ford and forget that carts cannot live on secrets.", "Sarab can slip people across upstream, but a campaign still eats by the wagon. Evasion alone does not feed the far bank."]
		}
	},
	{
		theme: "War Logistics", subject: "winter ration reserve",
		facts: [
			["The reserve holds barley for twelve days at half ration, but salt meat for only five.", "The cellar can feed eighty soldiers barley for twelve days at half ration. Salt meat ends on day five, and lamp oil on day seven."],
			["A thaw flooded the lower racks and spoiled six sealed grain sacks.", "During the thaw, meltwater entered the north wall. Six grain sacks sprouted before the quartermaster opened them."],
			["The inventory counts sacks, not edible weight, so wet grain still appears as full supply.", "The ledger records whole sacks. Water added weight to spoiled grain, making the reserve look healthier after the flood."],
			["Keep it for storms that close both roads, not to hide routine delivery failures.", "Open the reserve only when both roads close. Using it to cover late convoys leaves nothing for the blizzard it was built for."],
			["Stable hands lose meals first even though the horses and wagons depend on them.", "The current ration order cuts stable hands before infantry. Three collapsed last week, and then no wagons moved at all."],
			["Vostok counts mouths but ignores how much cold labor each body performs.", "Vostok gives every coat the same bowl. Fair on paper; foolish when a driver works all night and a clerk sleeps by the stove."],
			["Every sack must be opened and weighed before it enters the reserve ledger.", "The quartermaster accepted sealed sacks to save an hour. That shortcut hid the rot for three weeks."],
			["Never issue food by rank while laborers are fainting on the road.", "An officer may command the march, but rank does not pull a frozen wagon. Calories follow work or the whole column stops."],
			["Keeping the reserve closed while patrols go hungry may protect a future that never arrives.", "The hard objection is timing: soldiers are hungry now, while the storm the reserve anticipates may never close the road."],
			["Raise the grain racks, weigh every delivery, and publish a seven-day issue plan.", "Stone feet under the racks, weighed contents, and a visible seven-day ration schedule would make the reserve honest."]
		],
		history: {
			vostok: ["We call equal bowls fair even when the work is unequal.", "Vostok feeds by head count. In winter, the night driver needs more than the clerk by the stove, or both go nowhere."],
			aegis: ["Our ledgers create confidence after the food has already spoiled.", "Aegis trusts the recorded sack count. Without moisture checks, procedure certifies six sacks of compost as dinner."],
			sarab: ["We plan to live light and forget that winter is heavier than desert.", "Sarab can stretch a caravan ration, but cold burns food faster than our tables allow. Patience cannot replace calories."]
		}
	},
	{
		theme: "Intelligence and Deception", subject: "double agent report",
		facts: [
			["The report names the western gate, but its patrol times repeat an old captured schedule.", "It correctly names the western gate, yet all three patrol times come from a schedule captured two months ago."],
			["Scouts found the promised signal cloth, then saw fresh boot prints leading away from the ambush site.", "The blue signal cloth was present. So were twenty fresh prints leaving the ridge before our scouts arrived."],
			["The source mixes one verifiable detail with one urgent claim, forcing command to act before checking either.", "Each message pairs a true supply detail with an urgent attack warning. The truth buys the lie enough time to move troops."],
			["Use it to choose where scouts look, never as the sole reason to move a company.", "Treat the report as a search cue. No company moves until a second source confirms the route and the time."],
			["The courier's family remains inside enemy territory and can be punished for either truth or deception.", "The source's sister and two children remain across the line. Every message may be written with a guard beside them."],
			["Vostok rewards decisive warnings and lets urgency outrun verification.", "Vostok likes a report that permits movement now. That appetite lets an enemy steer a whole battalion with one frightened courier."],
			["The first mistake was asking the source for predictions instead of facts already observed.", "Handlers asked where the enemy would attack. They should have asked what the source personally saw, where, and at what hour."],
			["Do not punish civilians named by a source until independent evidence places them in the act.", "A name in one report is not a conviction. Search the place, check the time, and keep families out of the interrogation room."],
			["Every accurate detail may be bait designed to make the final false warning believable.", "The strongest case against using the report is deliberate cultivation: five small truths can purchase one fatal lie."],
			["Limit the source to observed facts and require two independent confirmations for troop movements.", "Future reports need time, place, and direct observation. Any troop move requires a scout or intercepted message to agree."]
		],
		history: {
			vostok: ["We reward a warning that lets us move and punish doubt as delay.", "Vostok wants an answer before the mud dries. A patient liar can march us by feeding that hunger."],
			aegis: ["Our source ratings look precise but inherit the handler's first assumption.", "Aegis gives the source a confidence grade, then cites that grade as evidence. A number cannot independently confirm itself."],
			sarab: ["We admire the divided tongue and sometimes forget it can divide us too.", "Sarab knows how to turn an agent. We therefore overestimate our ability to notice when the enemy has turned the agent back."]
		}
	},
	{
		theme: "Command and Authority", subject: "unauthorized rescue mission",
		facts: [
			["Six soldiers left the line to recover two trapped scouts; four returned with one scout.", "Six soldiers crossed the orchard without orders. Four returned carrying one scout; the second scout and two rescuers remain missing."],
			["They reached the stone wall before enemy archers cut the marked return lane.", "The team reached the wall in seven minutes. A red flare exposed their return lane, and archers closed it before the wounded could move."],
			["Nobody assigned rear security because every volunteer expected someone else to do it.", "All six focused on the trapped scouts. No one watched the orchard gate, so the first warning was an arrow through the medic's pack."],
			["A rescue is justified only with a route, a time limit, and someone still holding the original position.", "Send four with smoke, a marked return route, and a ten-minute cutoff; the rest must keep the line open for their return."],
			["The trapped scout's closest friend made the decision and carried the guilt back alone.", "The volunteer who led it was the scout's tentmate. Now he counts two missing rescuers against the one friend he brought home."],
			["Vostok praises the attempt after the same impulse has weakened the line.", "Vostok calls it loyalty and adds the dead to the roll. We need courage with a clock, not courage that empties a trench."],
			["The officer dismissed the first rescue request without checking whether smoke support was available.", "The request was refused in eight words. Nobody checked the two smoke pots sitting fifty paces away, so the soldiers went without them."],
			["No commander may order abandonment without first confirming the trapped soldiers' position and condition.", "Abandonment cannot be a reflex. Confirm who is alive, mark the route, and state what risk would end the attempt."],
			["One successful rescue can normalize disobedience that ruins the next coordinated defense.", "The best argument against praising it is precedent: next time three groups may leave three posts for three different friends."],
			["Write a rapid rescue drill and give squad leaders authority to launch it within fixed limits.", "Squad leaders need a four-person rescue drill, smoke access, and a ten-minute limit they can invoke without waiting for headquarters."]
		],
		history: {
			vostok: ["We praise the charge and count the empty trench afterward.", "Vostok calls an unauthorized rescue brave because it is brave. We are slower to admit that bravery can uncover everyone behind it."],
			aegis: ["Our approval chain can turn a ten-minute rescue into a forty-minute recovery.", "Aegis demands authorization from a headquarters that cannot see the orchard. By approval, rescue has become body recovery."],
			sarab: ["We let small teams choose, then give them too little structure for retreat.", "Sarab grants initiative, but a volunteer group still needs a cutoff signal. Freedom without a return rule spends people quietly."]
		}
	},
	{
		theme: "Morale and Memory", subject: "unit after heavy losses",
		facts: [
			["Seventeen of forty soldiers answered morning roll, and five of those are walking wounded.", "The company began with forty. Seventeen answer roll now; five are bandaged, and three keep setting places for people who died."],
			["Command held a victory formation before the burial detail had finished marking graves.", "The survivors were ordered to cheer at noon while the burial party was still cutting seventeen names into wet boards."],
			["The unit is still receiving ordinary patrol orders because the roster was never corrected.", "Headquarters still assigns a forty-person patrol load. Seventeen survivors cover it by skipping sleep and leaving wounded cooks on watch."],
			["Give them one defensible sector, two nights of sleep, and replacements who train beside them before patrol.", "Hold one quiet sector for forty-eight hours, feed them hot meals, and pair replacements with survivors before anyone returns to patrol."],
			["The newest replacements inherit bunks and equipment whose owners' names are still written on them.", "Replacements sleep under dead soldiers' names because nobody erased the bunk boards. The survivors read each name every night."],
			["Vostok treats endurance as proof that a unit can absorb one more demand.", "Vostok sees seventeen people still standing and calls the company alive. That turns endurance into permission to spend them twice."],
			["The casualty roster must be corrected before another order uses the old company strength.", "Every later mistake begins with the false number forty. Fix the roster, or every ration, watch, and patrol order remains a lie."],
			["Do not force survivors to celebrate before they have named and buried their dead.", "A banner can wait. The dead get names, the survivors get sleep, and only then may command ask what victory means."],
			["Removing the unit may confirm its fear that the dead achieved nothing and the survivors are being discarded.", "Rest can feel like dismissal. If they leave the line, tell them who takes the post and why their defense made that relief possible."],
			["Correct the roster, finish the graves, and rebuild around a smaller mission before adding replacements.", "Make the number honest, bury the dead, assign one achievable sector, and introduce replacements as help rather than substitutes."]
		],
		history: {
			vostok: ["We see survivors standing and assume they are ready.", "Vostok sees seventeen still standing and gives them forty people's work. Endurance becomes the excuse for spending them again."],
			aegis: ["Our readiness table stays green until somebody updates the casualty field.", "Aegis can display a full company because the report is late. Procedure turns seventeen exhausted people into forty available assets."],
			sarab: ["We scatter after loss and sometimes leave grief scattered with us.", "Sarab disperses survivors for safety, but isolated pairs cannot bury a company or tell the same story about what happened."]
		}
	},
	{
		theme: "Civilians in the War", subject: "hospital claimed by both sides",
		facts: [
			["The hospital treats both uniforms and has one marked entrance wide enough for stretchers.", "The sisters treat anyone unarmed inside. The south door is the only stretcher entrance, and both armies have placed archers above it."],
			["A supply cart was searched twice, spoiling fever medicine while the seals were argued over.", "Two checkpoints opened the same sealed chest. Fever medicine sat in sun for three hours and reached the ward warm and useless."],
			["Each side calls the roof observers medical guards, while both report troop movement from there.", "The roof is the failure. Four supposed guards carry signal mirrors and turn a protected hospital into an observation post."],
			["Create one unarmed inspection team and a weapon-free lane from the south road.", "Mark the south road, remove every roof observer, and let two unarmed inspectors verify each cart once before it enters."],
			["Patients who cannot walk are accused of sheltering whichever uniform occupies the next bed.", "Thirty-one patients cannot leave. Six are children, and every change at the gate makes their families cross a new line for water."],
			["Vostok wants one guard force large enough to deter seizure, making the hospital look occupied.", "Vostok solves uncertainty with more boots. A platoon at the doors would protect the wards and destroy their neutrality at the same time."],
			["Armed observers must leave the roof before either side can demand new guarantees.", "Both sides broke the agreement by using the roof. Remove the mirrors first; arguments over flags can follow after the hospital stops spotting targets."],
			["No wounded person may be removed for questioning until a doctor says transport will not kill them.", "Interrogation waits for medical clearance. A prisoner on a surgical table is a patient until the surgeon says otherwise."],
			["Neutrality may be impossible while the hospital sits on the only road through the district.", "The serious objection is geography: the hospital overlooks the road every army needs, so a painted symbol cannot remove its military value."],
			["Move observers off the roof, establish one inspection, and post the same patient rules at both checkpoints.", "One search, no roof signals, medical control over removal, and identical written rules at both lines are the minimum agreement."]
		],
		history: {
			vostok: ["We add guards until protection looks like occupation.", "Vostok would ring the hospital with a platoon. The wards might be safer tonight, but nobody would believe they were neutral tomorrow."],
			aegis: ["We mistake a signed protection plan for control of the people violating it.", "Aegis has clear hospital rules, two checkpoint forms, and no single officer responsible when those rules conflict."],
			sarab: ["We use high roofs for eyes and pretend a healer's roof is different.", "Sarab values every vantage point. Leaving signal mirrors above the wards turns sanctuary into camouflage, whatever the guard calls himself."]
		}
	},
	{
		theme: "Politics and History", subject: "failed prewar treaty",
		facts: [
			["The treaty fixed the border but never defined who controlled the three wells along it.", "The ink drew a border through dry land and left three wells under shared control without guards, maintenance, or a drought rule."],
			["The first violation was a locked well gate during drought, not a troop crossing.", "During the drought, a local captain chained the central well. The other side cut the chain; troops arrived two days later."],
			["The treaty named national commanders but gave village councils no way to report local violations.", "Only capitals could file a complaint. By the time a village message reached either commander, local guards had already traded arrows."],
			["Use a joint well crew and let either village summon inspectors without waiting for a capital.", "Each well needs mixed keepers, a public water count, and an inspection signal that either village can raise the same day."],
			["Border families married and grazed animals across a line that officials treated as empty ground.", "Twenty-three households use land on both sides. The treaty made ordinary grazing look like infiltration overnight."],
			["Vostok believes a firm line prevents argument, even when life on the ground crosses it daily.", "Vostok made the border unmistakable and the wells ambiguous. We defended the line while thirst decided the actual conflict."],
			["The missing drought rule mattered more than the ceremony that announced the treaty.", "Everyone photographed the signing. Nobody assigned who unlocks a well when the water falls below the third stone."],
			["Water cannot be withheld from civilians as punishment for a military violation.", "Close a road if troops cross it; do not close a well to families. Thirst is not a lawful border guard."],
			["A new agreement may only pause the war if local captains can ignore it without consequence.", "The strongest objection is enforcement: both capitals signed before, and neither removed the captain who first chained the well."],
			["Put village councils in the agreement and publish drought, inspection, and removal procedures at every well.", "The replacement needs local signatories, mixed well crews, measured drought rules, and automatic removal for any guard who blocks civilian water."]
		],
		history: {
			vostok: ["We defend a clean border on a map where real families cross every day.", "Vostok prefers one hard line. The wells, marriages, and grazing paths ignore it, so firmness without local rules manufactures violations."],
			aegis: ["Our treaty defined escalation between armies but omitted the first village complaint.", "Aegis wrote precise national procedures and no local reporting channel. The mechanism begins only after arrows are already flying."],
			sarab: ["We rely on custom to fill blank ink, but custom cannot bind a new captain.", "Sarab expected well-sharing traditions to survive the border. A chained gate proved memory is not enforcement."]
		}
	},
	{
		theme: "Weapons and Technology", subject: "prototype defensive barrier",
		facts: [
			["It stops arrows and a charging body for about twelve seconds; rain cuts that time in half.", "The field turns arrows and one full-speed charge for twelve seconds on dry stone. In rain it lasted six, then failed without warning."],
			["Two anchors sank in mud, the field folded inward, and three testers left with burns.", "In the third trial, two anchors sank three fingers into mud. The field folded toward the squad and burned three testers through leather."],
			["One cracked anchor kills the whole field while the warning lamp stays blue.", "A hairline crack in any anchor collapses the full barrier. The lamp reads anchor power, so it stays blue even while the field is dying."],
			["Use it to cover a stretcher crossing, with shields ready when it fails; do not build an assault around it.", "It can buy twelve seconds for wounded crossing open ground. A shield line must already be moving before the sixth second."],
			["The front rank cannot see the anchor lamps and takes the collapse first.", "Engineers watch the lamps from behind. The front rank sees only blue light, so they receive the collapse and the burns before anyone shouts."],
			["Vostok bunches behind promised cover, turning one failure into one neat target.", "Vostok bunches behind anything that promises cover. When the third barrier died, the whole squad was standing in one neat target."],
			["The trial should have stopped when the first anchor tilted; command reset it and ran again.", "An engineer marked the south anchor unsafe after trial two. The officer rotated the stone, cleared the mark, and ordered trial three."],
			["No soldier should enter the field unless they can see a timed retreat signal independent of the blue lamp.", "The barrier needs a separate red countdown visible from the front. If that signal fails, nobody enters the field."],
			["The barrier may encourage officers to cross ground they would reject without it.", "Its worst effect may be confidence: a twelve-second wall makes an exposed route look safe enough to order, but not safe enough to survive."],
			["Fix the anchors, add a front-facing countdown, and repeat the rain trial with nobody inside.", "Rebuild the anchors with broad feet, add a red countdown, and pass three wet trials with weighted dummies before another soldier enters."]
		],
		history: {
			vostok: ["Yes. At the third trial, we crowded behind it. When it failed, it left the whole squad bunched in one place.", "At the third trial, we packed in behind the blue light. When it failed, the whole squad was standing in one place. We don't call it cover anymore."],
			aegis: ["At Northwatch. The lamp stayed blue after an anchor cracked. A section advanced, and the field collapsed.", "At Northwatch, the lamp stayed blue after an anchor cracked. The checklist said advance, so six soldiers did. Then the field collapsed."],
			sarab: ["At Qamar Pass. We used one as a decoy and left its two operators exposed.", "We used a captured barrier as a decoy at Qamar Pass. The scouts got through. The two operators we left behind did not."]
		}
	},
	{
		theme: "Field Survival", subject: "desert water emergency",
		facts: [
			["The patrol has nine full skins for fourteen people and the mapped well is dry.", "Nine skins remain for fourteen people. The western well is dry, and the next confirmed water is an eighteen-hour night march."],
			["A cracked pack frame punctured two skins before anyone noticed the wet sand.", "A loose bronze buckle rubbed through two skins. The water trail ran for nearly a mile before the rear scout smelled wet sand."],
			["The patrol is rationing by container instead of measuring what each skin actually holds.", "Three skins are smaller trade skins, but the issue plan counts all nine equally. The patrol has six fewer cups than the count suggests."],
			["Rest through afternoon heat and move at dusk toward the stone cistern, not the unconfirmed palm grove.", "Shade now, one measured cup each, then an eighteen-hour march to the stone cistern after sunset. The palms are only a traveler's rumor."],
			["The wounded scout needs twice the water and cannot carry a share of the load.", "The wounded scout is fevered and needs two cups per issue. Four others must rotate his weight while receiving less than he does."],
			["Vostok keeps the whole group together even when two fast scouts could confirm water before dusk.", "Vostok fears dividing strength. Here, sending two light scouts now may save the fourteen who cannot afford a wrong night march."],
			["Nobody inspected the pack frames when the patrol transferred water at noon.", "They counted seals and missed the loose buckle. A hand around every frame would have found the edge before it opened two skins."],
			["Do not abandon the wounded scout while enough water remains to reach confirmed shelter together.", "The wounded stays while the cistern remains reachable. If the route fails, the decision changes with measured distance, not panic."],
			["The stone cistern report is three weeks old and may be no safer than the palm rumor.", "The hard objection is stale knowledge: the cistern was full three weeks ago, and another patrol may have emptied it yesterday."],
			["Measure every skin, pad every buckle, send two scouts, and march only toward confirmed stonework.", "Recount in cups, wrap the frames, dispatch two fast scouts, and leave at dusk only when one returns with a marked route."]
		],
		history: {
			vostok: ["We keep everyone together even when two fast scouts could save the column.", "Vostok treats separation as weakness. Two scouts traveling light could confirm the cistern before fourteen people spend the night's water walking wrong."],
			aegis: ["Our issue table assumes every sealed skin holds the same amount.", "Aegis has nine containers on the sheet. Three are smaller trade skins, so the precise ration plan begins six cups short."],
			sarab: ["We trust our memory of wells after the desert has changed them.", "Sarab knows the old routes and can become proud of that knowledge. A remembered well is not water until a scout touches it."]
		}
	},
	{
		theme: "The War's End", subject: "disarming rival militias",
		facts: [
			["Three militias agreed to surrender heavy weapons, but none will hand over bows before the others.", "Three captains signed. Each will surrender siege gear now, but each keeps bows until the other two place theirs in the same yard."],
			["The first collection point was beside one militia's burned meeting hall and was rejected as a victory display.", "Command chose the burned Red Hall for collection. Two militias saw trophies where officials saw open ground and never arrived."],
			["The weapon count ignores hidden spare strings, arrowheads, and workshop stock.", "Collectors count finished bows. One cooper can hide two hundred arrow shafts as barrel staves, so the inventory proves less than it claims."],
			["Collect siege gear first, register personal bows, and schedule simultaneous handover at neutral granaries.", "Move heavy gear now. Then seal personal bows at three neutral granaries in the same hour with mixed witnesses at each door."],
			["Villages rely on some militia members to guard livestock from raiders after the army leaves.", "Seven villages have no watch beyond the militia. Taking every bow tonight leaves their herds open before a civil guard exists."],
			["Vostok equates visible piles of weapons with control, even when workshops remain untouched.", "Vostok wants one mountain of surrendered bows for the announcement. A photographable pile does not close a hidden workshop."],
			["The neutral collection sites should have been agreed before surrender numbers were announced.", "Officials published quotas first and sites later. That made every location look chosen to humiliate whoever had lost there."],
			["No community loses defensive weapons before a civilian watch is trained and accountable.", "Heavy weapons go now. Village bows go only as named civil guards take responsibility for night watch and weapon storage."],
			["Registration may legitimize militias by turning their captains into permanent political gatekeepers.", "The strongest objection is status: signing with captains can convert wartime coercion into official authority over their villages."],
			["Use neutral sites, simultaneous seals, workshop inspections, and a dated transfer to civilian guards.", "Three granaries, one hour, mixed witnesses, workshop checks, and a thirty-day handoff to elected village watches make disarmament measurable."]
		],
		history: {
			vostok: ["We want one visible pile and may stop counting once it is tall enough.", "Vostok trusts the spectacle of surrendered weapons. A high pile in the square can hide three workshops still cutting new bows."],
			aegis: ["Our inventory can certify weapons while missing the coercive network that supplied them.", "Aegis can tag every bow and still leave the captains controlling food, workshops, and witnesses. Disarmament is larger than inventory."],
			sarab: ["We know every household can hide a bow and may use that truth to excuse collecting none.", "Sarab understands hidden arms. That can become fatalism: because perfect collection is impossible, we postpone the credible collection that is possible."]
		}
	}
];

// Refusals used to be slot-filled — `The ${s} trial is not yours to hear about.` —
// which produced "the disarming rival militias" and "the unauthorized rescue mission
// trial". Each topic now carries three authored refusals per faction, in that
// faction's own register, and depth selects among them. Nothing is substituted.
const refusals = {
	"contested river bridge": {
		vostok: ["The bridge is ours to worry about. Walk the long way like everyone else.",
			"We counted what that crossing cost. You do not get to read the number.",
			"Ask me about the weather instead. I will answer that one gladly."],
		aegis: ["Crossing dispositions are restricted. That is the whole of my answer.",
			"The tollhouse action is under review. Nothing from it is releasable.",
			"You are asking me to describe the eastern approach in detail. No."],
		sarab: ["The river tells its own story. Go and stand beside it, if you like.",
			"I will not name what waits on the far bank. Count the carts that came back.",
			"Some crossings are made in silence. So are some conversations."]
	},
	"winter ration reserve": {
		vostok: ["How much bread we have is not a stranger's business. Ever.",
			"You want to know when we get hungry. Wonderful question. No.",
			"The cellar is full enough for now. That is the entire report."],
		aegis: ["Stock levels are protected information. Ask the quartermaster for a price list instead.",
			"Reserve accounting is an internal matter, and it is going to stay internal.",
			"You are asking me to describe a weakness of ours. Declined."],
		sarab: ["A man does not show a thirsty stranger the bottom of his jar.",
			"The store is deep or shallow. Both answers cost you nothing and me a great deal.",
			"We eat well enough. That is all the arithmetic you are owed."]
	},
	"double agent report": {
		vostok: ["Names stay out of your mouth and mine. That is not negotiable.",
			"Someone lied to us. We are handling it ourselves. Go away.",
			"If I told you who, you would be the next thing we handled."],
		aegis: ["Source protection is absolute. There is no version of this I can give you.",
			"The matter is under counter-intelligence review and will remain so.",
			"You have asked a question that identifies people. The answer is no."],
		sarab: ["A snake in the tent is killed quietly, not described to visitors.",
			"I will not say the name. Names travel further than men do.",
			"The one you are asking about may be listening. Consider that, and then leave it."]
	},
	"unauthorized rescue mission": {
		vostok: ["They went without orders. They came back short. That is all you get.",
			"The hearing is ours. Our dead, our argument, our business.",
			"You want the story for the telling of it. Find another."],
		aegis: ["Proceedings are ongoing. Comment would prejudice them.",
			"Personnel matters are protected, particularly this one.",
			"The board has not reported. Neither will I."],
		sarab: ["Those who went are judged by those who waited. You did neither.",
			"The desert already heard the argument. It does not repeat itself.",
			"Grief has its own court. Its doors are closed to guests."]
	},
	"unit after heavy losses": {
		vostok: ["We are down. We are still here. Those are the only two facts you need.",
			"You are asking how badly we were hurt. Guess, and keep it to yourself.",
			"Count the empty bunks yourself if you have the stomach. I will not read them out."],
		aegis: ["Strength returns are restricted, for reasons I would think obvious.",
			"Casualty figures are reported upward, not outward.",
			"You are asking whether we can be pushed. Ask by trying, or do not ask."],
		sarab: ["We are fewer. The wind is also fewer, some days, and it still moves the dunes.",
			"Do not ask a house how many stones it has lost. Ask whether it stands.",
			"Our number is between us and the sand."]
	},
	"hospital claimed by both sides": {
		vostok: ["Wounded are wounded. Beyond that it is not your argument.",
			"The place is a hospital. Both sides say so. Neither side is honest. Enough.",
			"I will not discuss who holds the beds. People are in them."],
		aegis: ["The status of that facility is subject to negotiation and is not for discussion.",
			"Protected-site matters are handled at command level. Not here, not by me.",
			"Any statement I make becomes a claim. I decline to make one."],
		sarab: ["The sick belong to no flag. Neither does my answer.",
			"A roof over the dying is not a prize. Do not make me explain it twice.",
			"Ask the physicians. They will also refuse, but more kindly."]
	},
	"failed prewar treaty": {
		vostok: ["Old paper. It failed. Everyone who signed it is dead or lying.",
			"We were there. That is why I am not talking about it.",
			"Read the treaty. Then read the casualty lists. Draw your own line between them."],
		aegis: ["The negotiating record is sealed and will stay sealed.",
			"Attributing the failure is a political act. I do not perform political acts.",
			"That file was closed deliberately, and it is going to stay closed."],
		sarab: ["Promises made in a cool room do not survive the noon that follows.",
			"The treaty is a well everyone poisoned and no one will admit to.",
			"I was not at the table. I was at the funerals. Ask someone who was at the table."]
	},
	"prototype defensive barrier": {
		vostok: ["It works or it does not. You will find out the same way we did.",
			"You are asking where it breaks. Marvellous. No.",
			"Whatever it is, it is not yours to inspect."],
		aegis: ["Capability parameters are restricted, categorically and without exception.",
			"Trial data is not releasable, including the parts that flatter us.",
			"You have asked for a failure mode. That is precisely what is protected."],
		sarab: ["A shield is best described by the one who strikes it. Try, and report back.",
			"We do not measure the height of our walls aloud, friend.",
			"What holds and for how long is a secret worth more than the wall itself."]
	},
	"desert water emergency": {
		vostok: ["Water is short. Water is always short here. That is the whole briefing.",
			"You are asking how long we can last. Long enough. Move on.",
			"Wells are never discussed with people who might come to want one."],
		aegis: ["Water status is operationally sensitive and will not be disclosed to you.",
			"Consumption figures and reserve levels are both restricted information.",
			"That question maps the limit of our endurance. Declined."],
		sarab: ["I will not tell you how many wells we hold. Count the ones you found empty.",
			"Thirst is a private matter right up until it is everyone's.",
			"The one who asks after water is either thirsty or planning. Neither earns an answer."]
	},
	"disarming rival militias": {
		vostok: ["Who gives up what, and when, is being argued by people above me. Loudly.",
			"Ask the militias yourself. They will tell you three different things, all wrong.",
			"Nothing is settled, none of it is yours, and not today."],
		aegis: ["Terms under negotiation are not disclosed while they are under negotiation.",
			"Compliance schedules are restricted until they are agreed and verified.",
			"Any figure I gave you would be a bargaining position. So, no."],
		sarab: ["No one lays down the blade first. That is the entire difficulty, and I will say no more.",
			"You ask who trusts whom. Nobody. Now let it rest.",
			"The bows come down when the reason to hold them does. Not before, and not in conversation."]
	}
};

function response(topic, depth, faction, band) {
	if (band === "negative") {
		const authored = refusals[topic.subject];
		if (!authored) throw new Error(`no authored refusals for topic "${topic.subject}"`);
		const lines = authored[faction];
		return lines[depth % lines.length];
	}
	const pair = depth === 5 ? topic.history[faction] : topic.facts[depth];
	return pair[band === "positive" ? 1 : 0];
}

for (const topic of topics) {
	const set = openings[topic.subject];
	if (!set || set.length !== 3) {
		throw new Error(`topic "${topic.subject}" needs three authored depth-0 openers`);
	}
}

const options = [];
const responses = {};
for (const topic of topics) {
	const branch = slug(topic.subject);
	for (let depth = 0; depth < prompts.length; depth++) {
		const responseClass = `deep_${branch}_${depth + 1}`;
		for (const [toneIndex, tone] of ["positive", "neutral", "negative"].entries()) {
			options.push({
				id: `${responseClass}_${tone}`,
				// Depth 0 is authored per topic: the shared opener was written for the
				// barrier and asked what a bridge "does" when applied to everything else.
				text: depth === 0
					? openings[topic.subject][toneIndex]
					: prompts[depth][toneIndex].replace("{subject}", topic.subject),
				response: responseClass,
				tone,
				branch,
				branch_depth: depth,
				next_depth: depth === prompts.length - 1 ? -1 : depth + 1,
				topic: topic.subject.replace(/\b\w/g, (letter) => letter.toUpperCase()),
				weight: depth === 0 ? 6 : 10
			});
		}
		responses[responseClass] = {};
		for (const faction of ["vostok", "aegis", "sarab"]) {
			responses[responseClass][faction] = {
				negative: [response(topic, depth, faction, "negative")],
				neutral: [response(topic, depth, faction, "neutral")],
				positive: [response(topic, depth, faction, "positive")]
			};
		}
	}
}

if (options.length !== topics.length * 30) {
	throw new Error(`Expected ${topics.length * 30} options, generated ${options.length}`);
}
// Regression for the reference exchange: this question must ask about limited use,
// not confidence, because its answer describes a stretcher-cover use case.
const barrier = topics.find((topic) => topic.subject === "prototype defensive barrier");
if (prompts[3][0] !== "How would you use it without betting the operation on it?"
		|| !barrier.facts[3][0].includes("stretcher crossing")
		|| !barrier.facts[3][0].includes("do not build an assault around it")) {
	throw new Error("Prototype barrier limited-use question no longer matches its answer");
}
if (prompts[5][0] !== "You've dealt with this before?"
		|| !barrier.history.vostok[1].startsWith("At the third trial")
		|| !barrier.history.aegis[1].startsWith("At Northwatch")
		|| !barrier.history.sarab[1].startsWith("We used a captured barrier")) {
	throw new Error("Prototype barrier history question no longer matches its answers");
}
fs.writeFileSync(OUTPUT, JSON.stringify({ category: "deep_branches", options, responses }, null, 2) + "\n");
console.log(`gen-deep-dialogue: ${topics.length} authored topics, ${options.length} options, ${Object.keys(responses).length * 9} gated response lines`);
