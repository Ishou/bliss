package com.bliss.grid.domain.validation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import com.bliss.grid.domain.model.Cell
import com.bliss.grid.domain.model.ClueCell
import com.bliss.grid.domain.model.Clue
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.Test

class GridValidatorTest {

    private val validator = GridValidator()

    @Test
    fun `valid grid yields no violations`() {
        val placement = WordPlacement(
            Word("OR", "metal"),
            Position(Row(0), Column(0)),
            Direction.RIGHT,
        )
        val grid = Grid.fromPlacements(width = 3, height = 1, placements = listOf(placement))

        assertThat(validator.validate(grid)).isEmpty()
    }

    @Test
    fun `out of bounds cell is reported`() {
        val cells: Map<Position, Cell> = mapOf(
            Position(Row(5), Column(5)) to LetterCell('A'),
        )
        val grid = Grid(width = 3, height = 3, cells = cells, placements = emptyList())

        assertThat(validator.validate(grid)).contains(
            GridViolation.OutOfBounds(Position(Row(5), Column(5)), gridWidth = 3, gridHeight = 3)
        )
    }

    @Test
    fun `clue and letter cell overlap is reported`() {
        // Build placements that together would put a clue and a letter at the same position.
        val letterPlacement = WordPlacement(
            Word("OR", "x"),
            Position(Row(0), Column(0)),
            Direction.RIGHT,
        )
        val cluePlacement = WordPlacement(
            Word("AS", "y"),
            cluePosition = Position(Row(0), Column(1)),
            direction = Direction.DOWN,
        )
        val cells: Map<Position, Cell> = mapOf(
            Position(Row(0), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
            Position(Row(0), Column(1)) to LetterCell('O'),
            Position(Row(0), Column(2)) to LetterCell('R'),
            Position(Row(1), Column(1)) to LetterCell('A'),
            Position(Row(2), Column(1)) to LetterCell('S'),
        )
        val grid = Grid(
            width = 4, height = 4,
            cells = cells,
            placements = listOf(letterPlacement, cluePlacement),
        )

        assertThat(validator.validate(grid)).contains(
            GridViolation.ClueCellLetterCellOverlap(Position(Row(0), Column(1)))
        )
    }

    @Test
    fun `inconsistent intersecting letters are reported`() {
        // Two placements claim different letters at (1,2).
        val horizontal = WordPlacement(Word("CHAT", "x"), Position(Row(1), Column(0)), Direction.RIGHT)
        val vertical = WordPlacement(Word("ZAZA", "y"), Position(Row(0), Column(2)), Direction.DOWN)

        // Construct cells that match `horizontal` (so the grid stays internally coherent
        // letter-wise at (1,2) = H), but `vertical` says it should be Z.
        val cells: Map<Position, Cell> = mapOf(
            Position(Row(1), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
            Position(Row(0), Column(2)) to ClueCell(listOf(Clue("y", Direction.DOWN))),
            Position(Row(1), Column(1)) to LetterCell('C'),
            Position(Row(1), Column(2)) to LetterCell('H'),
            Position(Row(1), Column(3)) to LetterCell('A'),
            Position(Row(1), Column(4)) to LetterCell('T'),
            Position(Row(2), Column(2)) to LetterCell('A'),
            Position(Row(3), Column(2)) to LetterCell('Z'),
            Position(Row(4), Column(2)) to LetterCell('A'),
        )
        val grid = Grid(
            width = 6, height = 6,
            cells = cells,
            placements = listOf(horizontal, vertical),
        )

        val violations = validator.validate(grid)
        assertThat(violations).contains(
            GridViolation.InconsistentIntersection(Position(Row(1), Column(2)), expected = 'H', actual = 'Z')
        )
    }

    @Test
    fun `duplicate words are reported`() {
        val a = WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT)
        val b = WordPlacement(Word("OR", "x"), Position(Row(2), Column(0)), Direction.RIGHT)
        val cells: Map<Position, Cell> = mapOf(
            Position(Row(0), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
            Position(Row(0), Column(1)) to LetterCell('O'),
            Position(Row(0), Column(2)) to LetterCell('R'),
            Position(Row(2), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
            Position(Row(2), Column(1)) to LetterCell('O'),
            Position(Row(2), Column(2)) to LetterCell('R'),
        )
        val grid = Grid(width = 4, height = 4, cells = cells, placements = listOf(a, b))

        assertThat(validator.validate(grid)).contains(GridViolation.DuplicateWord(Word("OR", "x")))
    }

    @Test
    fun `orphaned letter cells are reported`() {
        // Cell map has a LetterCell at (2,2) that no placement covers.
        val placement = WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT)
        val cells: Map<Position, Cell> = mapOf(
            Position(Row(0), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
            Position(Row(0), Column(1)) to LetterCell('O'),
            Position(Row(0), Column(2)) to LetterCell('R'),
            Position(Row(2), Column(2)) to LetterCell('Z'),
        )
        val grid = Grid(width = 3, height = 3, cells = cells, placements = listOf(placement))

        assertThat(validator.validate(grid)).contains(
            GridViolation.OrphanedLetterCell(Position(Row(2), Column(2)))
        )
    }
}
