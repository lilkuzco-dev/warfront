# Warfront verification log

## v0.2.0 — Stage 0 preflight (2026-08-15)

### Clean build at v0.1.0
- `git pull` → up to date at `ed14951` (v0.1.0 tag). `./gradlew build` clean → `warfront-0.1.0.jar`. ✅

### Open Phase 1 item: client-camera soldier rendering
- Phase 1 headless battery could not verify in-camera soldier rendering (quickplay
  singleplayer stalls on a GUI screen for server-created worlds).
- **Settled by field report**: the friend group has been playing v0.1.0 and reports
  *visual* bugs on soldiers (hand/arm model issues) — i.e. soldiers do render on the
  client for all factions; the renderer registration and textures work. The remaining
  defect is an arm/overlay pose issue, fixed in Stage 5 of v0.2.0 (see below).
- Code-level root cause identified for the hand/arm issue: `SoldierRenderer` bakes
  `ModelLayers.PLAYER` (the player layer, which includes the skin overlay parts —
  sleeves/jacket/pants) into a plain `HumanoidModel`. Since the 1.21.3 render
  refactor the whole root part tree renders, but only `PlayerModel` copies arm/leg
  poses onto the overlay parts. Result: overlay cubes render un-posed while the
  arms/legs animate — detached "ghost limb" shells. Fix target: Stage 5.
- In-camera before/after screenshots to be captured in the Stage 5/6 client session
  (one client launch covers the open Phase 1 confirmation and the fix validation).

### Reported bug: "M1911 backwards rendering"
- **Cannot be reproduced: no M1911 (or any firearm) exists in Warfront v0.1.0, nor in
  vibranium or menagerie, nor in any mod in the installer manifest** (fabric-api,
  lithium, sodium, vibranium, warfront). Soldiers wield vanilla swords scaled by tech
  tier; the firearms overhaul is explicitly fenced out of this phase.
- Working hypothesis: the report refers to the soldier *held-item* rendering looking
  wrong from certain angles — which is the same arm-pose/overlay defect above — or to
  a mod outside the managed pack. Stage 5 will fix the arm issue and batch-audit all
  Warfront item model display transforms (currently: `sandbag_station`).
- ⚑ Flag for the Emperor: if a real M1911 item exists somewhere (screenshot/mod name),
  send it over and it gets its own fix; nothing in the managed pack contains one.

### License decision (Stage 0.4)
- Repo license was **CC0-1.0 — the Fabric example-mod template default**, not an
  intentional choice (it shipped verbatim with the template skeleton). Per the
  Garrison Update plan, relicensed to **LGPL-3.0-or-later**: `LICENSE` replaced with
  the LGPL-3.0 text, `COPYING.GPL` added (LGPL-3.0 incorporates GPL-3.0 by
  reference), `fabric.mod.json` license field updated, README section added.
  This satisfies Repurposed Structures' reuse condition (using mod must be open
  source) and lets imported LGPL NBTs carry their license correctly.

### Version
- Bumped to `0.2.0-dev`.

## v0.2.0 — Stage 5: rendering fixes (2026-08-15)

### Hand/arm model fix (root cause)
- Phase 1's `SoldierRenderer` baked `ModelLayers.PLAYER` (which includes the skin
  overlay parts — sleeves/jacket/pants) into a plain `HumanoidModel`. Since the render
  refactor the whole part tree renders, but only `PlayerModel` copies limb poses onto
  the overlay parts → overlay cubes rendered frozen at origin while limbs animated
  (detached "ghost limb" shells). **Fixed**: renderer now uses `PlayerModel` with an
  `AvatarRenderState`-based soldier state; overlay layers are hidden (uniforms are flat
  recolors), hat layer kept for hair depth. In-camera before/after screenshots land in
  the Stage 6 client session.

### "M1911 backwards rendering"
- Re-confirmed after Stage 5: **no M1911 or any firearm item exists in the managed
  pack** (see Stage 0 notes). Nothing to fix under that name; the visible soldier
  defect was the arm/overlay issue above.

### Item model transform audit
- All Warfront item models audited for display-transform mistakes:
  - `sandbag_station` — `minecraft:block/cube_all` parent → vanilla block transforms ✓
  - `bunk` — custom elements model with `minecraft:block/block` parent → vanilla block
    transforms ✓
- Warfront ships no handheld/custom-transform items; soldiers wield vanilla swords
  (vanilla transforms). Audit clean.

## v0.2.0 — Stage 6 battery (headless dev server, carpet fake players; 2026-08-15)

Setup: `tools/devserver.sh`, fake players Watcher/Ranger/Scout, battery datapack in
`run/warfront-test/datapacks/battery` (reinforce 0.5 min, roam 20 s @ 100% — speed
overrides only; garrison ranges untouched).

### Worldgen & bases
- Datapack loads with zero registry errors; all 9 tier×faction structures `/locate`
  successfully (outposts near spawn; HQs ~3,000 blocks out at the 112-chunk spacing). ✅
- `/place structure` assembles plates + jigsaw sprawl and spawns seed soldiers (17 for
  an outpost + FB pair) — but vanilla `/place` writes no chunk references, so
  command-placed bases are never *discovered*; discovery verified on naturally
  generated bases. ✅ (noted in BaseManager)
- Natural sarab outpost: seed soldiers adopt on first tick → base registered
  (`sarab outpost garrison=9`), dispersed across sub-compounds; hydration flag flips on
  the next cycle when a player is in radius. Second outpost registered the same way. ✅

### Population (test base: natural sarab outpost, target rolled 6 ∈ [5,8])
- Ledger == live entity count at every step (9=9, 4=4, 6=6). ✅
- Kill 5 → garrison 9→4 → reinforcement +2/cycle (bunk-limited: 2 bunks) back to the
  tier target, then stops at target. ✅
- All bunks destroyed → garrison stays below target across cycles (no bunks = no
  reinforcement). ✅
- Hostile-faction soldier within 32 → reinforcement paused; enemy removed → recovers
  to target next cycle. ✅
- Inter-base roaming: two sarab outposts 210 blocks apart, night forced → exactly one
  **pair** (doctrine size 2) with `warfront_roaming:1b` spawned; none spawned during
  day (night gate). ✅
- TPS with hydrated bases + fake players: 2.9 ms avg / P95 4.3 ms (target 50). ✅
- `/warfront order sarab assault …` → General selects `infantry_assault`, aborts
  cleanly on unloaded chunks — pipeline unregressed. ✅

### Disposition ledger (the Emperor's scenario, via `/damage … by <fakeplayer>`)
- Baseline all-neutral → kill 3 sarab: disposition −45 (hostile), 3 killed_soldier
  events; +1 non-lethal hit: attacked_soldier recorded, standing −40 (hostile label),
  disposition −60.9 → **vengeful**. ✅
- Found+fixed: the killing blow doesn't fire AFTER_DAMAGE, so one-shot kills skipped
  the standing penalty — standing hit now also applied in `die()`. ✅
- Decay: `/time add 72000` (3 game days = violence half-life): −60.9 → −35.2 (ledger
  sum exactly halved); +6 more days → −16.2 (cold). Ledger runs on the overworld day
  clock, so sleeping//time genuinely age memories; decay clamped against backward
  /time set. ✅
- Combat aid: Ranger kills 4 aegis in view of sarab soldiers → sarab +40 (friendly,
  4× aided_in_combat). ✅
- **Relations echo**: the same aid leaked −14 to Vostok (cold) — a faction Ranger has
  never met. ✅
- **Betrayal**: friendly-band Ranger kills one sarab → 40.0 → 9.9 (−30 = killed × 2.0
  multiplier), server log: `Betrayal: … was friendly with sarab; killed_soldier weight
  x2.0`. ✅

- Roaming despawn / entity leak: at battery cadence (20 s @ 100%) roamers accumulated
  to 19; after their chunks sat unloaded past the (shortened) despawn deadline and were
  reloaded, the sweep discarded them — count fell to 4, all of them fresh spawns from
  the still-running fast cadence. No leak; production cadence (240 s @ 50%, cap-gated)
  keeps steady-state far lower. ✅

### Corpus validator (#7)
- `tools/validate-dialogue.js`: **PASS — 1,401 player options** (hard gate ≥1000, plan
  target ≥1,200), **3,140 response lines** (target ≥2,400), 147 response classes, 12
  categories. No duplicate/near-duplicate texts, all conditions/effects vocabulary
  valid, every option resolves ≥2 lines per applicable faction across all reachable
  bands, coverage matrix has **zero empty faction×category cells** (printed in the
  validator output; every cell also covers all three band groups).

