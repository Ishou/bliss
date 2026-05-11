# Design — Integrated grid generator

**Date:** 2026-05-11
**Status:** Proposed (awaiting user approval)
**Branch:** `feat/grid-gen-enhance`
**Companion plan:** `docs/superpowers/plans/2026-05-11-integrated-generator.md` (to be written by `writing-plans`).

## Context

The current generator runs in two phases:

1. **`SlotPlanner.planVariable`** commits to all slot lengths + trailing-clue
   positions, producing a `List<WordSlot>`.
2. **`SkeletonFiller.fill`** runs a CSP search to assign a word to every slot.

The Wave 1 25-gen benchmark (committed `670798a` on `feat/grid-gen-enhance`)
exposed a fundamental flaw: at the production constraints (15×12, min word
length 2), **0/25 puzzles succeed on the first attempt**. Inspection of the
CSV shows:

- `slot_plan_backtracks = 0` for every puzzle — the planner finds one plan
  immediately and commits.
- All 25 seeds produce the *same* plan (the planner is fully deterministic
  post-Wave-3 / Wave-4: corpus-aware lengths + MRV arrow ordering = pure
  function of grid dimensions).
- `fill_first_slot_domain = 524` on every puzzle, identical CSP.
- 70k+ filler backtracks per puzzle — the search makes progress but cannot
  exhaust the tree in 5s.

**Root cause**: `SlotPlanner` commits to slot lengths without ever validating
they are *collectively* fillable. The bench's single attempt then watches the
filler confirm infeasibility for 5 wall-time seconds. Production absorbs this
via the use case's retry-with-fresh-seed loop (different seeds → different
filler head-shuffles → different search trees), so the user-facing success
rate is non-zero, but the underlying per-attempt success rate is.

## Goals

- Replace the two-phase planner/filler with a single integrated search that
  commits `(arrow, length, word)` atomically at every step. No plan ever
  exists that hasn't been proven extensible to a full grid.
- Carry over the Wave 1–4 improvements (corpus-aware length policy, MRV
  ordering on the arrow choice, Clock port, tightened cooldown, lazy
  `WordSlot.letterPositions`). Don't lose ground.
- Plan diversity emerges naturally from word-choice randomness (different
  words → different intersection letters → different downstream length
  viability → different trailing-clue placements). No explicit
  "plan-diversity" knob needed.
- Eliminate the bench's "0/25 first-attempt success" finding at 15×12.

## Non-goals

- **AC-3 / arc consistency** beyond one-hop forward-check. Defer to a future
  wave; the integrated commit-as-you-go pattern already removes the largest
  source of wasted work (infeasible plans).
- **Conflict-directed backjumping.** Chronological backtracking is the
  baseline; revisit only if the bench shows it's the next bottleneck.
- **Skeleton structural variability** (the original Wave 7). Boundary
  arrows remain a pure function of `(width, height)` via `Skeleton.arrows`.
- **Coexistence with the old generator.** Old code (`SkeletonFiller`,
  `SlotPlanner.planVariable`, `SlotPlanner.solveVariable`) and its
  test-side companions are deleted in the same wave — no
  feature-flag / dead-code path.

## Architecture

### Component layout (after the wave)

| File | Disposition |
|---|---|
| `Skeleton.kt` | unchanged — boundary arrow generation |
| `PlanState.kt` | extended: adds `letters: HashMap<Position, Char>`, `placements: ArrayList<WordPlacement>`, `commit(arrow, length, word, clue)`, `patternFor(arrow, length)` |
| `SlotPlanner.kt` | slimmed to a utility: `validLengths`, `corpusAwareLengthPolicy`, `orphanSafeLengths` stay; `planVariable`, `solveVariable`, `planFullLength` deleted |
| `SkeletonFiller.kt` | **deleted** in full |
| `IntegratedSearch.kt` | **new** — the unified backtracking search |
| `GridGenerator.kt` | calls `IntegratedSearch.solve` instead of `planVariable` + `SkeletonFiller.fill` |

