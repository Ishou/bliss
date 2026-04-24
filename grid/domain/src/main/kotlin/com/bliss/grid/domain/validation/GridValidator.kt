package com.bliss.grid.domain.validation

import com.bliss.grid.domain.model.ClueCell
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position

class GridValidator {
    fun validate(grid: Grid): List<GridViolation> {
        val violations = mutableListOf<GridViolation>()
        violations += outOfBoundsCells(grid)
        violations += duplicateWords(grid)

        val expectedLetters = mutableMapOf<Position, Char>()
        val cluePositions = mutableSetOf<Position>()
        val letterPositions = mutableSetOf<Position>()

        for (placement in grid.placements) {
            cluePositions += placement.cluePosition
            for ((pos, ch) in placement.letterPositions()) {
                letterPositions += pos
                val existing = expectedLetters[pos]
                if (existing != null && existing != ch) {
                    violations += GridViolation.InconsistentIntersection(pos, expected = existing, actual = ch)
                } else {
                    expectedLetters[pos] = ch
                }
            }
        }

        for (pos in cluePositions intersect letterPositions) {
            violations += GridViolation.ClueCellLetterCellOverlap(pos)
        }

        for ((pos, cell) in grid.cells) {
            if (cell is LetterCell && pos !in letterPositions) {
                violations += GridViolation.OrphanedLetterCell(pos)
            }
            if (cell is ClueCell && pos in letterPositions) {
                violations += GridViolation.ClueCellLetterCellOverlap(pos)
            }
        }

        return violations.distinct()
    }

    private fun outOfBoundsCells(grid: Grid): List<GridViolation> =
        grid.cells.keys
            .filter { it.row.value !in 0 until grid.height || it.column.value !in 0 until grid.width }
            .map { GridViolation.OutOfBounds(it, grid.width, grid.height) }

    private fun duplicateWords(grid: Grid): List<GridViolation> {
        val seen = mutableSetOf<String>()
        val duplicates = mutableListOf<GridViolation>()
        for (placement in grid.placements) {
            if (!seen.add(placement.word.text)) {
                duplicates += GridViolation.DuplicateWord(placement.word)
            }
        }
        return duplicates
    }
}
