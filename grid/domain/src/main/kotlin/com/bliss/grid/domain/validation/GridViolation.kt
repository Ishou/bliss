package com.bliss.grid.domain.validation

import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Word

sealed interface GridViolation {
    data class OutOfBounds(val position: Position, val gridWidth: Int, val gridHeight: Int) : GridViolation
    data class ClueCellLetterCellOverlap(val position: Position) : GridViolation
    data class InconsistentIntersection(val position: Position, val expected: Char, val actual: Char) : GridViolation
    data class DuplicateWord(val word: Word) : GridViolation
    data class OrphanedLetterCell(val position: Position) : GridViolation
}
