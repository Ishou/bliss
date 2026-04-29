package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import kotlin.random.Random

private const val GENERATION_TIMEOUT_MS = 5_000L

class GridGenerator(
    private val repository: WordRepository,
) {
    fun generate(
        constraints: GridConstraints,
        random: Random = Random.Default,
    ): Grid? =
        if (constraints.enforceInterlocking) {
            generateInterlocked(constraints, random)
        } else {
            generateGreedy(constraints, random)
        }

    // ── Interlocked generation: skeleton + slot planner + filler ────

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
    ): Grid? {
        val w = constraints.width
        val h = constraints.height
        if (w < 2 || h < 2) return null
        val deadline = System.currentTimeMillis() + GENERATION_TIMEOUT_MS

        val arrows = Skeleton.arrows(w, h)
        val slots = SlotPlanner.planVariable(arrows, w, h, random, deadline) ?: return null
        if (slots.any { it.length < constraints.minWordLength }) return null

        val placements = SkeletonFiller(repository).fill(slots, random, deadline) ?: return null
        // The planner + filler enforce the invariants `Grid.fromPlacements` checks
        // (in-bounds, no duplicate words, no clue/letter overlap, consistent crossings).
        // Catch only `IllegalArgumentException` — what `require(...)` throws — so a real
        // programming bug (NPE, IndexOutOfBoundsException) propagates with its stack trace
        // instead of becoming a silent null.
        return try {
            Grid.fromPlacements(w, h, placements)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ── Greedy search (no interlocking guarantee) ──────────────────

    private fun generateGreedy(
        constraints: GridConstraints,
        random: Random,
    ): Grid? {
        val working = WorkingGrid(constraints.width, constraints.height)
        val maxLength = maxOf(constraints.width, constraints.height) - 1
        val attempts = intArrayOf(0)
        if (!searchGreedy(working, constraints, maxLength, mutableSetOf(), attempts, random)) return null
        return working.toGrid()
    }

    private fun searchGreedy(
        working: WorkingGrid,
        constraints: GridConstraints,
        maxLength: Int,
        usedWords: MutableSet<String>,
        attempts: IntArray,
        random: Random,
    ): Boolean {
        if (working.density() >= constraints.targetDensity) return true
        if (attempts[0]++ >= constraints.maxAttempts) return false

        val ranked =
            working
                .candidatePlacements(constraints.minWordLength, maxLength)
                .mapNotNull { candidate ->
                    val pattern = working.patternAt(candidate.cluePosition, candidate.direction, candidate.length)
                    val matches =
                        repository
                            .findByLengthAndPattern(candidate.length, pattern)
                            .filter { it.text !in usedWords }
                    if (matches.isEmpty()) null else candidate to matches
                }.sortedBy { it.second.size }
                .shuffledStableGreedy(random)

        for ((candidate, matches) in ranked) {
            // Repository returns matches in frequency-descending order. We want
            // common words tried first (fewer backtracks), with light randomization
            // for puzzle variety — shuffle only the high-frequency head.
            val headSize = HEAD_SHUFFLE_SIZE.coerceAtMost(matches.size)
            val ordering =
                if (headSize <= 1) matches else matches.subList(0, headSize).shuffled(random) + matches.subList(headSize, matches.size)
            for (word in ordering) {
                val placement = WordPlacement(word, candidate.cluePosition, candidate.direction)
                if (working.place(placement)) {
                    usedWords += word.text
                    if (searchGreedy(working, constraints, maxLength, usedWords, attempts, random)) return true
                    usedWords -= word.text
                    working.undo(placement)
                }
            }
        }
        return false
    }

    private fun List<Pair<CandidatePlacement, List<Word>>>.shuffledStableGreedy(
        random: Random,
    ): List<Pair<CandidatePlacement, List<Word>>> =
        groupBy { it.second.size }
            .toSortedMap()
            .values
            .flatMap { bucket -> bucket.shuffled(random) }
}
