package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import kotlin.random.Random

class GridGenerator(
    private val repository: WordRepository,
) {
    /**
     * Generates a grid satisfying [constraints], or returns `null` if the
     * backtracker exhausts [GridConstraints.maxAttempts] first.
     *
     * [random] makes generation non-deterministic per call: with the same
     * inputs but different sources, output grids differ. The route handler
     * passes a fresh [Random] per HTTP request so refreshing the puzzle URL
     * yields a new layout. Tests pass `Random(seed)` for reproducibility.
     *
     * Candidate placements and matching words are shuffled at every search
     * frame — not sorted-then-shuffled — to preserve the rarest-first
     * heuristic (`sortedBy { it.second.size }`) that keeps backtracking
     * tractable on a 10×10 grid against a ~120-word list.
     */
    fun generate(
        constraints: GridConstraints,
        random: Random = Random.Default,
    ): Grid? {
        val working = WorkingGrid(constraints.width, constraints.height)
        val maxLength = maxOf(constraints.width, constraints.height) - 1
        val attempts = intArrayOf(0)
        return if (search(working, constraints, maxLength, mutableSetOf(), attempts, random)) working.toGrid() else null
    }

    private fun search(
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
                .shuffledStable(random)

        for ((candidate, matches) in ranked) {
            for (word in matches.shuffled(random)) {
                val placement = WordPlacement(word, candidate.cluePosition, candidate.direction)
                if (working.place(placement)) {
                    usedWords += word.text
                    if (search(working, constraints, maxLength, usedWords, attempts, random)) return true
                    usedWords -= word.text
                    working.undo(placement)
                }
            }
        }
        return false
    }

    /**
     * Shuffles within equal-rank buckets, preserving the rarest-first sort
     * that keeps backtracking efficient. A plain [List.shuffled] would
     * destroy that ordering and explode the search.
     */
    private fun List<Pair<CandidatePlacement, List<Word>>>.shuffledStable(random: Random): List<Pair<CandidatePlacement, List<Word>>> =
        groupBy { it.second.size }
            .toSortedMap()
            .values
            .flatMap { bucket -> bucket.shuffled(random) }
}
