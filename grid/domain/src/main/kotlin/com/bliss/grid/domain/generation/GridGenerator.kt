package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import kotlin.random.Random

/**
 * Default per-attempt deadline. The outer retry loop in
 * `GeneratePuzzleUseCase` retries on failure with a fresh seed, so a tight
 * per-attempt budget paired with retries is usually faster than one long
 * attempt — the pathological 5s-tail puzzles abandon quickly and the next
 * seed often succeeds in <100ms.
 */
const val DEFAULT_GENERATION_TIMEOUT_MS = 5_000L

/**
 * Initial per-probe budget for the randomized-restart loop inside
 * [GridGenerator.generate], in **nanoseconds** (so sub-millisecond budgets
 * are expressible). Doubles per probe up to the per-attempt deadline. Default
 * = 200ms = 200 × 10⁶ ns; the doubling lets harder seeds get progressively
 * longer budgets within the overall deadline.
 */
private const val INITIAL_PROBE_BUDGET_NS: Long = 1_000L

private const val NS_PER_MS: Long = 1_000_000L

class GridGenerator(
    private val repository: WordRepository,
    private val clock: Clock = SystemClock,
    private val lengthPolicy: (Int) -> List<Int> =
        SlotPlanner.corpusAwareLengthPolicy(repository),
) {
    fun generate(
        constraints: GridConstraints,
        random: Random = Random.Default,
        metrics: GenerationMetrics? = null,
        timeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS,
        cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
    ): Grid? = generateInterlocked(constraints, random, metrics, timeoutMs, cooldownPolicy)

    /**
     * Generates a fully interlocked grid via the skeleton pipeline:
     *
     * 1. [Skeleton.arrows] produces the deterministic boundary clue layout
     *    (corner duals, top-row + left-col dual cells, single trailing clue
     *    when w/h is odd).
     * 2. [SlotPlanner.planVariable] picks per-slot lengths via backtracking
     *    over the valid set `{M, M-1} ∪ [2, M-3]`, placing trailing clue
     *    cells with continuation + perpendicular continuation arrows when
     *    a slot stops short of the grid edge.
     * 3. [SkeletonFiller] solves the resulting CSP — each slot gets a word
     *    consistent with the letters at intersection cells.
     */
    private fun generateInterlocked(
        constraints: GridConstraints,
        random: Random,
        metrics: GenerationMetrics?,
        timeoutMs: Long,
        cooldownPolicy: ClueCooldownPolicy,
    ): Grid? {
        val w = constraints.width
        val h = constraints.height
        if (w < 2 || h < 2) return null
        val deadlineNs = clock.nanoTime() + timeoutMs * NS_PER_MS

        val skeletonStart = clock.nanoTime()
        val arrows = Skeleton.arrows(w, h)
        metrics?.skeletonMs = (clock.nanoTime() - skeletonStart) / NS_PER_MS

        val searchStart = clock.nanoTime()
        val search =
            IntegratedSearch(
                repository = repository,
                cooldownPolicy = cooldownPolicy,
                clock = clock,
                lengthPolicy = lengthPolicy,
            )

        // Randomized restart with exponentially growing probe budget (nanos).
        // Each probe gets a sub-random derived from the caller's [random], so
        // the whole generation stays deterministic per input seed but explores
        // structurally different search trees per probe.
        var probeBudgetNs = INITIAL_PROBE_BUDGET_NS
        var probeIdx = 0
        var successState: PlanState? = null
        while (clock.nanoTime() < deadlineNs) {
            val probeDeadlineNs = minOf(deadlineNs, clock.nanoTime() + probeBudgetNs)
            val probeRandom = Random(random.nextLong() + probeIdx)
            val state = PlanState(w, h)
            for (arrow in arrows) {
                state.addClueCell(arrow.cluePosition)
                state.addArrow(arrow.cluePosition, arrow.direction)
            }
            if (search.solve(state, probeRandom, probeDeadlineNs, constraints.themeLimits, metrics)) {
                successState = state
                break
            }
            // 1.05× growth — slow geometric ramp. Empirically the best
            // bench trade-off at 0.01ms initial: many short probes catch
            // easy seeds fast; the slow ramp avoids committing to long
            // doomed probes before the deadline.
            probeBudgetNs = (probeBudgetNs * 21 / 20).coerceAtMost(timeoutMs * NS_PER_MS)
            probeIdx++
        }

        // The integrated search interleaves planning and filling, so we attribute
        // the entire wall-time to fillMs and leave slotPlanMs at 0 (no separate
        // planning phase exists anymore).
        metrics?.slotPlanMs = 0
        metrics?.fillMs = (clock.nanoTime() - searchStart) / NS_PER_MS
        val state = successState ?: return null
        if (state.placements.any { it.word.text.length < constraints.minWordLength }) return null
        val placements = state.placements

        // The integrated search enforces the invariants `Grid.fromPlacements` checks
        // (in-bounds, no duplicate words, no clue/letter overlap, consistent crossings).
        // Catch only `IllegalArgumentException` — what `require(...)` throws — so a real
        // programming bug (NPE, IndexOutOfBoundsException) propagates with its stack trace
        // instead of becoming a silent null.
        return try {
            val grid = Grid.fromPlacements(w, h, placements)
            metrics?.succeeded = true
            grid
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
