package com.bliss.grid.domain.validation

import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordAxis

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

    /**
     * Two placements share the same dictionary headword (e.g. "COURT" and
     * "COURAIT" both lemma="COURIR"). The grid is structurally valid but
     * stylistically poor — mots-fléchés convention avoids placing multiple
     * inflections of the same verb / noun in the same puzzle.
     */
    data class DuplicateLemma(
        val lemma: String,
        val words: List<Word>,
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

    /**
     * Three or more consecutive clue (black) cells in a row or column.
     * Real mots fléchés never pack clue cells 3 or more in a line (spec
     * §4.1 C2) — pairs are fine and common, triples are visually heavy
     * and not used in printed grids.
     */
    data class BlackTriple(
        val start: Position,
        val axis: WordAxis,
    ) : GridViolation
}