### Work orders (#13) & matrix consequence (#10) — headless via /warfront contract
- Fresh player: offer → `vostok_elim_sarab` (eliminate 3 sarab) → accept → 3 attributed
  kills tick progress 0/3→3/3 → turn-in: Vostok standing +8, `contract_completed`
  (+ combat-aid credit from vostok witnesses → warm) — and **Sarab took the
  contract_target hit + standing −124 without the player ever talking to them**;
  Aegis went cold (−10.5) purely from the relations echo of helping Vostok. ✅
- Abandon: offer → accept → abandon: contract cleared, standing −5, `contract_failed`
  in the ledger. ✅
- Quartermaster price scaling is driven by the same standing/disposition inputs
  verified above (multipliers in `warfront_dialogue/quartermaster/*.json`).

### Dialogue UI + screenshots (client gametest; archived in `screenshots/v0.2.0/`)
- `WarfrontDialogueTest` (real input driving the actual screen):
  - Neutral open: "Viktor Volkov / Soldier of Vostok / Standing: neutral (0) —
    Disposition: Neutral", greeting *"Civilian. Keep clear of the wire."*, 4 options
    with category spread + exit, More…/Leave. ✅
  - After ledger-injected killed_soldier×3, the SAME soldier reopens at
    "Disposition: Hostile" with *"No sudden moves. The quota has room for one more."*
    — the reversible-bias flavor in-camera. ✅ (Options-tree bias further sharpened
    post-shot: band-matched options now score ×2.5 relevance.)
  - More… reroll + quartermaster tree shots captured. ✅
- `WarfrontRenderTest`: soldier lineup (all 3 factions × soldier/officer, ghost-limb
  fix confirmed in-camera — settles the open Phase 1 item) and **all nine tier×faction
  bases** placed and photographed aerially: Vostok HQ (crenellated perimeter, banner
  centerpiece, barracks rows, heli-pad "H", red-rethemed vanilla feature tent), Aegis
  HQ (clean stone/andesite, floodlights, blue banners), Sarab HQ (mud-brick warren,
  targets, garrison visible) — retheme unmistakable across factions. ✅
- Zero freehand major buildings: every enclosed structure in the shots traces to
  `structures/SOURCES.md` (spot-audit: barracks/armory/command/QM/mess/towers/bunker
  pieces = imported RS NBTs; tents/targets at gates = vanilla references; walls,
  gates, paths, pads, flagpoles = declared connective tissue). ✅

### Option repeat suppression (#8)
- 10 consecutive scripted conversations (fake player): every conversation showed 4
  options with category spread + an exit; **zero repeats within the 30-id history
  window**; ids recurred only once the window rolled past them (conversations 9–10
  reusing conversation-1 ids after 32 fresh ones). Shown-id lists logged in
  `run/devserver.log` (`Dialogue options for Runner: …`). ✅

## v0.2.1 — post-ship diagnosis, depth ruling, and Stage 6 structure battery re-run (2026-08-15)

### Why a re-run: the "broken jigsaw" that wasn't
- Field report: bases generating as "a single tiny walled square with a barrel, slabs,
  and a banner." Diagnosed in order (audit → logs → placement tests): the imported
  NBTs, SOURCES.md, pools, and the built 0.2.0 jar were all intact — the client was
  still running **warfront-0.1.0.jar**. The v0.2.0 release went live at 19:45, the
  manifest bump landed 19:46, the game launched 19:52 with the old jar: nobody ran the
  installer sync. v0.1.0 bases ARE single-plate structures; the report described them
  accurately. Zero structure errors in any log.
- Countermeasure: `mod-installer/tools/postship-check.sh` (sync + convergence dry-run
  + independent sha512 diff of every extra_mods jar; loud table + nonzero exit on any
  divergence). Ship doctrine updated: **ship is done when postship-check passes.**

### Depth ruling (Emperor, 2026-08-15)
- Shipped jigsaw sizes were 2–5 vs the RESEARCH.md plan of 4/6/7. Side-by-side
  `/place jigsaw` pairs (aegis HQ 4v7, aegis outpost 2v4; same start plate pinned;
  fresh flat gametest world) were **visually identical** — aegis plates carry only
  leaf feature sockets, so nothing past depth 1 is reachable. Evidence page:
  claude.ai/code/artifact/b42aca59-8dce-456a-9a70-3c97e0d9e015
- Ruling: raise to 4/6/7 anyway — depth is doctrine-relevant for vostok (trench
  reach) and sarab (dispersal); aegis compactness is correct per doctrine. Shipped as
  v0.2.1 (JSON-only), released, manifest bumped, postship-check gate: **PASS**.
- Placement-test findings worth keeping: `/place jigsaw <pool> <target>` needs the
  plates' actual anchor jigsaw name `warfront:socket` (not vanilla's default
  `minecraft:empty`); `/place jigsaw` ignores max_distance_from_center (hard 128).

### Stage 6 structure battery — fresh worlds, honest re-run
Setup: dev server on a brand-new `warfront-test` world (fake player Watcher held it
un-paused); client gametest fresh flat world for all camera work.

- **/locate battery**: all 9 tier×faction structures locate in the fresh server world
  (outposts/FBs 237–644 blocks from spawn; HQs 3,032–3,449 at the 112-chunk spacing). ✅
- **Natural generation**: walking Watcher to the nearest sarab outpost generated and
  registered it (`Registered base sarab_outpost@39,-284`), seeded a garrison of 8. ✅
- **Aerials, all nine** (screenshots/v0.2.1/): retheme unmistakable per faction —
  vostok stone/crenellation/red, aegis andesite/blue, sarab mud-brick/green. Depth
  raise visibly pays off where it should: vostok trench arms now chain far beyond the
  walls at all three tiers; sarab outpost/FB/HQ generate 2–4 detached sub-camp pads on
  paths; aegis stays compact (as ruled). ✅
- **Anatomy** (generator composition + furnishing audit + aerials): towers, armory,
  command post, quartermaster (+NPC seed), barracks, mess per tier plan; every plate
  furnished — faction-colored banners, chests/barrels, workstations, lecterns in
  command posts, barrel-rich HQ storage; bunks present (warfront:bunk in plate NBTs). ✅
- **Provenance spot-audit, 10 pieces** (5 vostok / 3 aegis / 2 sarab, houses + towers
  + bunker rooms): every rethemed NBT is dimension-identical to its imported RS
  original, palette rethemed, and listed in structures/SOURCES.md. 10/10. ✅
- **Eye-level read, aegis HQ** (per the ruling; unrotated template, spectator camera):
  - Gate approach, courtyard, and command post read as a genuine installation:
    manned gate under a bannered gatehouse, lantern-lit crenellations, layered
    watchtowers, banner dais + flagpoles, paved paths, supply barrel clusters,
    furnished command post (windows, planters, balcony; interior windows/torch/lectern). ✅
  - **Honest caveats, no redesign done (per ruling, reporting back instead)**:
    (1) the central bunker core and the gray-concrete annexes read as flat dark boxes
    at close range — they are rethemed stronghold/fortress *interior* pieces whose
    exteriors were never designed to be seen; (2) aegis barracks_1 (RS mountains
    medium_house_1) is a hillside house that looks odd out of terrain context —
    fine inside compounds, strange as the triptych centerpiece.
- **Test-harness fixes this pass** (cameras, not content): render distance must be set
  before world creation (integrated server snapshots it at connect); elevated cameras
  must be spectator — creative players fall during chunk-render waits, which had
  silently degraded some earlier "aerials." `/place template` keeps raw jigsaw blocks
  visible (test-only artifact; natural gen replaces them with their final_state).
- Soldier lineup: all 3 factions × soldier/officer posed correctly — ghost-limb fix
  still good. Dialogue screens re-captured incidentally (battery runs both tests). ✅

## v0.2.2 — independent reliability audit (2026-08-16)

Scope: Warfront only. Dedicated-server testing used a completely new survival world
(`warfront-audit-20260816`) and Carpet fake player `Auditor`; the client suite used
its own fresh flat worlds.

### Soldier spawning: reproduced, fixed, re-tested

- Reproduced the field symptom: a discovered base could initially show zero loaded
  soldiers and remain empty until the 15-second base cycle. The structures themselves
  are sound: every faction/tier main NBT contains seed soldiers (3–10 before attached
  jigsaw pieces), and a fresh Vostok HQ presented 14 visible seeds immediately. ✅
- Found the underlying HQ race: hydration could run while later jigsaw/template seed
  entities were still loading. A fresh Vostok HQ reached **48** despite a configured
  maximum of 40. Hydration now waits 200 ticks for all seeds to settle and enforces
  the remaining global budget per individual spawn. ✅
- Fresh-world results after the fix: Vostok HQ **14 visible seeds → 39 stored / 39
  loaded / target 39 / hydrated true**; Aegis HQ **4 visible seeds → 18 / 18 /
  target 18 / hydrated true**. No delayed empty base and no overfill. ✅
