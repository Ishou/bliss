package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import kotlin.random.Random

private const val GENERATION_TIMEOUT_MS = 5_000L

class GridGenerator(
    private val repository: WordRepository,
) {
    fun generate(
        constraints: GridConstraints,
        random: Random = Random.Default,
        metrics: GenerationMetrics? = null,
    ): Grid? = generateInterlocked(constraints, random, metrics)

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
    ): Grid? {
        val w = constraints.width
        val h = constraints.height
        if (w < 2 || h < 2) return null
        val deadline = System.currentTimeMillis() + GENERATION_TIMEOUT_MS

        val skeletonStart = System.nanoTime()
        val arrows = Skeleton.arrows(w, h)
        metrics?.skeletonMs = (System.nanoTime() - skeletonStart) / 1_000_000

        val slotPlanStart = System.nanoTime()
        val slots =
            SlotPlanner.planVariable(arrows, w, h, random, deadline, metrics) ?: run {
                metrics?.slotPlanMs = (System.nanoTime() - slotPlanStart) / 1_000_000
                return null
            }
        metrics?.slotPlanMs = (System.nanoTime() - slotPlanStart) / 1_000_000
        if (slots.any { it.length < constraints.minWordLength }) return null

        val fillStart = System.nanoTime()
        val placements =
            SkeletonFiller(repository).fill(slots, random, deadline, metrics) ?: run {
                metrics?.fillMs = (System.nanoTime() - fillStart) / 1_000_000
                return null
            }
        metrics?.fillMs = (System.nanoTime() - fillStart) / 1_000_000
        // The planner + filler enforce the invariants `Grid.fromPlacements` checks
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
