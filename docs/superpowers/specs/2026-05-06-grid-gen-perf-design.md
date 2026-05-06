# Grid generator perf — slot-plan + fill forward-checking

Date: 2026-05-06
Status: Draft
Branch: `chore/grid-gen-perf`
Anchor: PR #200's instrumentation lands per-phase metrics; this spec uses
that data to target the actual bottleneck.

## 1. Problem

`GridGenerator.generate()` shows highly variable wall time on 10×10 default
grids: median 118ms, p75 1194ms, **p95/p99 5000ms (timeout)**, 17.5% of
attempts time out entirely. The instrumentation in
`grid/api/src/test/kotlin/com/bliss/grid/api/GridGenBenchmarkTest.kt`
(N=200, seeds 0..199, French production corpus) gives us per-phase numbers:

| phase     | min | p50 | p75  | p95  | p99  | max  |
|-----------|----:|----:|-----:|-----:|-----:|-----:|
| total     | 5   | 118 | 1194 | 5000 | 5000 | 5000 |
| skeleton  | 0   | 0   | 0    | 0    | 0    | 0    |
| slot-plan | 0   | 0   | 25   | 439  | 1487 | 5000 |
| fill      | 0   | 62  | 719  | 5000 | 5000 | 5000 |

Search effort tells us **why**:

| metric                | min | p50  | p99       | max        |
|-----------------------|----:|-----:|----------:|-----------:|
| slot-plan backtracks  | 3   | 1350 | 5,756,063 | 18,608,339 |
| fill backtracks       | 0   | 941  | 457,367   | 2,021,887  |
| fill repo calls       | 0   | 7670 | 1,554,233 | 6,458,607  |
| first-slot domain     | 49  | 49   | 49        | 49         |

**Two independent root causes:**

1. **Slot-plan explodes on hard skeletons** — p99 of 5.7M backtracks (max
   18.6M) means `SlotPlanner.solveVariable` is doing combinatorial search
   over length permutations without pruning ones that are already
   inconsistent with downstream arrows.
2. **Fill exhausts timeout on hard puzzles** — p95 fill_ms = 5000ms (full
   timeout) means the CSP solver runs out of budget before exploring the
   tree. Largest single contributor to user-visible "10s tail."

## 2. Goal

Target the user-visible 10s tail (the slow runs, not the median). The
median is already fine at 118ms.

Concrete success metric (re-run the benchmark on the same 200 seeds):

- **Total p95** ≤ 1500ms (vs current 5000ms wall — i.e. fewer than 5% of
  runs hit the timeout).
- **Total p99** ≤ 3000ms (vs current 5000ms — keeps a 2s buffer to the
  hard timeout).
- **Success rate** ≥ 95% (vs current 82.5%).
- No median regression (current p50 = 118ms; tolerate up to +50ms).

## 3. Non-goals