- The global one-player cap reached exactly **64** after a forced patrol; another
  patrol spawned 0. A cap-constrained tactical assault used exactly the 3 remaining
  slots, including its delayed second wave, and finished at exactly 64. ✅
- Added player-position structure discovery as a seedless fallback. Seed adoption
  retries for 200 ticks so entity/structure-reference load ordering is not a single
  point of failure. Existing overfilled bases are trimmed only when their full target
  is visibly loaded; virtual ledgers are never reduced merely because a chunk has not
  loaded yet. ✅

### Additional defects found and closed

- Rival soldiers inside a base were incorrectly assigned to that base's garrison.
  Reproduced with a Vostok soldier in an Aegis HQ; after the guard, its
  `warfront_base` remained empty and the Aegis ledger did not change. Existing saved
  cross-faction memberships self-repair when the soldier loads. ✅
- Invalid `warfront_squad` UUID data now sanitizes to empty instead of throwing.
  Entity creation/add failures are checked in bases, patrols, travel squads, and
  assault waves; ledgers increment only after a successful world add. ✅
- Unreachable stations release after 200 ticks and are temporarily blacklisted;
  scheduled assault tasks are cleared between server lifecycles and isolated from a
  server-tick crash. Deprecated chunk APIs were removed under `-Xlint:deprecation`. ✅

### Runtime and artifact battery

- Soldier NBT survived save/restart with faction `vostok`, rank `officer`, fixed UUID
  squad, home position, dyed armor, and iron weapon intact. ✅
- Isolated Vostok/Aegis pair engaged correctly: one died and the survivor had 10.14
  health. Tech level 2 refreshed 45 loaded Vostok soldiers and a new spawn carried
  the expected iron loadout. An unloaded-target order aborted cleanly. ✅
- `tools/validate-dialogue.js`: PASS — 5,443 options, 21,758 response lines, 3,250
  classes. All 100 deep subjects contain ten sequential layers with one friendly,
  neutral, and threatening choice per layer. Every resource JSON parses with `jq`. ✅
- `./gradlew runGametest`: PASS — client boot, soldier lineup, all nine base camera
  placements and depth comparison; focused dialogue test PASS covers exact-position
  hold, visible responses, fresh rerolls, tone coverage, branch traversal through
  depth five, dynamically measured wrapped option buttons, change-subject behavior,
  personality temper limits,
  hostile-band/quartermaster flows, and soldier↔hostile-mob targeting;
  no Warfront exception. (Offline profile-auth and macOS shader-driver warnings are
  external development-environment noise.) ✅
- `./gradlew clean build`: PASS on JDK 25 with deprecation lint enabled; final jar
  archive integrity and SHA-512 recorded at handoff. ✅

## Civilization update — Phase 1 gate (2026-08-16)

### Automated conservation and determinism gate

- `./gradlew clean build`: PASS on JDK 25. The five JUnit checks are build-failing:
  exact inventory equality through embodied → local → virtual → reconstituted copies;
  a miner at 75/200 work advanced by 12,125 virtual ticks to exactly 61 goods and
  0 remainder; segmented coarse ticks equal one elapsed tick; identical input replay
  produces identical state; backwards clocks fail closed. A maximum command-sized
  500-record pure-data city averaged **0.252 ms/tick** across 100 measured runs
  (after warmup), below the 1 ms gate. ✅
- Dedicated server loaded Warfront 0.2.2 with 44 mods and zero registry, saved-data,
  mixin, UUID, or Warfront exception lines after the final fix. `/warfront city
  validate` independently reported `transition goods 10->10`, `produced=61`,
  `remainder=0`, `deterministic=true`. ✅

### Live leave/return proof

- Created `finalgate` (Sarab, 20 citizens) beside fake player Watcher: inspect showed
  **20 embodied / 0 local / 0 virtual**, and 20 real citizen entities existed. ✅
- Teleported Watcher 3,000 blocks away. After chunk unload: **0 embodied / 0 local /
  20 virtual**, and the entity selector found none. Goods advanced arithmetically
  **0 → 20** during ten seconds absent. ✅
- Returned to the city: **20 embodied / 0 local / 0 virtual**. The virtual interval
  completed a second exact cycle before promotion, producing 40 total goods; that
  total remained **40 → 40** over the next reconciliation. A reconstituted trader's
  entity NBT carried `warfront:trade_bundle=2`. ✅
- The first live attempt found and closed two real transition defects: city creation
  no longer trusts an unsettled destination heightmap after teleport, and promotion
  overwrites a stale chunk-restored entity from the authoritative actor record. The
  latter was the observed 28→8 conservation loss; the final rerun has no loss. ✅

Phase 1 stopped here for review. Phase 2 continued only after approval.

## Civilization update — Phase 2 gate (2026-08-16)

### Emergence, determinism, and conservation

- Equal-start 250-citizen run completed 10,000 economic ticks: Gini **0.0000 →
  0.7518**, liquidity-constrained poor share **0.0% → 52.0%**, and top-5 wealth share
  **4.8% → 47.6%**. Final wealth quantiles were 66 / 98 / 100 / 1,413 / 2,831 /
  42,323 (min/Q1/median/Q3/P90/max). A poor class, surviving middle/upper strata, and
  long rich tail emerged without assigned classes or unequal starting money. ✅
- Money remained exactly 250,000. Every model tick asserts money conservation and
  the goods identity `initial + regeneration - consumption - shock loss = current`.
  Embodied inventory additions/removals are included in the same ledger. Identical
  seed + ticks produced identical distribution, money, and net-worth arrays; snapshot
  encode/decode round-tripped them exactly. ✅
- Vein depletion + blight changed at least ten wealth ranks for 68/250 actors versus
  an unshocked twin (30 rose, 38 fell). A separate matched-region test produced ore
  prices 108 disrupted versus 94 stable, proving local scarcity changes prices. ✅
- A 500-citizen pure-data economy averaged **0.013 ms/tick** over 2,000 measured ticks
  after warmup, below the 1 ms gate. Full tables and model notes:
  `ECONOMY_VALIDATION.md`. ✅

### Dedicated-server persistence gate

- Existing 20-citizen city `finalgate` advanced from economic tick 1 to 2 with money
  fixed at 20,000, goods 54 → 84, and conservation true; measured full city-economy
  costs were 0.493 ms and 0.196 ms. ✅
- Injected vein depletion and blight at tick 2 changed Gini 0.0211 → 0.0602 and the
  wealth range 1,000–1,132 → 890–1,345 while money stayed 20,000 and conservation
  stayed true. After a graceful stop/restart, the exact tick, distribution, prices,
  money, goods, and conservation status were restored. ✅
- Five additional 500-citizen cities were kept fully Tier 3 (2,500 virtual citizens).
  Their full live economy ticks measured 0.396 / 0.409 / 0.456 / 0.498 / 0.769 ms,
  each below 1 ms and each conserved. With all test cities present, `tick query`
  reported 7.0 ms average, 12.4 ms P95, and 15.3 ms P99 over 100 samples. ✅
- The virtual snapshot fast path was transition-tested by returning to `finalgate`:
  20/20 citizens reconstituted with current economic inventory (sample: 47 wheat),
  then passed the next embodied synchronization at 20,000 money / 504 goods with
  conservation true and a 0.108 ms economy tick. Final log grep found no Warfront,
  registry, saved-data, or conservation error/exception. The server stopped cleanly
  through RCON. ✅

Phase 2 stops here for review. Derived social classes, governors, taxation, welfare,
regulation regimes, and class-driven housing/consumption remain intentionally fenced
for Phase 3.

## Dialogue overhaul gate — 0.2.3 (2026-08-17)

- Dialogue generation produced 2,743 player options and 6,458 response lines. The
  validator passed schema and branch integrity, per-faction negative/neutral/positive
  disclosure pools, length bounds, and player-facing mechanism-leak checks. The three
  remaining warnings are pre-existing thin hostile pools in field chatter. ✅
- Deep dialogue now uses ten hand-authored, concrete subjects. Standing silently
  changes what an NPC will disclose, while faction identity appears through distinct
  incidents and field language. Immediate context, including recent monster attacks
  and fights, receives priority over long-form lore. ✅
- `./gradlew clean build`: PASS on JDK 25. ✅
- `./gradlew runGametest`: PASS in 5m44s. The dialogue test opened the prototype
  barrier branch for Vostok, Aegis, and Sarab; advanced to the faction-history reply;
  verified standing 4, distinct expected language, measured non-overlapping header
  rows, padded wrapping, complete buttons, and a no-scroll reference exchange at
  independent GUI scales 1 and 2. ✅
- Manually inspected all six archived frames in `screenshots/v0.2.3/`: no header
  collisions, divider strike-through, clipped reply, or clipped choice text. Both the
  player question and the NPC answer remain visible at the tighter scale. ✅

## Dialogue cleanup gate — 0.2.4 (2026-08-17)