All files stay inside `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/`.

### Layer guarantees

`IntegratedSearch` is `internal` to the `generation` package, like the
previous `SkeletonFiller`. The public surface stays `GridGenerator`.
`PlanState` remains `internal`. No new public types, no new cross-context
imports, no new dependencies. Konsist architecture tests pass as-is.

## State model — `PlanState` extension

Current fields:

```kotlin
private val cellType: Array<Array<CellType?>>
private val arrowState: LinkedHashMap<Position, LinkedHashMap<Direction, ArrowState>>
private val letterMembership: HashMap<Position, MutableSet<Int>>
private val _slots: ArrayList<WordSlot>
private val undo: ArrayDeque<() -> Unit>
```

Added fields (all rolled back through the existing undo log on `rollback(cp)`):

```kotlin
private val _letters: HashMap<Position, Char> = HashMap()
val letters: Map<Position, Char> get() = _letters

private val _placements: ArrayList<WordPlacement> = ArrayList()
val placements: List<WordPlacement> get() = _placements

private val _usedWords: HashSet<String> = HashSet()
val usedWords: Set<String> get() = _usedWords

private val _usedLemmas: HashSet<String> = HashSet()
val usedLemmas: Set<String> get() = _usedLemmas

private val _themeUsed: HashMap<String, Int> = HashMap()
val themeUsed: Map<String, Int> get() = _themeUsed
```

Centralising dedup + theme state in `PlanState` means the integrated
search doesn't manage parallel mutable structures. Every search-side
mutation goes through `commit(...)` which pushes inverse operations
onto the existing undo log; `rollback(cp)` cleans up letters,
placements, used-words/lemmas, theme counters in one pass.

New mutation method:

```kotlin
/**
 * Atomically commit (arrow, length, word, clue) — the integrated search's
 * one and only structural-mutation step.
 *
 * Performs everything materialize() did plus:
 *  - Writes word.text[i] into letters at each slot position; fails if any
 *    position already has a different letter (CSP conflict).
 *  - Records WordPlacement(word, arrow.cluePosition, arrow.direction, clue).
 *
 * On any failure (letter conflict, trailing-clue lands on letter, etc.),
 * returns false. Partial mutations remain in the undo log; caller MUST
 * checkpoint() before commit() and rollback(cp) on false.
 *
 * On success returns true; the slot is now MATERIALIZED in arrowState,
 * its trailing clue (if any) is PENDING via continuation arrows, and
 * letters/placements are updated.
 */
fun commit(
    arrow: ClueArrow,
    length: Int,
    word: Word,
    clue: WordClue,
): Boolean
```

New read methods:

```kotlin
/** Build the pattern (position-index → known letter) for a candidate slot. */
fun patternFor(arrow: ClueArrow, length: Int): Map<Int, Char>
```

The existing `materialize` is **kept** internally (used by `commit`); the
public interface narrows to `commit` for the new search. `planVariable`'s
removal eliminates the only call site of `materialize` from outside, so we
can make it `private` after deleting `planVariable`.

## Search loop — `IntegratedSearch`

