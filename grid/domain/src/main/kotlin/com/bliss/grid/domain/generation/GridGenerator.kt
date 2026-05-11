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
        val deadline = clock.currentTimeMillis() + timeoutMs

        val skeletonStart = clock.nanoTime()
        val arrows = Skeleton.arrows(w, h)
        metrics?.skeletonMs = (clock.nanoTime() - skeletonStart) / 1_000_000

        val searchStart = clock.nanoTime()
        val state = PlanState(w, h)
        for (arrow in arrows) {
            state.addClueCell(arrow.cluePosition)
            state.addArrow(arrow.cluePosition, arrow.direction)
        }
        val search =
            IntegratedSearch(
                repository = repository,
                cooldownPolicy = cooldownPolicy,
                clock = clock,
                lengthPolicy = lengthPolicy,
            )
        val ok = search.solve(state, random, deadline, constraints.themeLimits, metrics)
        // The integrated search interleaves planning and filling, so we attribute
        // the entire wall-time to fillMs and leave slotPlanMs at 0 (no separate
        // planning phase exists anymore).
        metrics?.slotPlanMs = 0
        metrics?.fillMs = (clock.nanoTime() - searchStart) / 1_000_000
        if (!ok) return null
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