- Removed every rendered standing, disposition, personality, mood, conversation-depth,
  tone-marker, and color-legend label from both the screen and chat fallback. The
  visible header is now only name, rank/faction, and subject. ✅
- Removed generated faction preambles such as “Here is the field version,” “Recorded
  finding,” and “A careful question gets daylight.” Three tone variants now share the
  same direct factual answer instead of manufacturing 1,800 nominally unique lines
  with filler. The validator rejects those preambles if they return. Final corpus:
  2,743 player options, 4,658 response lines, and 350 response classes. ✅
- Disclosure now uses the hidden standing band as intended: hostile/wary refuses,
  neutral gives a concise fact, and friendly/trusted gives the fuller answer. The
  separate disposition meter no longer accidentally chooses disclosure depth. ✅
- `./gradlew runGametest`: PASS in 5m44s after the final wording change. Vostok,
  Aegis, and Sarab each produced a direct, faction-specific barrier exchange at GUI
  scales 1 and 2; all six frames passed no-marker, non-overlap, wrapping, complete
  button, and no-scroll assertions. Manual review of `screenshots/v0.2.4/` found the
  simplified header and final question/answer unobstructed at both scales. ✅

## Settlement gate — 0.3.0 (2026-08-18)

### Cross-faction base separation

- **Reproduced the fault before fixing it.** `tools/verify-base-spacing.js` replays
  `RandomSpreadStructurePlacement` and its `LegacyRandomSource` LCG offline, so every
  candidate placement is known exactly without running the game. Against the nine
  original structure sets it found two different factions in the **same chunk
  (0.0 blocks apart) on 4 of 4 seeds** — e.g. `aegis_base` and `sarab_outpost` both at
  (-12816, -3472). Root cause: `spacing`/`separation` constrain placements only
  *within* one structure set, and warfront had nine sets with nine independent salts,
  each blind to the others. ✅
- After collapsing all twelve structures into one `warfront:bases` set at
  `spacing 20 / separation 13`, the same tool over **8 seeds × 25,921 placements each**
  reports a worst-case closest pair of **224.0 blocks**, cross-faction included,
  against a required floor of 200. ✅
- Confirmed live, not just on paper: `/locate structure` for all twelve structure
  types on a real world gave a minimum over all 66 pairs of **244.8 blocks**, closest
  cross-faction pair 256.0 blocks (`sarab_outpost` ↔ `vostok_base`). ✅
- The verifier is deliberately conservative — biome predicates, exclusion zones and
  `frequency` are ignored, and each can only *remove* a placement, so the measured
  distance is a lower bound on what a real world produces. ✅

### Civilians, cities, emeralds

- Datapack loads clean: dev server reached `Done` with no registry loading errors
  after the structure-set collapse and the three new city structures. ✅
- Civilian seeding verified end-to-end on a live world. Walking into a naturally
  generated Sarab city produced
  `base_sarab_city_191_255 [sarab] citizens=28 soldiers=9`, and a forward base
  produced `citizens=8` — both seeded automatically from structure discovery. ✅
- **Caught and fixed a seeding bug with the same live check.** The first
  implementation scanned the full jigsaw bounding box and filled greedily, so all 28
  citizens landed in a line at `x=192` — the western edge of a 244-block-wide sprawl
  box, 124+ blocks from the city, all stuck at `local` fidelity with **zero entities
  spawned**. Reading the actual saved positions out of `civilization.dat` is what
  exposed it. Seeding now anchors on the structure's *start piece* and sorts a full
  disc scan by distance before choosing. Re-verified on a fresh city:
  `embodied=28 local=0 virtual=0`, with a live citizen entity at (-1146.9, 75, -264.9). ✅
- Emerald economy visible on a live citizen:
  `warfront_inventory = "minecraft:emerald=4;…;minecraft:wheat=15"` on a farmer —
  the capped purse, not the whole balance. `/warfront city economy` reports
  `city wealth=1120` emeralds, per-lot buy prices `food=1 ore=5 timber=4 crafts=8`,
  and `conserved=true` with the player-trade ledger at `in=0 out=0`. ✅
- Old `WFE2` economy snapshots still decode — the external-trade ledger was added as
  `WFE3` with the previous version kept readable, so existing worlds do not reset. ✅

### Render battery (rule 9)

- `./gradlew runGametest`: PASS. Twelve new aerial frames (four quadrants × three
  faction cities) plus `aegis_city_plaza`, `aegis_city_avenue` and `aegis_city_farm`.
- **Frames read, not just produced.** The plaza frame shows the well, awninged market
  stalls, Aegis banners and street lamps; the farm frame shows fenced farmland with a
  water channel and wheat at mixed growth stages. An earlier pass had two cameras
  aimed at empty ground because Minecraft yaw 0 is *south*, not north — the frames
  were re-shot rather than accepted, which is the whole point of looking at them. ✅
- `node tools/gen-base-plans.js` reports **zero stamp collisions** across every plate,
  and now warns loudly instead of silently dropping a building that will not fit —
  which is how the Sarab city plate was found to be one command post too narrow. ✅

## Dialogue rewrite gate — 0.3.1 (2026-08-18)

The complaint was that the dialogue read as random and often was not English. It was
both, and the cause was structural: the corpus reached its size by slotting a topic
noun into shared sentence frames.

### What the measurement found

- Three player-question frames alone produced **300 lines** — *"Have the realities of
  {X} changed much since the war began?"*, *"If I had to deal with {X} tomorrow…"* and
  *"I've heard soldiers argue about {X}…"*, each repeated 100 times with a different
  noun. 570 of 2,743 options came from just 30 reused frames. ✅
- 18 response frames produced 180 near-identical lines. ✅
- The seams showed as ungrammatical English, exactly as a reader would feel it:
  *"On the line, ammunition reserves **is** measured…"*, *"We learn ammunition reserves
  by shortage and remember **it** by scars"*, *"The impatient see only mountain
  **passes**; the patient see the hidden roads curling around **it**"* (11 copies),
  *"Our history with **the disarming rival militias** is not for you"*, and
  *"What does the contested river bridge actually **do**?"* ✅
- 27 lines used firearms vocabulary in a setting VOICE.md defines as bows and blades. ✅

### What changed

- `dialogue/VOICE.md` gained a craft addendum assigning each faction a rhetorical
  grammar rather than just a word list: Vostok **parataxis and litotes** (Old English
  understatement through the plain trench register), Aegis **anaphora, asyndeton and
  meiosis** (the dispatch and the institutional voice), Sarab **antithesis, metaphor
  and apophasis** (the balanced couplet of wisdom literature). The cover-the-name test
  is now carried by sentence construction, not vocabulary. ✅
- `gen-field-chatter.js` rewritten: **nothing is substituted.** 60 subjects, each with
  its own authored player questions and its own authored reply per faction. ✅
- `gen-deep-dialogue.js`: the 300 slot-filled refusals replaced by authored refusals
  per topic per faction, and depth-0 openers authored per topic. ✅

### Enforced, so it cannot grow back

`tools/validate-dialogue.js` gained prose gates: firearms vocabulary, and a
**frame-reuse detector** (first-four/last-four-word signature) that fails the build
when any frame repeats past a small threshold. Branched follow-ups are exempt, and the
screenshot is the justification: the topic header is on screen above them, so *"Tell
me about the latest attempt."* under **Contested River Bridge** is context, not a
template. A subject-verb heuristic was written, measured at **49 false positives and
zero true ones**, and deleted rather than shipped — a gate with no precision only
teaches people to ignore warnings. ✅

### Result

| | before | after |
|---|---|---|
| firearms violations | 27 | **0** |
| plural noun answered by singular *it* | 11 | **0** |
| most-reused player-option frame | 100× | **10×** (branched, topic shown) |
| most-reused response frame | 10× | **4×** |
| player options | 2,743 | 1,863 |
| response lines | 4,659 | 4,418 |

The count gates were lowered from 2700/4600 to 1700/4200 with the reason recorded in
the validator: those floors were only reachable by templating. Breadth is bought with
writing now, or not at all.

`node tools/validate-dialogue.js`: **PASS**, zero warnings. `./gradlew runGametest`:
PASS; dialogue frames read in-camera — *"What state is the river bridge actually in?"*
now asks something a bridge can answer. ✅

## Post-ship audit — 0.3.2 (2026-08-18)

An audit of everything shipped in 0.3.0/0.3.1 found four real defects. All were
introduced by those releases; all are fixed here, with the measurement that found them.

### 1. Player trades were silently undone (correctness)

`EconomyManager.trade` wrote only to the `EconomyModel`. But while a citizen is
**embodied** the entity is authoritative: `reconcile` copies entity→record every 20
ticks, and the economic tick copies record→model. So the model side of a trade was
overwritten within one economic tick — the player kept the goods they bought *and the
citizen kept them too*, and a sale's goods never arrived. Fixed by mutating the
entity's inventory (`consume`/`store`) as part of the trade, which is the only write
that survives the ladder. ✅

