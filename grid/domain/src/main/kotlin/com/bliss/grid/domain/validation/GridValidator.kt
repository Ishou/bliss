package com.bliss.grid.domain.validation

import com.bliss.grid.domain.model.ClueCell
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
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

        violations += blackTriples(grid)
        violations += closedClamps(grid)

        return violations.distinct()
    }

    /**
     * Detect every closed clamp on the grid. A clamp traps a length-2
     * slot between aligned black pairs; printed mots fléchés never use
     * the pattern (spec §4.1 C7). Reports each clamp once, keyed by its
     * top-left corner.
     */
    private fun closedClamps(grid: Grid): List<GridViolation.ClosedClamp> {
        val violations = mutableListOf<GridViolation.ClosedClamp>()
        // Vertical clamp: 3 rows x 2 cols, BB / .. / BB.
        for (r in 0 until grid.height - 2) {
            for (c in 0 until grid.width - 1) {
                if (isClue(grid, r, c) &&
                    isClue(grid, r, c + 1) &&
                    isLetter(grid, r + 1, c) &&
                    isLetter(grid, r + 1, c + 1) &&
                    isClue(grid, r + 2, c) &&
                    isClue(grid, r + 2, c + 1)
                ) {
                    violations +=
                        GridViolation.ClosedClamp(
                            topLeft = Position(Row(r), Column(c)),
                            axis = WordAxis.VERTICAL,
                        )
                }
            }
        }
        // Horizontal clamp: 2 rows x 3 cols, B.B / B.B.
        for (r in 0 until grid.height - 1) {
            for (c in 0 until grid.width - 2) {
                if (isClue(grid, r, c) &&
                    isLetter(grid, r, c + 1) &&
                    isClue(grid, r, c + 2) &&
                    isClue(grid, r + 1, c) &&
                    isLetter(grid, r + 1, c + 1) &&
                    isClue(grid, r + 1, c + 2)
                ) {
                    violations +=
                        GridViolation.ClosedClamp(
                            topLeft = Position(Row(r), Column(c)),
                            axis = WordAxis.HORIZONTAL,
                        )
                }
            }
        }
        return violations
    }

    private fun isClue(
        grid: Grid,
        r: Int,
        c: Int,
    ): Boolean = grid.cells[Position(Row(r), Column(c))] is ClueCell

    private fun isLetter(
        grid: Grid,
        r: Int,
        c: Int,
    ): Boolean = grid.cells[Position(Row(r), Column(c))] is LetterCell

    /**
     * Find every horizontal or vertical run of 3+ consecutive [ClueCell]s.
     * Reports the leftmost / topmost position of each maximal triple-run
     * once per axis (spec §4.1 C2).
     */
    private fun blackTriples(grid: Grid): List<GridViolation.BlackTriple> {
        val violations = mutableListOf<GridViolation.BlackTriple>()
        for (r in 0 until grid.height) {
            var run = 0
            var start = -1
            for (c in 0 until grid.width) {
                val isBlack = grid.cells[Position(Row(r), Column(c))] is ClueCell
                if (isBlack) {
                    if (run == 0) start = c
                    run++
                    if (run == 3) {
                        violations +=
                            GridViolation.BlackTriple(
                                start = Position(Row(r), Column(start)),
                                axis = WordAxis.HORIZONTAL,
                            )
                    }
                } else {
                    run = 0
                }
            }
        }
        for (c in 0 until grid.width) {
            var run = 0
            var start = -1
            for (r in 0 until grid.height) {
                val isBlack = grid.cells[Position(Row(r), Column(c))] is ClueCell
                if (isBlack) {
                    if (run == 0) start = r
                    run++
                    if (run == 3) {
                        violations +=
                            GridViolation.BlackTriple(
                                start = Position(Row(start), Column(c)),
                                axis = WordAxis.VERTICAL,
                            )
                    }
                } else {
                    run = 0
                }
            }
        }
        return violations
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