```kotlin
internal class IntegratedSearch(
    private val repository: WordRepository,
    private val cooldownPolicy: ClueCooldownPolicy,
    private val clock: Clock,
    private val lengthPolicy: (Int) -> List<Int>,
) {
    fun solve(
        state: PlanState,
        random: Random,
        deadline: Long,
        themeLimits: Map<String, Int>,
        metrics: GenerationMetrics? = null,
    ): Boolean {
        if (clock.currentTimeMillis() > deadline) return false

        val next = state.nextPendingMRV(lengthPolicy) ?: run {
            return state.validate().ok
        }

        val available = state.availableLength(next)

        // Arrow can't fit a 2+ letter word — only option is to deactivate it.
        if (available < 2) {
            val cp = state.checkpoint()
            state.deactivate(next.cluePosition, next.direction)
            if (!state.hasDeadCluesNow() && solve(state, random, deadline, themeLimits, metrics)) {
                return true
            }
            state.rollback(cp)
            metrics?.let { it.slotPlanBacktracks++ }
            return false
        }

        // Length choices — strict descending (longer words preferred).
        val lengths = SlotPlanner.orphanSafeLengths(next, available, lengthPolicy).sortedDescending()
        for (length in lengths) {
            val pattern = state.patternFor(next, length)
            metrics?.let { it.fillRepoCalls++ }
            val matches = repository.findByLengthAndPattern(length, pattern)
            val candidates = filterCandidates(matches, state, themeLimits)
            if (candidates.isEmpty()) continue

            for (word in headShuffle(candidates, random)) {
                val clue = pickClue(word, state.themeUsed, themeLimits, random)
                val cp = state.checkpoint()
                if (state.commit(next, length, word, clue) && !state.hasDeadCluesNow()) {
                    if (solve(state, random, deadline, themeLimits, metrics)) return true
                }
                state.rollback(cp)
                metrics?.let { it.fillBacktracks++ }
            }
        }
        return false
    }
}
```

(Helpers `filterCandidates`, `pickClue`, `headShuffle` are moved verbatim
from the deleted `SkeletonFiller`; only the search loop is new.)

### Heuristics summary

