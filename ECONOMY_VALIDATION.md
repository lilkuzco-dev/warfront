# Civilization Phase 2 — economy validation

Validation date: 2026-08-16. The model starts 250 citizens with exactly 1,000 money
each. Skills, metabolism, aptitude, professions, and access to finite resource nodes
are deterministic heterogeneous traits. These results come from the build-failing
JUnit suite, not hand-authored fixture output.

## Research basis translated into mechanics

- Sugarscape's heterogeneous agents, spatially unequal renewable resources, and
  metabolism became finite prime/secondary/peripheral food, ore, and timber nodes,
  individual skill/aptitude, and periodic upkeep:
  <https://jasss.soc.surrey.ac.uk/12/1/6/appendixB/EpsteinAxtell1996.html>
- Conserved-exchange econophysics became fixed-amount bilateral transfers in a closed
  money supply. No market action mints money:
  <https://arxiv.org/abs/cond-mat/0004256>
- Liquidity-constrained participation became the poverty floor: an actor below the
  configured floor cannot fund production inputs or fixed exchange. Food sales,
  recovery contracts, and shocks still make escape possible:
  <https://www.aeaweb.org/articles?id=10.1257%2F00028280260344489>

These are design translations, not claims that the game reproduces any paper's
calibration. The acceptance target is a deterministic, auditable simulation that
creates heterogeneous outcomes from an equal start.

## Equal-start emergence gate

Configuration: seed `0x5EEDC1A7`, population 250, starting wealth 1,000, liquidity
floor 100, fixed exchange 3, 250 exchanges per tick, automatic shock interval 400,
shock severity 180 permille.

| Tick | Gini | Below floor | Top 5% share | Min | Q1 | Median | Q3 | P90 | Max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 0.0000 | 0.0% | 4.8% | 1,000 | 1,000 | 1,000 | 1,000 | 1,000 | 1,000 |
| 100 | 0.0509 | 0.0% | 5.8% | 851 | 1,003 | 1,065 | 1,135 | 1,218 | 1,459 |
| 1,000 | 0.4514 | 17.2% | 29.9% | 113 | 588 | 936 | 1,203 | 1,435 | 53,518 |
| 5,000 | 0.6869 | 39.2% | 44.1% | 66 | 99 | 501 | 1,477 | 2,281 | 43,207 |
| 10,000 | **0.7518** | **52.0%** | **47.6%** | 66 | 98 | 100 | 1,413 | 2,831 | 42,323 |

At tick 10,000 the lower half is at or near the liquidity constraint, while Q3 and
P90 remain far above it and a long elite tail persists. This passes the requested
poor/middle/elite emergence gate; outcomes did not move uniformly upward.

## Conservation and determinism

- Money remained exactly **250,000** at every checkpoint.
- Every economic tick asserts:
  `initial goods + regeneration - consumption - shock loss = actor goods + node stock`.
  Any mismatch throws and fails the build.
- Embodied inventory reconciliation classifies real mined/harvested goods as
  production and removed/consumed goods as consumption; a dedicated test exercises
  both directions without breaking the audit.
- Two independent models with the same seed and 10,000 ticks produce byte-for-byte
  identical money and net-worth arrays. Encode/decode/restart restores the identical
  distribution and conservation ledger.

## Shock and regional-price gates

A matched baseline and shocked run diverged only when one received vein depletion
and blight at tick 5,000. At tick 10,000, **68/250 citizens moved at least ten wealth
ranks** relative to baseline: 30 rose and 38 fell. The shock therefore reshuffled
fortunes instead of applying a uniform multiplier.

In a second matched-region test, the disrupted city's ore price reached **108** while
the unshocked region's was **94**. Prices are updated from each city's local demand
and inventory supply; they are not a global fixed table.

## Performance and live persistence

- A 500-citizen pure-data model averaged **0.013 ms per economic tick** over 2,000
  measured ticks after warmup, below the 1 ms gate on the validation machine.
- A live 20-citizen dedicated-server city measured 0.493 ms on its first economic
  tick and 0.196 ms on its second, including synchronization and persistence.
- Five simultaneous 500-citizen Tier-3 cities measured **0.396, 0.409, 0.456, 0.498,
  and 0.769 ms** for their full model + conservation + snapshot ticks. All 2,500
  citizens were confirmed virtual. With those cities plus the existing embodied and
  abstract test cities, the server ran at **7.0 ms average / 12.4 ms P95 / 15.3 ms
  P99** (100-tick sample; 50 ms budget).
- Live money remained 20,000 and conservation stayed true before and after injected
  vein depletion plus blight. Restarting the dedicated server restored the exact
  tick, Gini, quantiles, prices, money, goods, and conservation status.
- Promoting that saved city after virtual-only ticking reconstituted all 20 citizens
  with current inventory (a sampled entity carried 47 wheat); the following economy
  tick remained conserved at 20,000 money and 504 goods.

## Player/operator surface

`/warfront city economy <city>` reports the live tick, Gini, poor share, top-5 share,
wealth quantiles, prices, total money/goods, conservation status, and tick cost.
`/warfront city shock <city> <vein_depletion|blight|raid|fire>` injects an explicit
test or story shock. Datapacks can tune the economic cadence, wealth/liquidity floor,
exchange intensity, and automatic shock interval/severity in
`warfront_config/economy.json`.

Phase 2 stops at this review gate. Derived class labels, policy, taxation, welfare,
governors, and class-driven buildings belong to Phase 3 and are not implemented.