*Not empirically driven:* the right-click path could not be exercised headlessly —
carpet's `player <name> use` accepts the command but does not fire entity interaction,
and the aim was verified correct. The fix rests on source-level data-flow analysis of
`reconcile` → `snapshot` → `syncPhysicalGoodsIntoModel`, each hop read directly. Worth
a client gametest later.

### 2. Structure pieces could still overlap despite the 224-block floor (worldgen)

The floor separates structure **origins**; it says nothing about how far pieces sprawl
from them. Measured from the live ledger, a generated city's bounding box was
**245×46×245 — a half-extent of 122**, so two adjacent cities needed 245 blocks and
only had 224: **up to 21 blocks of overlap.** Every other structure measured ≤84.
The city was the outlier, so the city was corrected rather than the spacing: jigsaw
`size` 5→3 and `max_distance_from_center` 116→96. Re-measured on freshly generated
terrain: 199×201, 155×201, 151×197 — worst half-extent **100.5**, so two adjacent need
201 against a 224 floor. **No overlap possible.** ✅

### 3. Anchor scanning grew with jigsaw sprawl (performance)

`anchorPoints` ran two *unbounded* `scanBounds` passes over the whole structure
bounding box, and it runs on **every base tick until a base finishes hydrating** — so a
base that cannot finish (spawn budget exhausted, no standable anchor) rescanned
**5.5M block states every 15 seconds** for a 245-wide city. Now clipped to a 64-block
box around the centre, which makes the cost independent of sprawl. The bunk scan in
`reinforce` deliberately still walks the full bounds — destroying a distant barracks
must still count — but it runs at most once per `reinforce_minutes`. ✅

### 4. NPE risk trading with an orphaned citizen (robustness)

`sellToCitizen` dereferenced the city record before checking it. A citizen entity whose
city record is missing would have crashed the interaction. Guarded. ✅

### Also corrected

- `verify-base-spacing.js` passed the placement salt by assigning a property onto the
  function object — action at a distance that silently reuses the previous salt if a
  caller forgets. Now an explicit parameter. ✅
- The city aerial frames shot from 120 blocks could not contain a ~200-block town, so
  the subject sat in a corner of its own evidence photo. Raised to 200. Re-read: the
  road cross, plaza, farmland and district sprawl are now all legible in frame. ✅

### Known and accepted, not fixed

- **Citizens carry a lootable purse and do not repopulate.** Killing a city's 28
  civilians yields their carried emeralds (≤4 each, 12 for traders) and depopulates it
  permanently. Bounded and deliberate — the purse is capped precisely so this is not a
  farm — but it is a real incentive to slaughter civilians, and worth a design ruling.
- **`EconomyModel` is cached per city and rebuilt if the population changed** across a
  restart, which resets that city's wealth distribution. Pre-existing behaviour,
  reachable more often now that citizens are killable.

Regression suite after the fixes: `verify-base-spacing.js` PASS (224-block floor),
`validate-dialogue.js` PASS with zero warnings, `gen-base-plans.js` zero collisions and
zero skipped buildings, `./gradlew runGametest` PASS with 75 frames.

## Settlement growth and the open economy — 0.4.0 (2026-08-18)

### What was built

Population growth bounded by housing and food; expeditions that mine, trade, forage and
raid; and three settlement tiers with a rare, genuinely large metropolis.

The economy model had to stop being fixed-size and closed. Per-actor arrays now grow, a
slot is `serial - 1` for the life of the city (a death leaves an inert slot rather than
re-indexing everyone), and a **treasury** holds money belonging to nobody — the estates
of the dead, and what expeditions bring home.

### Verified live

- **Three tiers generate.** All six new structures resolve via `/locate`. A metropolis
  seeded **300 citizens** with a garrison filling toward its 70–95 target:
  `base_vostok_metropolis_… citizens=300 soldiers=23`. ✅
- **Footprint stays inside the separation floor.** The metropolis measured **228×221
  (half-extent 114)** against a **272-block** floor — two adjacent need 228, leaving 44
  blocks of headroom. Separation was raised 16 (from 13) precisely because the largest
  settlement sets the spacing for everything. ✅
- **Growth works, bounded by housing:** a town went `10 → 11 → 13 → 15 → 17 → 19`
  against `housing=32`, while three of its citizens were `away=3` on expedition. ✅
- **Expeditions dispatch and resolve** across every settlement — mine, trade, forage and
  raid — with parties drawn from the city's own people. ✅
- **Raids move wealth between cities.**
  `base_vostok_city_… sacked testopolis for 70 emeralds and 84 food (lost 2)`.
  Testopolis' money fell 25,000 → 23,700, its Gini rose 0.04 → 0.12 and its poorest
  citizen dropped to 84 — the raiders took it off people, richest first. Both cities
  still reported `conserved=true`. ✅
- **External wealth reaches citizens.** A mining city's actor money climbed
  `28,000 → 28,028 → 28,150 → 28,206` as `carried in` rose to 31 emeralds. ✅
- **Performance holds at scale:** a 300-citizen metropolis costs 0.78ms per economic
  tick and 0.055ms per reconcile. ✅

### Three bugs this found and fixed before shipping

1. **Conservation would have broken on reload.** `initialMoney` was derived from the
   population at construction, so a decoded snapshot of a city that had *grown*
   recomputed the baseline from the larger population and the audit would have thrown.
   The baseline is persisted now. Caught by `tools/econ-selftest.sh`, which compiles
   `EconomyModel` standalone (it has no Minecraft imports) and runs 30 invariants in a
   second rather than a server boot.
2. **Nothing an expedition earned ever reached anybody.** Deposits landed in the
   treasury and stayed there, because the payout reserve was a full `startingWealth`
   (1000) and a typical haul is 250. The economy was open on paper and unchanged in
   practice — visible only because a mining city's `money=` never moved. Reserve is now
   one liquidity floor.
3. **The growth clock never fired.** It demanded `now % interval == 0`, but `maybeGrow`
   is only reached on an economic tick every 200 ticks, so the clock had to land on one
   of three reachable residues out of 600 — a town sat at 10 citizens with 32 housing
   and full granaries for six minutes without a single birth. It now fires on the first
   economic tick inside each interval window.

### Regression suite

`econ-selftest.sh` ALL PASS (30 invariants) · `verify-base-spacing.js` PASS (272-block
floor) · `validate-dialogue.js` PASS, zero warnings · `gen-base-plans.js` zero
collisions and zero skipped buildings · `./gradlew runGametest` PASS, with the town,
city and metropolis plates added to the aerial battery and read.

### Note

The port collision during testing was **not** resolved by killing the process holding
25565: no pidfile existed, so by rule 1 it was not mine to kill. The dev server was
moved to a spare port for that session and returned to 25565 in 0.4.1, which also
hardened `tools/devserver.sh` so the ambiguity cannot recur.

## Dev-server launcher hardening — 0.4.1 (2026-08-18)

Port 25565 was reclaimed, and `tools/devserver.sh` rewritten so an orphaned server can
never again be ambiguous.

### The actual root cause

The old script *did* write a pidfile — but it recorded `$!`, which is the **gradlew
wrapper**, not the JVM that binds the port. Measured on a live launch: launcher **50145**,
listener **50160**. The recorded PID could therefore never match the process holding the
port, so a leftover server was unprovable either way. The listening PID is now discovered
via `lsof` on our own port *after our own log reports ready* (so the binder is provably
ours) and recorded separately in `run/server.pid`.

### Behaviour, each branch exercised

| Situation | Result |
|---|---|
| Port free | starts; `run/server.pid` written and matching `lsof` |
| Pidfile present, PID dead | `cleared a stale …/server.pid (process was gone)`, then starts |
| Port held, pidfile **matches** holder | `reclaiming it` → kills that exact PID → starts, records the new listener |
| Port held, **no** pidfile | **refuses**, exit 1, reports PID / elapsed / command line |
| Port held, pidfile records a live but **different** PID | **refuses**, exit 1, names both PIDs |

Verified end to end: launch → `lsof :25565 = 51746` and `run/server.pid = 51746` (match)
→ clean stop → port free, both pidfiles removed. ✅

`status` was added to report what holds the port and whether it is ours.

### A bug this verification caught

The first cut of `stop` waited for the **port** to free before its kill fallback. The
listening socket closes at the very start of shutdown, while `Saving worlds` is still
running — so the fallback landed a SIGTERM in the middle of the world save. The log
proved it: `Saving worlds` with no following `All dimensions are saved`, and gradle
reporting `non-zero exit value 143`. `stop` now waits on the **process** to exit (90s)
and only then falls back. Re-verified: `Saving worlds` → `All dimensions are saved`, no
SIGTERM, and the fallback never fires on a healthy shutdown. ✅

