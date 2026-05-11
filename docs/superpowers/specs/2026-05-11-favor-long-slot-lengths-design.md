# Favor Long Slot Lengths in Grid Generation

**Status:** Approved
**Date:** 2026-05-11
**Bounded context:** `grid/domain`

## Motivation

Generated mots fléchés grids currently bias toward mid-range slot lengths (3–6), with longer slots tried only after the mid bucket and 2-letter slots tried first within their bucket alongside 3–6. This produces grids that are dense with short words and visually flat.

Two goals motivate the change:

1. **Visual / quality.** Longer words give the grid more visible "marquee" content. Surface-form inflation in French (`unir` → `unissent`, etc.) means long slots have healthy candidate pools even though long *lemmas* are rarer.
2. **Performance.** Picking the longest viable length first naturally fragments the remaining available cells faster. Deeper in the search tree the planner is forced into short slots anyway; eating the constrained long-slot decision early avoids exploring shorter alternatives that fail later for cell-budget reasons.

## Scope

Single function in a single file:

- **File:** `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SlotPlanner.kt`
- **Function:** `orderForBias` (lines 191–199)

No signature changes, no new types, no new constraints in `GridConstraints`, no plumbing into other modules.

## Change

`orderForBias` currently returns lengths bucketed as `mid (3..6) + long (>6) + short (==2)`, each bucket shuffled with the supplied `Random`.

New behaviour: return `candidates.sortedDescending()`. Deterministic per arrow, no randomness.

For an arrow with `available = 10`, `candidates = validLengths(10) = [10, 9, 2, 3, 4, 5, 6, 7]`, the new ordering is `[10, 9, 7, 6, 5, 4, 3, 2]`. The planner tries the longest viable length first and backtracks downward to shorter lengths only when a longer choice produces an unrecoverable plan (orphan cells, dead clue cells).

`validLengths`'s contract is unchanged — it remains the source of truth for which lengths are valid; `orderForBias` is the separate ordering concern. The `Random` parameter on `orderForBias` becomes unused but is retained for signature stability (low-cost optionality if seeded variety is reintroduced later).

## Determinism trade-off

This makes slot planning **deterministic per skeleton**. The same skeleton input produces the same length plan every time.

Run-to-run variety in the final grid still comes from two upstream / downstream sources:

1. **Skeleton randomization** (upstream of `SlotPlanner`) — skeletons themselves vary per generation.
2. **Word selection** in `SkeletonFiller` via `headShuffle` — slot lengths are fixed, but the words placed into them still vary across runs.

This is acceptable because the caller paths that matter (live generation, daily grid) randomize the skeleton upstream. No caller currently relies on plan-shape variety on a fixed skeleton.

## "Variation between horizontal and vertical"

Falls out naturally: each arrow's `available` cell budget depends on its direction. In a 10×12 grid, an H arrow at row 0 sees `available = 10` and starts at bucket 10; a V arrow at col 0 sees `available = 12` and starts at bucket 12. No explicit direction-keyed logic is needed.

## Test plan (TDD, red → green → refactor)

All tests live in `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/` alongside existing `SlotPlanner` tests.

### Red phase (new behaviour)

1. **`orderForBias returns candidates in strictly descending order`**
   Input: `[2, 3, 5, 7, 10]` (or a permutation). Expected: `[10, 7, 5, 3, 2]`.

2. **`orderForBias is deterministic across seeds`**
   Call `orderForBias` twice with two different `Random` seeds. Expected: identical results.

3. **`orderForBias preserves all input candidates`**
   No length added, none dropped.

### Green phase

Replace function body with `candidates.sortedDescending()`. Tests turn green.

### Refactor phase

Update `orderForBias` KDoc to reflect the new bias and the rationale (push visual weight to long words; fragment available cells faster so the tail of the search tree has shorter slots and looser CSPs).

### Regression coverage (already in place)

Existing `planVariable` integration tests pin behavioural invariants that this change must not break:

- No orphan cells in the final plan.
- No dead clue cells.
- Backtracking still recovers from infeasible long-first picks.

These tests are not modified.

## MANIFESTO compliance map

| Principle | Status |
|---|---|
| Domain has zero infra deps | ✓ pure domain logic |
| TDD: failing test first | ✓ red → green → refactor order above |
| Test behaviour, not structure | ✓ tests pin the ordering contract |
| Small PR (< 400 lines) | ✓ ~20 lines code + ~30 lines tests |
| Conventional commit, lowercase subject | ✓ `refactor(grid): order slot lengths strictly descending` |
| Architecture tests | ✓ no new dependencies, ArchUnit/Konsist unaffected |
| Each module builds and tests independently | ✓ change is internal to `grid/domain` |

## Out of scope

- **Hard ban on `L = 2`** (or `L = 3`). Defer until we see real grids generated under the new bias. If 2-letter slots remain too prevalent, a follow-up can drop them from `validLengths` with care for the corner orphan-safe constraints.
- **Weighted random sampling by length.** Considered (Approach B in brainstorming); bucket model collapses to descending sort here, so weighted sampling is redundant.
- **Run-to-run plan-shape variety on fixed skeletons.** Not required by any current caller.
- **Configurability via `GridConstraints`.** YAGNI.

## Risk and observability

Expected wins:

- More long words visually.
- Fewer late-tree backtracks because cell budgets shrink predictably.

Possible regression: first-slot candidate domain in `SkeletonFiller` may be smaller (long words are less common), which could increase fill-phase backtracks even as slot-plan backtracks drop.

Existing `GenerationMetrics` already captures both signals (`fillBacktracks`, `slotPlanBacktracks`, `fillFirstSlotDomainSize`). After merge, spot-check a batch of generated grids on the production skeleton and compare before/after. No CI gate; revert is one commit if perf regresses sharply.
