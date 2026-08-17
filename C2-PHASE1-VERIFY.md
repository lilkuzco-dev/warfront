# Command & Control — Phase 1 gate

Status: **implementation and visual/performance verification complete; not released**.

## Implemented

- `warfront:screen` panels discover one coplanar rectangular wall, bounded to 5x3.
  Every wall has one controller and one 128x128 dynamic texture; panels submit only
  their own UV slice. Normal placement rejects a sixth column/fourth row; command-built
  overflow is partitioned into non-overlapping world-stable tiles.
- `warfront:projector` binds the same feed model and renders a two-level translucent
  table hologram with light curtains.
- Live terrain maps sample only client-loaded chunks and refresh once per 40 ticks per
  controller, independent of panel count.
- Tactical overlays consume registered providers. Phase 1 exposes only bases belonging
  to factions where the bound player has friendly standing, plus targets engaged by
  those factions' soldiers. Same-tick requests share one provider snapshot.
- Satellite feeds are hidden when Cosmos is absent. When Cosmos is present, the
  reflection-only bridge queries its public constellation, Kinetics orbit state/ground
  track, `CommsCoverage`, and `ReconImager` APIs. Server-authored recon pixels replace
  the last image only while the footprint covers the configured center and comms exists,
  so remote imagery does not depend on client-loaded chunks.
- Use cycles available feeds and binds the display to the player/current coordinates;
  sneak-use recenters, and on the satellite feed also cycles owned recon satellites.
- Both blocks are in the Warfront creative tab. All static art is original and generated
  by `tools/gen-textures.js`; upstream audits are in `ASSETS-ORIGIN.md` and
  `LICENSES-UPSTREAM.md`.

## Automated evidence (2026-08-17)

- `./gradlew clean build`: PASS (18 JUnit tests, client compilation, resource processing,
  and remapped jar).
- Asset JSON parse: PASS.
- `DisplayWallLayoutTest`: 5x3 controller/UV coordinates, all four facings, and
  incomplete-wall splitting: PASS.
- Dedicated server without Cosmos: 44 mods loaded; Warfront initialized; 15 screen
  block entities plus one projector placed and serialized; no registry errors or
  exceptions.
- Post-audit cadence probe without Cosmos: with the game frozen, seeded bogus tactical
  markers were cleared after a 25-tick step, and seeded stale satellite name/pass/comms,
  signals, and pixels were cleared after a 105-tick step. This directly proves the
  refresh body runs and the soft dependency fails closed.
- A corrected 100-tick server sample after 200 baseline ticks and 500 active-scene ticks
  exercised a 5x3 tactical wall plus projector (both controllers refreshed markers):

  | Scene | average | P50 | P95 | P99 |
  |---|---:|---:|---:|---:|
  | display blocks absent | 5.6 ms | 4.7 ms | 13.2 ms | 16.0 ms |
  | 5x3 wall + projector active | 5.5 ms | 4.8 ms | 12.6 ms | 14.0 ms |
- The first refresh/timing run was invalidated by audit: a `Long.MIN_VALUE` subtraction
  overflow meant the real refresh body never ran. The numbers are retained only as
  historical context and are not acceptance evidence:

  | Invalidated scene | average | P50 | P95 | P99 |
  |---|---:|---:|---:|---:|
  | wall/projector absent | 19.5 ms | 17.5 ms | 41.4 ms | 68.3 ms |
  | 5x3 wall + projector | 20.6 ms | 18.6 ms | 33.5 ms | 77.3 ms |

  Regression tests now cover first refresh, cadence, clock reset, legal 5x3 layouts,
  all facings, holes, placement rejection, and deterministic overflow partitioning.

## Client gate

`WarfrontRenderTest` now builds a correctly oriented 5x3 live wall plus projector,
samples 120 observations per scene, reports median FPS/P95 frame time, asserts against
a material regression, and captures `c2_5x3_live_wall_and_projector.png`.

The corrected automated client harness passed on the Apple M4 Max/OpenGL backend:

| Scene | median FPS | P95 frame time |
|---|---:|---:|
| baseline | 120 | 0.999 ms |
| 5x3 wall + projector | 120 | 1.227 ms |

The screenshot confirms a unified front-facing 5x3 feed and visible projector hologram.
The same run exposed and verified the fix for disconnect cleanup attempting to release
GPU textures from Netty's I/O thread. No C2 assertion, rendering, or cleanup error
remained in the final run.

Run from the Warfront checkout with JDK 25:

```sh
export JAVA_HOME=/Users/jessehagy/jdks/jdk-25.0.4+7/Contents/Home
./gradlew runC2gametest
```

The remaining release ritual is: tag/release, committed installer-manifest bump, and
`tools/postship-check.sh` including its load-compatibility gate.
