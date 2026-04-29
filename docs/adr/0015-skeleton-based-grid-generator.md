# ADR-0015: Skeleton-Based Grid Generator

## Status

Accepted

## Context

The original `GridGenerator` used a greedy rectangular-block CSP: it attempted
to place words one by one on a blank canvas, backtracking when no candidate fit
the partially-committed letter intersections. That approach had two structural
problems:

1. **Structural validity not guaranteed.** The greedy placer had no global model
   of the clue-cell layout, so it could emit grids where letter cells existed
   with no reachable clue arrow, or where interior block cells appeared (cells
   that were never assigned a type). Both states are invalid for the French
   mots-croisés format.

2. **Interlocking not enforced.** Without a prior skeleton, words were placed
   without requiring intersection, producing sparse or disjoint layouts that
   look nothing like a real crossword.

A re-design was needed that (a) guaranteed structural validity before word
selection began, and (b) enforced the interlocking property that every letter
cell belongs to exactly one horizontal and one vertical word run.

## Decision

Replace the greedy rectangular-block CSP with a three-phase pipeline:

### Phase 1 — Skeleton (`Skeleton.kt`)

A pure function over `(width, height)` that produces a deterministic list of
`ClueArrow`s placed on the boundary. The corner `(0, 0)` carries a dual
`DOWN_RIGHT + RIGHT_DOWN`; even-indexed columns on the top row and even-indexed
rows on the left column carry dual arrows; odd positions along those borders are
letter cells. No randomness enters here. The skeleton is the invariant frame
that every valid grid of a given size must satisfy.

### Phase 2 — Slot Planner (`SlotPlanner.kt`)

Takes the skeleton's arrows and produces `WordSlot`s — concrete (position,
direction, length) tuples. Two strategies are implemented:

- `planFullLength` (v1): every slot runs to the grid edge. Deterministic,
  produces the densest valid layout, used for validation and tests.
- `planVariable` (v2): each slot independently samples a length from the
  valid-length set `{M, M-1} ∪ [2, M-3]`, then inserts a trailing clue cell
  when the word stops short of the edge. That trailing clue cell carries a
  same-axis continuation arrow (so the next word after the gap starts correctly)
  and a perpendicular arrow (replacing the skeleton arrow that the trailing
  clue's position invalidates). This step introduces all layout variability.

### Phase 3 — Skeleton Filler (`SkeletonFiller.kt`)

Given the finalized `WordSlot` list, runs a standard backtracking CSP using the
`WordRepository`'s position-letter index. Arc consistency is not implemented;
the index lookup (words matching a partially-committed pattern) is the pruning
mechanism. A deadline is passed to bound wall-clock time.

### Why this decomposition

- Phases 1 and 2 together guarantee that every cell in the grid is typed
  (letter or clue) before any word is chosen — eliminating the "interior block
  cell" defect class.
- Phase 2's trailing-clue logic is the only place where new clue cells appear;
  it is self-contained and testable in isolation.
- Phase 3 is a pure CSP that sees only typed slots with intersection
  constraints — it has no knowledge of the skeleton layout, making it
  independently testable and replaceable.

## Alternatives considered

**Arc-consistency pre-processing (AC-3).** Would prune the Phase 3 search space
more aggressively but requires computing the constraint graph up front, which
adds complexity roughly proportional to the number of intersecting slot pairs.
The position-letter index is already fast enough (p50 < 1 s on the production
corpus at 100 calls); AC-3 is deferred until profiling shows it is needed.

**Constraint propagation inside the filler.** After each word is placed, forward
checking could propagate the committed letters to reduce candidate lists for
intersecting slots. Deferred for the same reason as AC-3.

**Keeping the greedy rectangular-block CSP for the non-interlocked path.**
The old algorithm is preserved behind the `enforceInterlocking = false` flag in
`GridConstraints` and removed from the interlocked path only. This allows
A/B comparison and safe rollback without destroying the test history that was
accumulated against it.

## Consequences

### Easier

- Structural validity (`no block cells inside`, `every letter cell reachable
  from a clue walk`) is now an invariant of Phase 2, not something the CSP
  tries to enforce implicitly. The `GenerationInspectionTest` suite verifies
  this over 100 seeds.
- Each phase is independently unit-testable: `SkeletonTest`, `SlotPlannerTest`,
  `SlotPlannerVariableTest`, and the filler is covered by `GenerationInspectionTest`.
- The position-letter index added to `CsvWordRepository` benefits both the
  old and new generation paths.

### Harder

- The variable-length slot planner's trailing-clue logic (`PlanState.kt`) is
  the most complex piece: each trailing clue must invalidate the perpendicular
  skeleton arrow it sits on and issue its own arrows. Bugs here produce
  unreachable letter cells rather than hard failures — the inspection tests
  catch these but the error mode is subtle.
- A valid-length set design is load-bearing: the set `{M, M-1} ∪ [2, M-3]`
  avoids lengths that would produce a trailing clue at position M-1 where the
  continuation letter would be out of bounds. Changing the set requires
  re-verifying the trailing-clue geometry.

### Different

- `GridGenerator` is now a thin orchestrator over the three phases. The
  interesting logic lives in `Skeleton`, `SlotPlanner`, and `SkeletonFiller`.
- The `enforceInterlocking` flag in `GridConstraints` is the seam between the
  old and new paths. The old path is kept for regression comparison and will be
  removed in a follow-up once the new path is confirmed stable in production.