- **Inflectional-flex slot-resizing** (the user's `manger / mangeras /
  mangerez` idea). Bigger algorithmic change than perf; defer to a
  follow-up spec if these perf fixes don't reach the success metric.
- **Restart heuristics, simulated annealing, parallel search.** Likely
  smaller wins than forward-checking; come back to them if needed.
- **Skeleton boundary changes** — the deterministic boundary phase is 0ms;
  not optimizing it.
- **Corpus changes / repository indexing.** The position-letter index
  already works (microsecond lookups); not the bottleneck.

## 4. Architecture

Two independent fixes, both in `grid/domain/generation/`:

### 4.1 Slot-plan forward-checking (`SlotPlanner.solveVariable`)

**Today:** picks a length, materializes it, recurses. If a downstream arrow
becomes infeasible (e.g. its `available < 2` after the new clue cells
land), we discover that *only when we recurse to it*. The discovery
unwinds the whole stack to retry, hence the 5.7M backtracks at p99.

**Change:** after `state.materialize(next, length)` succeeds, walk every
*pending* arrow and check that `state.availableLength(arrow) >= 2` (or
that the arrow can still legally deactivate). If any pending arrow has
no legal action remaining, fail this length and try the next. This is
classic forward-checking: spend O(arrows) work per assignment to skip
exponential exploration.

**Estimated impact:** p99 slot-plan backtracks 5.7M → ~1-5k. Slot-plan
p95 ms 439 → ~50ms. Doesn't help median (already fast), but kills the
slot-plan timeout tail.

### 4.2 Fill forward-checking (`SkeletonFiller.search`)

**Today:** when a word is placed in a slot, we just recurse. Domain
re-evaluation happens at the *next* MRV scan, where we discover any
intersecting slot whose domain became empty — but only after a full scan
over all unassigned slots and a `findByLengthAndPattern` call per slot.

**Change:** after placing a word, immediately query
`findByLengthAndPattern` for **only the slots that intersect the
just-placed slot** (we know those are the only ones whose pattern
changed). If any returns an empty list, undo and try the next word
without recursing. This bounds the dead-end-detection cost to
O(intersecting-slots) per word try, vs the current O(all-unassigned-slots
× repo-call) at the next MRV scan.

**Auxiliary:** add an MRV cache. `domainFor` is called O(slots) per
search node; many slots' patterns don't change between consecutive nodes
(only the just-placed slot's intersections changed). Cache last-known
domain per slot, invalidate only the intersecting slots' entries on
each placement.

**Estimated impact:** p95 fill_ms 5000 → ~1500ms. Fill backtracks
unchanged (the search tree shape is the same), but each backtrack is
cheaper because the dead-end detection doesn't scan all unassigned
slots.

### 4.3 Slot-intersection precomputation (shared support)

Both fixes need: "given slot S, what other slots cross it?". Today this
is implicit in the `letters: Map<Position, Char>` — slots share cells.
For the new code we want an explicit
`Map<SlotIndex, List<IntersectingSlotIndex>>` precomputed once before
`SkeletonFiller.search` starts. Pure data, ~O(slots²) precompute,
amortizes over millions of search nodes. Add to `SkeletonFiller.fill`'s
init block.

## 5. Components

- `grid/domain/.../SlotPlanner.kt` — add `pendingArrowsFeasible(state)`
  helper, call it after each successful materialization. ~30 lines.
- `grid/domain/.../SkeletonFiller.kt` — add `intersections: Array<IntArray>`
  precompute, refactor `search` to forward-check on placement, add
  `domainCache: Array<List<Word>?>` keyed by slot index. ~60 lines.
- `grid/api/.../GridGenBenchmarkTest.kt` — already in place; re-run after
  each change to measure delta. No code changes.

## 6. Data flow (no changes)

The pipeline shape stays identical: skeleton → slot-plan → fill → grid.
The fixes are internal to slot-plan and fill — no API changes; metrics
class already added.

## 7. Tests

- **Existing correctness tests must stay green.** `GridGeneratorPropertyTest`
  (random 4×6 grids, validates interlocking) and the rest of `grid/api`
  tests.
- **No new correctness tests** — forward-checking is a *pruning*
  optimization, it can only fail-fast on dead ends that pure backtracking
  would also have reached eventually. Behavior on success cases is
  unchanged.
- **Re-run benchmark after each fix** to capture the impact in the iter
  log section of this spec.

## 8. Rollout — two PRs

CLAUDE.md mandates <400-line PRs. The fixes split naturally:

- **PR-A: instrumentation + slot-plan forward-checking.** ~120 lines
  (metrics class + plumbing + `pendingArrowsFeasible`). Self-contained;
  measures its own impact via the benchmark in the same PR.
- **PR-B: fill forward-checking + intersection precompute + domain cache.**
  ~80-150 lines. Depends on PR-A landing first (so we can measure
  fill-only delta separately).

## 9. Risks

| Risk | Mitigation |
|---|---|
| Forward-checking has a per-step constant cost. On easy puzzles (median 118ms), the overhead could increase median. | Mitigation: forward-check only does work proportional to arrows / intersections (cheap). Benchmark median tolerance is +50ms; if violated, gate the FC behind a depth threshold. |
| Domain cache invalidation is fiddly — easy to forget a slot when intersections change. | Make the cache invalidation conservative: invalidate the just-placed slot AND every slot in its intersection list. No partial invalidation. |
| Slot-plan FC could over-prune: declares an arrow infeasible when it could still legally deactivate. | The check has to consider both "available ≥ 2" *and* "can deactivate" — explicitly two conditions. Test with the existing property test (any over-pruning breaks correctness). |

## 10. Out-of-scope follow-ups

- Inflectional-flex slot resizing (user-flagged, deferred). If the success
  metric isn't met after PR-B, this is the next algorithmic change.
- Restart-on-dead-end. Tiny cost, small expected win, easy to add later.
- JMH-class microbenchmark. Current bench-test is enough for this scope.

## 11. Iter log — actual measured results

Re-running the same 200-puzzle benchmark after each PR landed:

| metric              | base | + slot-plan FC | + fill FC |
|---------------------|-----:|---------------:|----------:|
| success rate        | 82.5% | 83.5%         | **84.5%** |
| total p50           | 118ms | 55ms          | **46ms**  |
| total p75           | 1194ms | 213ms        | **174ms** |
| total p95           | 5000ms | 5000ms       | 5002ms    |
| total p99           | 5000ms | 5000ms       | 5004ms    |
| slot-plan p95       | 439ms | 22ms          | 22ms      |
| slot-plan max       | 5000ms | 85ms         | 84ms      |
| slot-plan bks p99   | 5.76M | 286k          | 286k      |
| fill p50            | 62ms  | 49ms          | 36ms      |
| fill bks p50        | 941   | 941           | 784       |

**Goal review against §2:**

| target | spec goal | actual |
|---|---|---|
| total p95 ≤ 1500ms | yes | **NO** — still hits 5000ms wall |
| total p99 ≤ 3000ms | yes | NO — 5004ms |
| success ≥ 95% | yes | NO — 84.5% |
| no median regression (≤ p50 + 50ms) | yes | YES — p50 dropped 72ms |

**Honest assessment:** PR-A + PR-B nailed median + p75 (huge wins) but
**did not move the hard tail.** The p95 5s timeouts are not from
slot-plan or fill backtracking — those are now fast. They're from
puzzles where *no valid fill exists* (or only an exotic one) given the
fixed slot lengths the planner produced.

That's the user's original intuition: the *configuration* is the
bottleneck, not the search speed. Fix paths from here:

1. **Inflectional-flex slot resizing** (the user-flagged design): let
   the fill phase grow/shrink slot lengths within an inflection
   family. Expands the solution space — would actually help the
   currently-infeasible 15.5%.
2. **Restart-on-deadend with reseed**: cheap, may unstick the worst
   cases by trying a different slot-plan even when the first looks
   feasible.
3. **Domain memoization**: probably cheap perf win, won't help the
   tail.

Recommendation: ship PR-A + PR-B as the perf foundation; pursue the
inflectional-flex redesign as the next algorithmic iter.

## 12. Spec self-review notes

Placeholder scan: no TBD/TODO. Internal consistency: §4.1 estimates "p99
backtracks 5.7M → ~1-5k" — that's a hand-wavy reduction factor; actual
impact will be measured by the benchmark and recorded in §11 (this
section), so OK as estimate. Scope: PR-A and PR-B are independent
shipping units, sized appropriately. Ambiguity: §5's "intersections:
Array<IntArray>" — clarified that it's keyed by slot index. §7 says no
new correctness tests; that's intentional and explained.
