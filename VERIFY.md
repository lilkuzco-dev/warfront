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
