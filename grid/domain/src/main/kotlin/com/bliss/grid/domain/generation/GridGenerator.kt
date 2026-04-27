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
     * backtracker exhausts [GridConstraints.maxAttempts] first or the
     * resulting grid fails the interlocking invariant.
     *
     * Every letter cell in a returned grid satisfies the interlocking
     * rule: interior cells belong to both a horizontal and a vertical
     * word; edge cells (row 0 or column 0) belong to at least one.
     */
    fun generate(
        constraints: GridConstraints,
        random: Random = Random.Default,
    ): Grid? {
        val working = WorkingGrid(constraints.width, constraints.height)
        val maxLength = maxOf(constraints.width, constraints.height) - 1
        val attempts = intArrayOf(0)
        if (!search(working, constraints, maxLength, mutableSetOf(), attempts, random)) return null
        return working.toGrid()
    }

    private fun search(
        working: WorkingGrid,
        constraints: GridConstraints,
        maxLength: Int,
        usedWords: MutableSet<String>,
        attempts: IntArray,
        random: Random,
    ): Boolean {
        if (working.density() >= constraints.targetDensity) {
            if (!constraints.enforceInterlocking || working.isFullyInterlocked()) return true
            // Density met but interlocking not — keep placing words.
        }
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
                    if (matches.isEmpty()) {
                        null
                    } else {
                        RankedCandidate(candidate, matches, pattern.size, working.uncrossedFixCount(candidate))
                    }
                }.sortedWith(
                    compareByDescending<RankedCandidate> { it.uncrossedFixes }
                        .thenByDescending { it.crossings }
                        .thenBy { it.matchCount },
                ).shuffledStable(random)

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

    private data class RankedCandidate(
        val candidate: CandidatePlacement,
        val matches: List<Word>,
        val crossings: Int,
        val uncrossedFixes: Int,
    ) {
        val matchCount: Int get() = matches.size
    }

    /**
     * Shuffles within equal-rank buckets, preserving the composite sort
     * (most uncrossed fixes, then crossings, then rarest first) that keeps
     * backtracking efficient. A plain [List.shuffled] would destroy that
     * ordering and explode the search.
     */
    private fun List<RankedCandidate>.shuffledStable(
        random: Random,
    ): List<Pair<CandidatePlacement, List<Word>>> =
        groupBy { Triple(-it.uncrossedFixes, -it.crossings, it.matchCount) }
            .toSortedMap(compareBy<Triple<Int, Int, Int>> { it.first }.thenBy { it.second }.thenBy { it.third })
            .values
            .flatMap { bucket -> bucket.shuffled(random).map { it.candidate to it.matches } }
}
