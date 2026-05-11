# Grid Generation Bench Findings — 2026-05-11

**Status:** Findings — actionable revert + future-direction guide
**Bounded context:** `grid/domain`

## TL;DR

Two algorithmic experiments were explored in this session: (1) a strict-descending length bias on the existing two-phase pipeline (`SlotPlanner` then `SkeletonFiller`), and (2) a unified plan-+-fill search (`GridSolver`) that collapses the two phases. Both were benched against the pre-bias bucketed baseline on 10×10 (N=50) and the production 15×12 (N=200) grids.

The descending-bias commit `9065e9a` is a **catastrophic regression** at both grid sizes — single-shot success drops from 84% to 0% on 10×10 and from ~2.5% to 0% on 15×12. The fusion architecture is also a regression, going 0% → 2% with the full connected + MRV stack on 10×10 vs the bucketed baseline's 84%.

**Action:** revert `9065e9a` ("refactor(grid): order slot lengths strictly descending") on `main`. The pre-bias bucketed `orderForBias` is currently the best-performing configuration we have.

## Bench setup

- Production corpus (`CsvWordRepository.frenchFromClasspath()`)
- Default constraints except where noted
- Per-attempt timeout: 5 s (`DEFAULT_GENERATION_TIMEOUT_MS`)
- JIT warm-up: 5 generations
- Run via `GridGenBenchmarkTest.200 puzzles per-phase metrics on the production corpus`

## Results — 10×10, N=50

| # | Stack | Success | p50 ms | p75 ms | p95 ms | fill_backtracks p50 | first_slot_domain |
|---|---|---|---|---|---|---|---|
| 1 | main (`9065e9a` descending bias) | **0/50 = 0%** | 5046 | 5049 | 5055 | 52 935 | 2440 |
| 2 | bucketed bias (revert `9065e9a`) | **42/50 = 84%** | 95 | 401 | 5001 | 2 577 | 108 |
| 3 | fusion (commits `d7147bb` + `b111d98`) | **0/50 = 0%** | 5009 | 5010 | 5011 | 145 559 | 321 |
| 4 | fusion + connected-first + MRV (`6cff891` + `6e2ea66`) | **1/50 = 2%** | 5002 | 5002 | 5002 | 1 696 913 | 321 |

## Results — 15×12, N=200 (earlier samples)

| # | Stack | Success | Notes |
|---|---|---|---|
| 1 | main (`9065e9a` descending bias) | 0/200 = 0% | All hit 5 s budget; user-provided data |
| 2 | bucketed bias (pre-`9065e9a`) | 5/200 = 2.5% | User-provided, single-shot baseline |
| 3 | fusion | 0/80 (stopped) | Same flat-line as variants 1 + 3 |
| 4 | fusion + connected-first | 0/40 (stopped) | Same |
| 5 | fusion + connected-first + MRV | 0/140 (stopped) | Same |

## Findings

### 1. Descending bias is catastrophic, not just suboptimal

The 2026-05-11 commit `9065e9a` reordered `SlotPlanner.orderForBias` from bucketed (mid 3–6 first, long >6 second, 2 last) to `sortedDescending()`. The rationale was that longer slots would be more visually compelling and that fragmentation of remaining cells would help the CSP. Empirically:

- **10×10**: success rate dropped from 84% to 0% — every puzzle now times out.
- **15×12 production**: success rate dropped from 2.5% (already low) to 0%.
- `first_slot_domain` at 10×10 jumped from 108 (bucketed) to 2440 (descending) — the search starts on a much wider domain, which the filler can't constrain into a solution within 5 s.

The descending-bias change is unsalvageable in its current form. The visual goal was real, but achieving it needs a different lever (e.g. per-arrow capped long-slot quota, or corpus-density-weighted bias) — not strict descending.

### 2. The two-phase architecture is load-bearing

Three independent fusion benches at production 15×12 plus two at 10×10 all converge toward 0% success. Mixing length and word decisions at every recursion level multiplies the search tree faster than the corpus-feasibility gate prunes:

- **`slot_plan_backtracks` p50**: 0 (two-phase) vs 750k (fusion only) vs 5.6M (fusion + connected + MRV).
- **`fill_backtracks` p50**: 2.6k (two-phase bucketed) vs 146k (fusion only) vs 1.7M (fusion + connected + MRV).

`SlotPlanner.planVariable` searching length plans corpus-agnostically is *faster* than the unified solver consulting the corpus mid-plan, because the structural pruning kills bad plans in microseconds while the unified solver pays for ~1–5M backtracks before the deadline.

### 3. Smart variable ordering (connected-first + MRV) helps the fusion stack, but not enough

Going from fusion-only (0/50) to fusion + connected-first + MRV (1/50) is a real signal — the variable-ordering improvements do help the unified search. But the architecture itself starts from a hole too deep for variable-ordering tweaks to fill.

## Recommended action

1. **Revert `9065e9a`** on `main`. Single-line change in `SlotPlanner.orderForBias`. Restores 84% single-shot success on 10×10 and ~2.5% on 15×12.
2. **Drop the unified-solver experiment** — leave only the bench refactor (`0ba6951`) and this findings doc on the branch for PR.
3. **Future algorithm work should layer onto two-phase** — `SkeletonFiller` (the fill phase) is the leverage point.

### Candidate future directions, ordered by expected leverage

1. **Per-attempt timeout split in `GeneratePuzzleUseCase`** — currently one 5 s attempt, retry with self-calibrating timeout. With p50 = 95 ms on 10×10 successes and ~5 s on failures, try N attempts with progressively tighter timeouts (e.g. 200 ms × 5, then 1 s × 3, then 5 s) — would dramatically improve total wall time on bad-seed cases.
2. **Conflict-directed backjumping in `SkeletonFiller`** — current code does chronological backtracking. CBJ identifies which earlier decision caused the conflict and jumps directly there.
3. **AC-3 propagation after each word placement** — propagate domain shrinkage transitively through the intersection graph, catching dead-ends a level deeper than the current forward check.
4. **Capped long-slot quota in `SlotPlanner.orderForBias`** — keep bucketed order but bias the mid bucket toward 5–6 over 3–4, and allow ≤ K long slots per plan. Recovers some of the visual-weight intent of the descending bias without the catastrophic CSP cost.
5. **Reduce production grid size to 10×10** — 84% single-shot, p50 = 95 ms. A product decision, but the bench gap (84% vs 2.5%) is large enough to be worth weighing.

## Branch / commit references

- This branch (`worktree-chore-bench-slf4j-logs`):
  - `0ba6951` — bench SLF4J refactor (kept for PR)
  - 12 cancelling commits (fusion impl + reverts + re-applies) — already reset out
- `main`:
  - `9065e9a` — descending bias, **proposed for revert**
  - `42b4ff2` — spec for descending bias (kept as historical record)

## What we did NOT do

- Did not revert `9065e9a` on `main` (left for the user to decide).
- Did not rerun the 15×12 bench on each variant at full N=200 — stopped at first clear signal (≥80 samples) to save time.
- Did not pursue the future directions listed above — they're proposals, not implementations.
