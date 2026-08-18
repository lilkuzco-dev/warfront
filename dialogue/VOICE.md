# Warfront dialogue voice guide

Every soldier response line must pass the **cover-the-name test**: hide the faction
label and a reader should still guess who is talking. Player option lines are the
player's voice — plain, direct, occasionally characterful, never faction-flavored
(the player belongs to no faction).

## Vostok — mass and attrition
Blunt, fatalistic, dark humor, collective pronouns. Sentences short like rations.
Death is weather: it comes, you dig. Pride in endurance, not skill. "We", "the
line", "the quota". Never impressed, rarely alarmed. Jokes are gallows jokes,
delivered flat.

- *"You dig, you hold, you die tired. It's a living."*
- *"The line held. The line always holds. That's what the line is for."*
- *"Two of ours for one of theirs. Good arithmetic, bad day."*
- Vengeful-band flavor: cold arithmetic, not rage. *"You cost us eleven. We count,
  friend. We always count."*

## Aegis — combined-arms precision
Clipped, professional, acronym-flavored, courteous-but-guarded. Radio discipline
leaks into speech: short transmissions, confirmations, no wasted syllables.
Politeness is procedure, not warmth. Refers to doctrine, sectors, assets, ROE.

- *"Copy. State your business and keep your hands visible."*
- *"Sector's green. Keep it that way."*
- *"Assistance rendered will be logged. Aegis remembers its ledgers."*
- Vengeful-band flavor: procedural threat. *"You are flagged. One incident from
  weapons-free. Walk carefully."*

## Sarab — asymmetric shadow
Indirect, proverb-leaning, watchful, poetic threat. Answers questions with images.
Time and desert do the fighting; patience is the weapon. Never states strength,
never admits weakness. Warnings arrive dressed as hospitality.

- *"The well is deep and the rope is short. Sit; drink what you can reach."*
- *"We are not many. Neither are scorpions."*
- *"The dune moved in the night. So did we."*
- Vengeful-band flavor: soft-spoken certainty. *"The sand keeps what it is given.
  It has been given your name."*

## Band flavor rules (all factions)
- **negative** (vengeful/hostile/cold): no small talk offered back; warnings,
  conditions, contempt. A vengeful grunt does not chat — he counts your sins.
- **neutral**: guarded, transactional, occasional dry color.
- **positive** (warm/friendly/devoted): rank-and-file warmth in the faction's own
  key — Vostok shares the joke, Aegis shares the intel, Sarab shares the shade.

## Craft rules
- No line over ~140 characters; most under 100.
- No lore contradictions: three factions only; tech levels 0–4; no firearms.
- Officers speak with more authority and longer sentences than grunts;
  quartermasters talk stock, prices, and supply lines.
- Never break the fourth wall; never reference game mechanics by name
  ("standing" → "your name with us", "disposition" → "how we look at you").

---

# Craft addendum (0.3.1) — the devices behind each voice

The first corpus reached scale by slotting a topic noun into a shared sentence frame:
one frame, ten subjects, ten lines. That is what produced *"On the line, ammunition
reserves **is** measured by whether dawn finds us standing"*, *"the impatient see only
mountain **passes** ... curling around **it**"*, and a hundred copies of *"Have the
realities of X changed much since the war began?"* A frame cannot agree in number with
a slot it has never seen, and a reader feels the seam long before they can name it.

**The rule that follows: no line is written by substitution. Every line is authored
for its own subject.** Breadth is bought with writing or not at all.

Each faction gets a distinct rhetorical grammar, so the cover-the-name test is carried
by *sentence construction*, not just vocabulary.

## Vostok — parataxis and litotes

Clauses laid side by side, joined by commas and full stops rather than subordination;
the Anglo-Saxon core of the language, one and two syllables. The device is **litotes** —
the deliberate understatement of Old English heroic verse (*"that was a good king"*)
carried into the plain trench register of Owen and Babel. Horror is reported as
inconvenience; that gap is the joke and the grief at once.

- Parataxis: *"You dig, you hold, you die tired. It's a living."*
- Litotes: *"Nobody froze that night who kept moving. Most kept moving."*
- Epigram to close: short sentence after a long one. The full stop does the work.

## Aegis — anaphora, asyndeton, meiosis

Procedure as rhythm. **Anaphora** (repeated sentence openings) mimics a checklist;
**asyndeton** (dropped conjunctions) mimics radio traffic where every syllable costs.
**Meiosis** downgrades catastrophe to a reporting category. The lineage is the military
dispatch and the institutional voice of Kipling's professionals and Le Carré's Circus:
courtesy that is procedure, never warmth.

- Asyndeton: *"Marked, logged, corrected. Next."*
- Anaphora: *"We survey it. We route around it. We do not admire it."*
- Meiosis: *"We lost the bridge and eleven people. The report calls it a setback."*

## Sarab — parallelism, metaphor, apophasis

The balanced couplet of wisdom literature — Proverbs and Ecclesiastes, and the Arabic
proverb tradition behind them — where the second half answers, inverts or completes the
first. **Antithesis** carries the argument; **metaphor** drawn from water, sand, rope,
shade and heat carries the threat; **apophasis** says a thing by declining to say it.
Never state strength; never admit weakness.

- Antithesis: *"The impatient count the passes. The patient count the ways around them."*
- Metaphor: *"Rope frays where you cannot see. So does a promise."*
- Apophasis: *"I will not tell you how many wells we hold. Count the ones you found empty."*

## Enforced by the validator

`tools/validate-dialogue.js` now fails the build on: firearms vocabulary (this war has
bows, blades and siege engines — no guns), a plural noun answered by a singular pronoun,
`the` stranded before a gerund phrase, and any first-four/last-four-word sentence frame
reused past a small threshold. A frame that reappears is a template growing back.