### Rule 1 compliance

Nothing is ever selected by pattern. `lsof` is used only to *identify* a holder for
comparison and reporting, never to choose a kill target: the sole PID the script will
signal to reclaim a port is one it recorded itself and that still matches the listener.
The launcher fallback additionally checks the command line still looks like gradle/java
before signalling, so a reused PID is left alone.

## Retrospective audit — 0.4.2 through 0.4.9 (2026-08-20)

Nine releases went out between 2026-08-18 20:55 and 2026-08-20 13:42 with **no entry in
this log**. This section is written afterwards, during a folder-wide audit. It says what
was actually confirmed, and — more usefully — what the confirmation found.

The span, by release: 0.4.2 grounded citizens and profession markets · 0.4.3–0.4.5 citizens
sent to productive worksites, profession skins bound in avatar state, trade restricted to
tangible production · 0.4.6 guarded working cities · 0.4.7–0.4.8 monumental castles and
Dracula (0.4.7 was rolled back the same day for registry bounds) · 0.4.9 imported castle
builds.

### Two defects, both invisible to every check that was being run

**1. Every citizen rendered as the missing texture.** From 0.4.3 (`Bind citizen profession
skins in avatar state`) to 0.4.9 inclusive, all five citizen professions drew as the
magenta-and-black checkerboard on every client. Fixed in 0.4.10, below.

**2. Castles could generate on top of faction bases.** 0.4.7 gave the castles their own
structure sets. Minecraft enforces spacing and separation only *within* a set, so nothing
kept a 501-block castle away from a base. Measured across 8 seeds: **5 of 8 placed a castle
and a base in the same chunk — 0.0 blocks apart** — and the remaining 3 at 16 blocks.
`tools/verify-base-spacing.js` had reported a 272-block floor at 0.3.0 and was failing
8/8 by 0.4.9. Fixed in 0.4.10, below.

Both shipped through a green `postship-check`, a green load gate, and a clean server boot,
because neither is the kind of thing those check. The first needed a frame; the second
needed a tool that was already in the repo and was not being run.

### What the audit did confirm about 0.4.9

- The jar on the Empire server hashes to `3edd08b502498bcce1e501e9…`, exactly what
  `mods.json` declares, verified by pulling the file off the server. ✅
- `warfront 0.4.9` initialised in the 13:43 boot — 104 mods, zero mixin failures, zero
  errors. ✅
- Rebuilt from committed source at `v0.4.9`: byte-identical to the shipped jar. ✅
- `tools/econ-selftest.sh` **ALL PASS** (30 invariants, including that a re-encode is
  byte-identical and conservation still closes after a snapshot round trip). ✅
- `tools/validate-dialogue.js` **PASS** — 1,863 player options, 4,418 response lines,
  310 classes, zero warnings. ✅
- `tools/verify-grand-castles.js` **OK** — all four castles at 501×501, garrisons, rich
  loot, working towns and economy intact. ✅

## 0.4.10 — the two defects above (2026-08-20)

### Citizen skins: what was actually wrong

`CitizenRenderer` built each profession's `PlayerSkin` from the *full texture path*:

```java
new ClientAsset.ResourceTexture(warfront:textures/entity/citizen/miner.png)
```

`ClientAsset.ResourceTexture` takes an **asset id** and derives the file itself as
`textures/<path>.png`. The file it therefore asked for was
`warfront:textures/textures/entity/citizen/miner.png.png`, which does not exist.

The renderer's own `getTextureLocation` override returned the correct path all along — but
26.2's avatar pipeline prefers the render state's `skin`, so the correct answer sitting
three lines above never got a look in. `SoldierRenderer` was unaffected for exactly one
reason: it never sets `state.skin`, which is why soldiers looked perfect in the same frame
where citizens did not.

**Fixed** by passing the asset id. Confirmed two ways:

- `CITIZEN_SKIN_AUDIT 5 professions resolve: [MINER=warfront:textures/entity/citizen/miner.png,
  FARMER=…, BUILDER=…, TRADER=…, LABORER=…]` — note the single `textures/`. ✅
- **Frame read**: `0002_citizen_profession_lineup` shows five citizens in five distinct
  profession skins — trader with barrel, farmer in apron, builder, laborer with axe, miner
  with helmet and pickaxe. Before the fix the same frame was five magenta checkerboards. ✅

The battery now asserts this itself rather than leaving it to a human to notice: every
profession's skin must resolve to a texture the resource manager actually has.

### Castles: one structure set, because spacing only works inside one

The four castles moved into `bases.json` as weighted entries and the two castle-only sets
are gone. `tools/verify-base-spacing.js`:

| | 0.4.9 | 0.4.10 |
|---|---|---|
| worst closest pair, 8 seeds | **0.0 blocks** | **272.0 blocks** |
| seeds below the 200-block floor | 8 / 8 | 0 / 8 |

272.0 is the same floor this tool measured at 0.3.0, before the castles existed — the merge
restores the original invariant exactly rather than approximating it.

The weights were solved, not chosen, so castle frequency is unchanged: grand castles at
50 each and Dracula at 3 against a total of 6,613 reproduce the old spacing-160 and
spacing-640×0.35 rates to **403/region vs 400** and **8.06 vs 8.75**.
`verify-grand-castles.js` now pins that arithmetic, so a later weight edit cannot quietly
make castles common, and it fails if either retired set file reappears.

### Known residual — castle edges can still clip a large base

Centre-to-centre distance is not the whole question once one member of the set is 501
blocks across. At the 272-block minimum, a castle (half-extent 250) beside a metropolis
(half-extent 62) leaves **−41 blocks**: they can overlap by up to 41 blocks when they land
at the minimum. `verify-base-spacing.js` now measures and prints this rather than leaving
it implicit.

The same arithmetic applies to the set's village exclusion. A structure set carries one
`exclusion_zone`, so the castles now inherit the bases' `minecraft:villages` at 8 chunks
rather than the 16 they had of their own. Neither number was ever enough for a 501-block
plate — 16 chunks is 256 blocks against a half-extent of 250 — so this is the same root
cause rather than a new one: a monumental structure sharing placement numbers that were
sized for 76-to-124-block plates.

Buying either back means raising `separation` (and the exclusion radius), which costs base
density near villages and everywhere else — a live trade-off, deliberately not made
unilaterally. Recorded here so it is a decision rather than a surprise.

### Regression suite, on 0.4.10

`econ-selftest.sh` **ALL PASS** · `validate-dialogue.js` **PASS**, zero warnings ·
`verify-base-spacing.js` **PASS** at 272.0 blocks · `verify-grand-castles.js` **OK** with
castle rates pinned · `runGametest` **BUILD SUCCESSFUL**, 101 frames · `runCastlerender`
**BUILD SUCCESSFUL**, castle aerial read.

## 0.4.11 — cross-set clearance without vanilla's cap (2026-08-20)

0.4.10 stopped castles landing on bases by merging them into the bases set. That worked,
and it was the wrong shape: it made every castle obey placement numbers sized for
76-to-124-block plates, when the point of the castle work is that a supplied build may be
larger than the last one.

### What the supplied builds actually are

Measured with the new `tools/measure-build.js`, against the maps as supplied:

| | supplied build | the importer's 341×341 window captures |
|---|---|---|
| Celestial Castle (Aegis) | castle core ~272×288 | **97.6%** |
| Mug Castle (Sarab) | spread cliff-city | **87.5%** |
| Cinderella Armored (Vostok) | 1.77M built blocks, core ~1248×528 | **36.5%** |

Two things follow. The 501 footprint is **castle plus four working towns plus roads**, not
castle — the Aegis castle geometry is ~272 across. And Vostok is imported from roughly a
third of the build it was given. Neither is visible from anything the repo checked: the jar
hashes, the structure loads, the castle renders, and `verify-grand-castles.js` confirms
501×501 because 501×501 is what the importer was told to write.

### The ceiling that decided the design

Splitting castles into their own set needs `bases` to keep clear of them, and vanilla's only
tool for that is `exclusion_zone`. Its `chunk_count` is codec-bounded to **[1:16]** — 256
blocks — while a 501-block castle beside a 124-block metropolis needs **313**. The game
refuses the datapack rather than degrading:

```
Errors in element warfront:bases:
Caused by: java.lang.IllegalStateException: Value 20 outside of range [1:16]
```

That is the same registry-bounds class of failure that got 0.4.7 rolled back, caught this
time before shipping because the castle render config boots a world and loads every datapack.

### warfront:base_spread

So `bases` uses a placement type of Warfront's own: `RandomSpreadStructurePlacement` plus an
uncapped `avoid_set`/`avoid_chunks`, applied at the same override point vanilla applies its
exclusion zone and delegating to vanilla's own `hasStructureChunkInRange`. Same call, same
semantics, different bound — and the clearance is now free to grow with the castles.

