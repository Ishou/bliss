package com.bliss.grid.domain.validation

import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Word

sealed interface GridViolation {
    data class OutOfBounds(
        val position: Position,
        val gridWidth: Int,
        val gridHeight: Int,
    ) : GridViolation

    data class ClueCellLetterCellOverlap(
        val position: Position,
    ) : GridViolation

    data class InconsistentIntersection(
        val position: Position,
        val expected: Char,
        val actual: Char,
    ) : GridViolation

    data class DuplicateWord(
        val word: Word,
    ) : GridViolation

    data class OrphanedLetterCell(
        val position: Position,
    ) : GridViolation

    /**
     * A letter cell that does not satisfy the interlocking rule:
     * - interior cells (row > 0 and col > 0) must be in both a horizontal
     *   and a vertical word
     * - edge cells (row == 0 or col == 0) must be in at least one
     */
    data class UncrossedCell(
        val position: Position,
        val inHorizontal: Boolean,
        val inVertical: Boolean,
    ) : GridViolation
}