| Choice | Strategy | Source |
|---|---|---|
| Arrow ordering | MRV via `PlanState.nextPendingMRV(lengthPolicy)` | Wave 4 carryover |
| Length ordering | Strict descending — longest first (user's explicit preference: *"favor words from longer lengths >>> shorter lengths"*) | Wave 3 length policy + sort |
| Word ordering | Frequency-descending with head-shuffle (HEAD_SHUFFLE_SIZE constant) | Existing `SkeletonFiller.headShuffle` |
| Length filtering | `orphanSafeLengths(arrow, available, lengthPolicy)` applies corner-orphan safety; `lengthPolicy` applies corpus-density filter | Wave 3 + existing helper |
| Word filtering | text/lemma dedup + theme cap + cooldown-clue availability | Wave 2/3 carryover |
| Forward-check | After commit: `hasDeadCluesNow()` + structural conflicts caught in `commit` itself; no separate intersection re-eval (commit + immediate-fail-or-recurse subsumes it) | New / simplified |

## Data flow

```
GridGenerator.generate(constraints, random, metrics, timeoutMs, cooldownPolicy):
  1. arrows = Skeleton.arrows(w, h)         [unchanged]
  2. state = PlanState(w, h)
  3. for each arrow:
       state.addClueCell(arrow.cluePosition)
       state.addArrow(arrow.cluePosition, arrow.direction)
  4. search = IntegratedSearch(repository, cooldownPolicy, clock, lengthPolicy)
  5. success = search.solve(state, random, deadline, constraints.themeLimits, metrics)
  6. if success:
       Grid.fromPlacements(w, h, state.placements)
     else:
       null

IntegratedSearch.solve(state, random, deadline, themeLimits, metrics):
  Recursive backtracking. Each frame:
    a. deadline check
    b. pick next arrow via MRV (using lengthPolicy)
    c. if no arrow pending → validate() → success/fail
    d. available < 2 → deactivate-or-backtrack
    e. for length in lengths.sortedDescending():
         pattern = state.patternFor(arrow, length)
         for word in filterCandidates(repo.findByLengthAndPattern(length, pattern)):
           pick clue, checkpoint, commit
           if hasDeadCluesNow → rollback + try next word
           else → recurse → on true return true; on false rollback + try next
       fall through: return false (this arrow has no working (length, word))
```

## Error handling

- **`PlanState.commit` returns false** on structural conflict (letter cell
  would land on existing clue, trailing clue would land on existing letter,
  letter conflict at intersection). Caller checkpoints before, rolls back on
  false.
- **Empty candidate list** for `(length, pattern)`: try next length. Not a
  failure — just no commitment to make at this length.
- **Deadline exceeded**: return false from any frame. The recursion unwinds
  cleanly via the undo log.
- **Grid.fromPlacements throws `IllegalArgumentException`**: caught in
  `GridGenerator.generate` (existing pattern, preserves real bugs as
  uncaught exceptions).

## Migration — deletions

The following files are **deleted** in full as part of this wave:

- `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SkeletonFiller.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerTest.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerCooldownTest.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerThemeTest.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SlotPlannerVariableTest.kt`

Tests retained:

- `GridGeneratorTest`, `GridGeneratorPropertyTest` — behavioural / property
  tests at the public-API level. Survive untouched.
- `SkeletonTest` — pure boundary-arrow tests. Survive.
- `PlanStateTest` — extended with tests for the new `commit`,
  `patternFor`, `letters`, `placements` surfaces.
- `SlotPlannerTest` — narrowed: tests for `validLengths`,
  `corpusAwareLengthPolicy`, `orphanSafeLengths` survive. Tests for the
  deleted `planVariable` / `planFullLength` removed.
- All `grid/application` tests — survive (call sites unchanged).

## Testing strategy

| Concern | Test type | File |
|---|---|---|
| `commit` atomicity (success, conflict, rollback) | Unit | `PlanStateTest` (extended) |
| `patternFor` correctness | Unit | `PlanStateTest` (extended) |
| `IntegratedSearch.solve` happy path on tiny in-memory corpus | Unit | new `IntegratedSearchTest.kt` |
| `IntegratedSearch.solve` deadline propagation | Unit (with `Clock` fake) | new `IntegratedSearchTest.kt` |
| Theme caps + cooldown still respected end-to-end | Unit | new `IntegratedSearchTest.kt` |
| `GridGenerator` returns valid grid on 5×5 with `AlwaysMatchingRepository` | Existing | `GridGeneratorTest` |
| Property: returned grid satisfies `Grid.fromPlacements` invariants | Existing | `GridGeneratorPropertyTest` |
| Bench: 25-gen `success_rate` improves over baseline | Manual | `GridGenBenchmarkTest` |

### `IntegratedSearchTest` contracts

- **Happy path**: 3×3 grid with in-memory corpus of carefully-chosen 2/3-letter words returns a valid placement set.
- **Deadline**: with a `FixedClock` that advances past deadline mid-search, returns false promptly.
- **Length descending**: with a corpus that offers words at multiple valid lengths, the placement chosen is from the longest length that has any fit.
- **Theme cap**: with `themeLimits = {"compass": 0}`, a word whose only clue is themed `"compass"` is never placed.
- **Cooldown**: with all clues of a word on cooldown, the word is excluded from the search domain.
- **Atomic commit failure**: when a candidate word's letter at position p conflicts with a previously-committed letter, the commit fails and the search tries the next word.

## Exit criteria

This wave is "done" when:

1. Old code (`SkeletonFiller`, `SlotPlanner.planVariable`, deleted tests) no
   longer exists on `feat/grid-gen-enhance`.
2. `IntegratedSearch.kt` + extended `PlanState` + updated `GridGenerator`
   exist.
3. `./gradlew :grid:domain:test :grid:application:test :grid:api:build`
   is green (excluding `@Tag("bench")`).
4. The 25-gen fast bench is run on the wave's tip; **`success_rate`
   strictly exceeds 0/25**. (Concrete target: ≥5/25 first-attempt success
   at 15×12. If the bench still shows 0/25, the design assumption
   "integrated commit gives plan diversity" is wrong and the wave is
   blocked pending re-design.)
5. Spotless clean, Konsist arch tests pass.

## Open questions

None — the user has approved Approach 1 with strict-descending length
preference. All other heuristic / structural choices are documented
above.

## References

- Self-critique conversation (this branch).
- Wave 1 spec: `docs/superpowers/specs/2026-05-11-grid-gen-wave1-observability-design.md`.
- Wave 1–5 commits on `feat/grid-gen-enhance` (observability, Clock,
  cooldown, validLengths, corpus-aware, MRV, letterPositions memoization).
- Manifesto rules on PR size, conventional commits, dead-code deletion:
  `CLAUDE.md`.
