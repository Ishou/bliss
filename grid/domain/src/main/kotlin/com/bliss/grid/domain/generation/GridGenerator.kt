package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.WordPlacement

class GridGenerator(private val repository: WordRepository) {

    fun generate(constraints: GridConstraints): Grid? {
        val working = WorkingGrid(constraints.width, constraints.height)
        val maxLength = maxOf(constraints.width, constraints.height) - 1
        return if (search(working, constraints, maxLength)) working.toGrid() else null
    }

    private fun search(working: WorkingGrid, constraints: GridConstraints, maxLength: Int): Boolean {
        if (working.density() >= constraints.targetDensity) return true

        val usedWords = working.placements.map { it.word.text }.toHashSet()
        val ranked = working.candidatePlacements(constraints.minWordLength, maxLength)
            .mapNotNull { candidate ->
                val pattern = working.patternAt(candidate.cluePosition, candidate.direction, candidate.length)
                val matches = repository.findByLengthAndPattern(candidate.length, pattern)
                    .filter { it.text !in usedWords }
                if (matches.isEmpty()) null else candidate to matches
            }
            .sortedBy { it.second.size }

        if (ranked.isEmpty()) return false

        val (candidate, matches) = ranked.first()
        for (word in matches) {
            val placement = WordPlacement(word, candidate.cluePosition, candidate.direction)
            if (working.place(placement)) {
                if (search(working, constraints, maxLength)) return true
                working.undo(placement)
            }
        }
        return false
    }
}