It also keeps the vanilla exclusion zone, so bases still dodge villages at 8 chunks *and*
hold 20 chunks off castles, at full spacing-24 density. The trade a datapack-only design
would have forced is simply not paid.

All four castles share one set so a single `avoid_set` covers them, Dracula included at
weight 1 against 15/15/15 — reproducing the 8.75-per-region rate his own
spacing-640-at-0.35 set produced (**8.70** measured). Grand castles unchanged at **391**.

### Proven twice, on purpose

- **Offline** — `verify-base-spacing.js` models exclusion zones and `base_spread`, and
  re-derives from the structure NBTs the radius each clearance *needs*: need 313 blocks =
  20 chunks, configured 20, **measured 336** across 8 seeds. Because the number comes from
  the NBTs, a castle that grows fails this until the clearance grows with it. Verified the
  gate bites by setting the zone to 10 chunks: TOO SMALL, measured 176, overlap by 137.
- **In-game** — `./gradlew runWorldgentest` asks the server's own generator state through
  the real `isStructureChunk` path:
  `CASTLE_CLEARANCE castles=18 closest base 339 blocks (need 313)`. ✅

  A JavaScript reimplementation being right says nothing about a custom placement type being
  wired in correctly, which is exactly where this would have failed silently. That test
  needs its own run config: the render battery uses a **flat** world for deterministic
  aerial framing, and a flat world's generator carries only its preset's structure sets —
  measured as `minecraft:strongholds, minecraft:villages`, neither of them ours.

### Regression

`econ-selftest.sh` ALL PASS · `validate-dialogue.js` PASS · `verify-base-spacing.js` PASS ·
`verify-grand-castles.js` OK · `gen-base-plans.js` reproduces every NBT unchanged ·
`runGametest` **BUILD SUCCESSFUL, 101 frames**, citizen skins still resolving ·
`runWorldgentest` PASS.

### Still open: the castles are not yet bigger

This release makes bigger castles *possible*. It does not make them bigger. Re-importing at
true size needs, in order:

1. **A generator for the town shells.** `structures/working-town-shells/*.nbt` are three
   committed 501×24×501 plates with **no generator anywhere in the repo** — the importer
   only reads them. The four districts are stamped at fixed coordinates inside the 501 grid,
   so they cannot move outside a larger castle until they can be regenerated. This is a
   rule 4 problem in its own right: a generated file whose generator does not exist.
2. **The importer sized from the build** rather than `SIZE = 501` and `--source-radius 170`.
3. **Mug upgraded.** Cinderella is already upgraded and measured; Mug is 1.10.2 and needs the
   same `--forceUpgrade` pass (recipe and its trap are in `tools/measure-build.js`).
4. Re-import ×3, then raise `avoid_chunks` — `verify-base-spacing.js` will say by how much,
   and fail until it is done.

## 0.4.12 — the castles generate (2026-08-20)

### They never had

Reported from play: teleporting to a Dracula castle arrived at flat ground with no castle.
Confirmed on the live server three independent ways:

- `/locate` finds one — that is pure placement arithmetic and involves no blocks.
- **Zero entities.** Each castle carries 33 baked-in soldiers (21 for Dracula). Within 120
  blocks of the located Aegis centre: no soldiers, no citizens, one Pig.
- **Zero blocks.** A filtered clone over a 32×32×32 box at the exact centre returned `0`
  stone bricks, `0` bunks, `0` chests, `0` cobblestone.

The city record was still seeded and ticking (`base_aegis_castle_5744_10660_east`), which is
why nothing looked wrong from the server side.

### Why — an engine ceiling, not a setting

`ChunkGenerator.createReferences` scans a **hardcoded 8-chunk radius** for structure starts,
so a structure reaches at most **128 blocks from its start chunk**, and
`max_distance_from_center` is codec-capped at 128 as well. The castles are **501 blocks
across — 250 from centre**. No structure type can generate them.

This also answers "generate them the way Woodland Mansions do": mansions are ~200 blocks and
sit *under* that ceiling. Ancient cities and trial chambers likewise. There is no vanilla
structure wider than ~257 blocks.

It also explains why the castle render config stayed green throughout: it proves templates by
pasting them with `/place`, which bypasses worldgen entirely.

### The replacement

`CastleBuilder` replays the castle set's own placement maths on `END_SERVER_TICK` — the same
arithmetic `/locate` uses — loads the chunks each strip touches, and pastes the template **48
blocks at a time** so a 2.17-million-block castle never lands in a single tick.
`CastleSites` records the origin of each finished site, so a castle is built once and never
stamped over a player's work. Rule 7 throughout: no entity or block-entity tick anywhere in it.

### Verified by counting each castle's own chests back out of the world

| | chests in template | present in world |
|---|---|---|
| aegis | 8 | **8** |
| sarab | 63 | **63** |
| vostok | 861 | **861** |
| dracula | 118 | **118** |

Plus a natural site built end to end (`CASTLE_BUILT warfront:sarab/castle at -5258, 62, 2694
— 63/63 chests verified`), and screenshots taken of what the **builder** produced rather than
what `/place` produces.

### What the debugging cost, and why it is written down

Four rounds, and every one of them was the check being wrong rather than the code.

`StructureTemplate.filterBlocks` returns **template-relative positions** whatever `BlockPos`
is handed to it, so reading the world at those raw coordinates inspects a spot thousands of
blocks away. Three separate checks reported a confident zero against a paste that was
working:

1. looked for stone bricks — at Sarab, whose import is jungle canopy and snow;
2. recomputed the origin from the surface heightmap, which a finished castle raises;
3. passed `Blocks.AIR` to `filterBlocks`, which filters **for** the block given, so it
   sampled nothing and reported `0/0`.

What broke the deadlock was a probe that wrote a single diamond block at the origin and read
it straight back: `present=true, chunkStatus=minecraft:full`. That separated "cannot write
here" from "not looking where it wrote", and the next check found 63 of 63 chests.

### Regression

`econ-selftest.sh` ALL PASS · `validate-dialogue.js` PASS · `verify-base-spacing.js` PASS ·
`verify-grand-castles.js` OK · `runWorldgentest` **BUILD SUCCESSFUL** including clearance,
natural build, and all four types.

### Still open

- **Sarab's import is wrong.** Its screenshot is jungle canopy and a snowy cliff with no
  visible keep: the Mug Castle build is spread across its source map and the fixed 341×341
  window centred on terrain. It now *generates* faithfully — it is simply the wrong 341×341.
- **Vostok has a stray slab floating above it**, visible in its template render.
- Castles are still 501, not the larger sizes the supplied builds could support; the importer
  still hardcodes `SIZE = 501` and `--source-radius 170`.

## 0.4.13 — the castle paste stops stalling the server (2026-08-20)

0.4.12 built castles correctly and cost the live server **14 seconds** doing it:
`Can't keep up! Running 14231ms or 284 ticks behind` on the first castle in the fresh world.
The paste itself is cheap; generating a strip of up to 128 chunks in a single tick is not.

Chunk loading is now budgeted to **6 per tick**, and the slice waits rather than pasting into
ground that does not exist yet — a slice written into unloaded chunks reports success and
leaves nothing behind, which is the failure this whole class exists to prevent.

The first attempt at budgeting deadlocked: `level.getChunk` pulls a chunk in without a
ticket, so it unloads again before the next tick and a budgeted loop re-loads the same
chunks forever. `setChunkForced` holds them, and the tickets are released the moment the
castle is finished rather than left holding a thousand chunks for the life of the world.

Re-verified, all four types: `aegis=8/8 sarab=63/63 vostok=861/861 dracula=118/118`, plus a
natural site end to end.

## 0.4.14 — the castles as reported from play (2026-08-21)

Jesse played 0.4.13 and reported, with screenshots: castles floating above the ground on
slabs and pedestals; the wrong castle model "half the time"; the Dracula site showing a
completely different castle; no soldiers or citizens in the castle proper; no loot; empty
interiors missing the specified rooms; and the King of Vostok standing in a cobblestone
basement full of note blocks, talking like a grunt. Every one of those traced to a real,
distinct defect.

### Which castle, where, and when — CastleBuilder now reads the world

- **Wrong model / wrong Dracula.** The builder picked the faction with a private hash of
  the chunk coordinates, while `/locate`, discovery and the city ledger read the chunk's
  recorded `StructureStart`. It now reads the same start everything else reads
  (`ChunkStatus.STRUCTURE_STARTS`) and pastes exactly that castle; a placement chunk whose
  entries were all biome-vetoed is skipped instead of getting a castle no system knows.
- **~250-block offset.** The paste was centred on the anchor chunk; the jigsaw start box
  begins AT it. The paste is now anchored to the start's own bounding box, so blocks,
  ledger, citizens and `/locate` agree.
