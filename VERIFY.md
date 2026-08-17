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
