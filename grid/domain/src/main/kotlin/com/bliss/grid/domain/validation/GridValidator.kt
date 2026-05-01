package com.bliss.grid.domain.validation

import com.bliss.grid.domain.model.ClueCell
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.WordAxis

class GridValidator {
    fun validate(
        grid: Grid,
        enforceInterlocking: Boolean = false,
    ): List<GridViolation> {
        val violations = mutableListOf<GridViolation>()
        violations += outOfBoundsCells(grid)
        violations += duplicateViolations(grid)

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

        if (enforceInterlocking) {
            violations += uncrossedCells(grid)
        }

        return violations.distinct()
    }

    companion object {
        /**
         * Returns the list of letter-cell positions that violate the
         * interlocking rule:
         * - interior cells (row > 0 and col > 0): must be in both a
         *   horizontal AND a vertical word
         * - edge cells (row == 0 or col == 0): must be in at least one
         */
        fun uncrossedCells(grid: Grid): List<GridViolation.UncrossedCell> {
            val horizontalPositions = mutableSetOf<Position>()
            val verticalPositions = mutableSetOf<Position>()
            for (placement in grid.placements) {
                val positions = placement.letterPositions().map { it.first }
                when (placement.direction.axis) {
                    WordAxis.HORIZONTAL -> horizontalPositions.addAll(positions)
                    WordAxis.VERTICAL -> verticalPositions.addAll(positions)
                }
            }

            val violations = mutableListOf<GridViolation.UncrossedCell>()
            for ((pos, cell) in grid.cells) {
                if (cell !is LetterCell) continue
                val inH = pos in horizontalPositions
                val inV = pos in verticalPositions
                val isEdge = pos.row.value == 0 || pos.column.value == 0
                val valid = if (isEdge) inH || inV else inH && inV
                if (!valid) {
                    violations += GridViolation.UncrossedCell(pos, inHorizontal = inH, inVertical = inV)
                }
            }
            return violations
        }
    }

    private fun outOfBoundsCells(grid: Grid): List<GridViolation> =
        grid.cells.keys
            .filter { it.row.value !in 0 until grid.height || it.column.value !in 0 until grid.width }
            .map { GridViolation.OutOfBounds(it, grid.width, grid.height) }

    private fun duplicateViolations(grid: Grid): List<GridViolation> {
        val seen = mutableSetOf<String>()
        val byLemma = mutableMapOf<String, MutableList<com.bliss.grid.domain.model.Word>>()
        val duplicates = mutableListOf<GridViolation>()
        for (placement in grid.placements) {
            if (!seen.add(placement.word.text)) {
                duplicates += GridViolation.DuplicateWord(placement.word)
            }
            // Track lemma-level duplicates separately. A surface-form duplicate
            // is also a lemma duplicate, but the latter is reported per-lemma
            // (one violation listing every offending word) instead of one per
            // collision so callers can render "couru/courait/courrons" as a
            // single grouping rather than three repeated entries.
            byLemma.getOrPut(placement.word.lemma) { mutableListOf() }.add(placement.word)
        }
        for ((lemma, words) in byLemma) {
            if (words.distinctBy { it.text }.size > 1) {
                duplicates += GridViolation.DuplicateLemma(lemma, words.toList())
            }
        }
        return duplicates
    }
}