- **Floating.** One `WORLD_SURFACE_WG` column (treetops included) decided the height of
  half a million blocks. Now: median `OCEAN_FLOOR` of a 5x5 grid across the footprint,
  budgeted like the slices; each slice then carves terrain above the paste height and
  drops foundation columns from the template's underside to solid ground.
- **Empty castle.** Civilians were seeded at discovery — before any block existed — and
  the paste landed on them; district anchors were hardcoded ±178 (only right for 501
  wide); reconcile dragged anything 5 blocks above "ground" down to the plate, kings and
  wall-watch included. Castle seeding now runs from `onCastleBuilt` with the real paste
  origin and size; castle bases hydrate only after they exist; castle soldiers keep their
  altitude.

### The imports (see structures/CASTLE-IMPORTS.md for the full record)

Crop centres are measured by the importer's new `--scan` (mass centroid + tall-material
bins + Y histogram) **and their renders read before being trusted** — the scan alone
centred Vostok on decorative parasol trees and Sarab on cliff spires with the keep at the
crop edge. Renders of the final three crops show each keep at its template centre. min-y
sits just below the measured ground plateau, cutting the source worlds' buried machinery
(the Vostok note-block room among it). Each castle carries the SOURCES.md interior —
rethemed grand library, study, storage junction and two concealed vault passages —
stamped beneath a throne the importer derives from the build (highest roofed chamber near
centre: kings at template y=113/42/52), plus royal guards, a rampart watch, vault
sentries, and full loot coverage (24 rich + hidden_vault + common on everything else;
`minecraft:empty` is gone and the verifier rejects it, Dracula included via `--retrofit`).

### The king is a court

Rank king now maps to its own dialogue role, and a royal audience is exclusive both ways:
kings offer only royal.json lines (authored per faction voice: presence, realm, war,
people, counsel, defiance, farewell — 23 options, 189 response lines across all bands),
and nobody else ever offers them. Kings lose station/hunt/patrol/travel/stroll goals at
rank application and hold their chamber via KingCourtGoal; the royal guard fights.

### Gates

`verify-grand-castles.js` OK (per-castle pins incl. king height ≥ y20) ·
`verify-base-spacing.js` PASS (496 measured vs 463 needed) · `validate-dialogue.js` PASS
(1,886 options / 4,607 lines, royal 23/23/23 per faction) · `econ-selftest.sh` ALL PASS ·
`gradlew build` clean · render battery + worldgen battery: see below.

### The fourth defect, found by the gates: castle starts had NEVER generated since 0.4.9

Wiring the truth-based builder into the batteries surfaced a deeper fault than any
reported: a jigsaw start piece must fit the generation box (`max_distance_from_center`
is codec-capped at 128), so from the moment the template pools pointed at 501-wide
monoliths, `findValidGenerationPoint` failed silently everywhere — no StructureStart,
no /locate, no discovery, no ledger, on any fresh chunk. Every castle the live server
ever "located" was a relic start baked into pre-0.4.9 chunks. Fixed by decoupling the
record from the blocks: the pools now place a 16x4x16 zero-block marker
(`tools/gen-castle-marker.js`), and the builder pastes the monument at the marker's
site. Along the way: `getChunk(STRUCTURE_STARTS)` returns an empty placeholder in 26.2
(measured: 40 candidates, 40 empty answers, under a second) — the working oracle is
`StructureCheck.checkStructurePresence`, the same machinery /locate uses.

### Proven end to end on the dedicated dev server (fresh world, 2026-08-21)

- `/locate` finds all four castle structures on a fresh world. ✅
- Walking Watcher to each located site builds THAT castle: vostok 927/927, sarab
  1514/1514, aegis 10/10, dracula 118/118 chests verified in world. ✅
- `CASTLE_GROUND` sampled footprints (e.g. 58..122 → paste height 71) — castles are
  carved in uphill and footed downhill instead of hovering. ✅
- The moment the last slice is down: `Registered base sarab_castle@-7135,3088` and
  `CASTLE_POPULATION seeded 240 citizens across 5 districts`. ✅
- Dracula builds with zero "Can't keep up" warnings after pacing (8-wide slices,
  three single-tick phases on alternate ticks, tickets released 40/tick). Residual:
  the densest template (aegis, 4.2M records) still accrues some tick debt on a dev
  machine; a castle takes a few minutes of background construction, once, per site.

### Batteries

`runCastlerender` BUILD SUCCESSFUL (8m05s) — all four castle aerials + obliques READ:
each type is its own build, centred, towns present; plus the full 101-frame suite.
`runWorldgentest` BUILD SUCCESSFUL (11m35s) — clearance 505 vs 313 needed; natural
sites: builder decisions honest (3 biome vetoes on this ocean-heavy seed); all-types
paste `aegis=10/10 sarab=1514/1514 vostok=927/927 dracula=118/118`.

### Residuals, written down on purpose

- Existing worlds keep their broken 0.4.13 castles: CastleSites marks those sites
  built, and pre-0.4.14 chunks carry either relic starts or none. Castle fixes are
  fully visible only in fresh chunks — for structure work, a fresh world (the empire
  world was already throwaway pending Wave 4).
- The client-gametest integrated server answers structure queries with empty
  placeholder chunks; its natural-site assertion is deliberately "builder made an
  honest decision", with paste coverage from the all-types check and the end-to-end
  proof on the dedicated server above.
- Castles appear only where the has_structure biome tag allows (plains, forests,
  savanna, taiga, snowy plains, desert, windswept hills). 0.4.13 pasted ghost
  castles at every placement chunk regardless of biome — including modded biomes —
  which is how a castle ended up towering over an enchanted forest with no systems
  attached. If castle frequency now feels low, widening the tag is a design decision,
  not a bug fix.

## 0.4.15 — the sea, the veil, the Count, and the blend (2026-08-21)

Four reports from Jesse's live play of 0.4.14, all addressed:

- **Coastal castles flooded.** A coastal footprint's OCEAN_FLOOR samples dragged the
  median paste height under the waterline (his Sarab pasted at y=48 — 15 under the sea)
  and the site carve dug a basin the ocean filled. Paste height is now floored at sea
  level; the carve can never breach the waterline, and ocean columns get foundation
  piles to the seabed. The "milling, limited citizens" report was downstream of this:
  240 citizens seeded correctly at that site, then crowded the few dry patches.
- **No Dracula.** He was baked into the template all along — and his own vampire rules
  killed him: castle builds in daylight, he strolls a courtyard, the sun takes 6 HP/s.
  `VampireVeil` now raises the Count in a roofed chamber whenever a mortal enters an
  un-slain castle, leashes him to his keep (`setHomeTo`, 40 blocks), and only a
  player's killing blow marks the site permanently slain (`CastleSites.dracula_slain`).
- **The vampire's veil.** Entering Dracula's grounds turns THAT PLAYER's world to
  midnight under a blood-red full moon with snowfall — four client-only mixins
  (sky-state rotated by the time-delta to midnight so vanilla's own conventions hold;
  moon tinted at its one uniform write; weather forced to snow at the renderer's call
  sites; lightmap and fog collapsed to night). Server time, weather and mob logic are
  untouched. Battery frames READ across two iterations: the first showed salmon-brown
  snow (red-tinted sky light dyeing the flakes — removed) and a bright day-fog horizon
  (FogRendererMixin added). Final frames: black sky, red moon, white snow, dark
  horizon; veil-off frame restores noon instantly. `/warfront veil on|off` for demos.
- **"Blend like a Woodland Mansion."** Mansions displace exactly their own volume
  because their templates carry air; the imports carry none, which is why the builder
  flattened the whole square. Each castle now ships a per-column occupancy sidecar
  (`tools/gen-castle-occupancy.js`, verifier-pinned): occupied columns are cleared from
  the castle's own base upward and footed to solid ground; untouched columns keep their
  hills, trees and water. Dracula occupies 23.9% of his square — three-quarters of his
  grounds are now real terrain. Verified from the battery world's region files with the
  new `tools/render-world-topdown.js` (the client-gametest camera cannot stream remote
  chunks reliably — three attempts produced fog; region files cannot fail to mesh):
  Dracula's islands sprawl across a real archipelago with no square scar, Sarab's
  estate has rivers and snowfields threading between its buildings, Aegis keeps its own
  imported grounds with the world running cleanly to their edge.

Gates: `verify-grand-castles.js` OK (now also pins occupancy sidecars) ·
`verify-base-spacing.js` PASS · `runCastlerender` BUILD SUCCESSFUL, veil frames read ·
`runWorldgentest` BUILD SUCCESSFUL, all four types chest-verified under the new site
prep (aegis 10/10, sarab 1514/1514, vostok 927/927, dracula 118/118).
