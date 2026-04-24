package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.WordPlacement

class GridGenerator(private val repository: WordRepository) {

    fun generate(constraints: GridConstraints): Grid? {
        val working = WorkingGrid(constraints.width, constraints.height)
        val maxLength = maxOf(constraints.width, constraints.height) - 1
        val attempts = intArrayOf(0)
        return if (search(working, constraints, maxLength, mutableSetOf(), attempts)) working.toGrid() else null
    }

    private fun search(
        working: WorkingGrid,
        constraints: GridConstraints,
        maxLength: Int,
        usedWords: MutableSet<String>,
        attempts: IntArray,
    ): Boolean {
        if (working.density() >= constraints.targetDensity) return true
        if (attempts[0]++ >= constraints.maxAttempts) return false

        val ranked = working.candidatePlacements(constraints.minWordLength, maxLength)
            .mapNotNull { candidate ->
                val pattern = working.patternAt(candidate.cluePosition, candidate.direction, candidate.length)
                val matches = repository.findByLengthAndPattern(candidate.length, pattern)
                    .filter { it.text !in usedWords }
                if (matches.isEmpty()) null else candidate to matches
            }
            .sortedBy { it.second.size }

        for ((candidate, matches) in ranked) {
            for (word in matches) {
                val placement = WordPlacement(word, candidate.cluePosition, candidate.direction)
                if (working.place(placement)) {
                    usedWords += word.text
                    if (search(working, constraints, maxLength, usedWords, attempts)) return true
                    usedWords -= word.text
                    working.undo(placement)
                }
            }
        }
        return false
    }
}
