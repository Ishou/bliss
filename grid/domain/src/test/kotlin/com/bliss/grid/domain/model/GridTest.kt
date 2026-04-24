package com.bliss.grid.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test

class GridTest {

    @Test
    fun `single horizontal placement produces clue cell and letter cells`() {
        val placement = WordPlacement(
            word = Word("OR", "metal precieux"),
            cluePosition = Position(Row(0), Column(0)),
            direction = Direction.RIGHT,
        )

        val grid = Grid.fromPlacements(width = 4, height = 1, placements = listOf(placement))

        assertThat(grid.width).isEqualTo(4)
        assertThat(grid.height).isEqualTo(1)
        assertThat(grid.cells[Position(Row(0), Column(0))])
            .isEqualTo(ClueCell(listOf(Clue("metal precieux", Direction.RIGHT))))
        assertThat(grid.cells[Position(Row(0), Column(1))]).isEqualTo(LetterCell('O'))
        assertThat(grid.cells[Position(Row(0), Column(2))]).isEqualTo(LetterCell('R'))
        assertThat(grid.cells.keys).hasSize(3)
    }

    @Test
    fun `intersecting placements share the crossing letter`() {
        // CHAT horizontal at (1,0): letters at (1,1)=C (1,2)=H (1,3)=A (1,4)=T
        // HIBOU vertical clue at (0,2): letters at (1,2)=H (2,2)=I (3,2)=B (4,2)=O (5,2)=U
        // (1,2) is the crossing 'H'.
        val horizontal = WordPlacement(
            Word("CHAT", "felin"),
            Position(Row(1), Column(0)),
            Direction.RIGHT,
        )
        val vertical = WordPlacement(
            Word("HIBOU", "rapace"),
            Position(Row(0), Column(2)),
            Direction.DOWN,
        )

        val grid = Grid.fromPlacements(width = 6, height = 6, placements = listOf(horizontal, vertical))

        assertThat(grid.cells[Position(Row(1), Column(2))]).isEqualTo(LetterCell('H'))
        assertThat(grid.cells[Position(Row(2), Column(2))]).isEqualTo(LetterCell('I'))
    }

    @Test
    fun `two placements at the same clue position merge into one clue cell`() {
        val right = WordPlacement(Word("OR", "metal"), Position(Row(0), Column(0)), Direction.RIGHT)
        val down = WordPlacement(Word("OS", "anatomie"), Position(Row(0), Column(0)), Direction.DOWN)

        val grid = Grid.fromPlacements(width = 4, height = 4, placements = listOf(right, down))

        val clueCell = grid.cells[Position(Row(0), Column(0))] as ClueCell
        assertThat(clueCell.clues).containsExactlyInAnyOrder(
            Clue("metal", Direction.RIGHT),
            Clue("anatomie", Direction.DOWN),
        )
    }

    @Test
    fun `clue position out of bounds throws`() {
        val placement = WordPlacement(
            Word("OR", "x"),
            Position(Row(5), Column(0)),
            Direction.RIGHT,
        )
        assertFailure {
            Grid.fromPlacements(width = 3, height = 3, placements = listOf(placement))
        }.messageContains("bounds")
    }

    @Test
    fun `letter position out of bounds throws`() {
        val placement = WordPlacement(
            Word("CHAT", "x"),
            Position(Row(0), Column(0)),
            Direction.RIGHT,
        )
        assertFailure {
            Grid.fromPlacements(width = 3, height = 3, placements = listOf(placement))
        }.messageContains("bounds")
    }

    @Test
    fun `letter and clue cell overlap throws`() {
        val first = WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT)
        val second = WordPlacement(Word("AS", "y"), Position(Row(0), Column(2)), Direction.DOWN)
        assertFailure {
            Grid.fromPlacements(width = 5, height = 5, placements = listOf(first, second))
        }.messageContains("overlap")
    }

    @Test
    fun `inconsistent intersecting letters throw`() {
        // CHAT horizontal at (1,0): (1,2)='H'. ZAZA vertical clue at (0,2): (1,2)='Z'.
        val horizontal = WordPlacement(Word("CHAT", "x"), Position(Row(1), Column(0)), Direction.RIGHT)
        val vertical = WordPlacement(Word("ZAZA", "y"), Position(Row(0), Column(2)), Direction.DOWN)
        assertFailure {
            Grid.fromPlacements(width = 6, height = 6, placements = listOf(horizontal, vertical))
        }.messageContains("intersect")
    }

    @Test
    fun `duplicate words throw`() {
        val a = WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT)
        val b = WordPlacement(Word("OR", "x"), Position(Row(2), Column(0)), Direction.RIGHT)
        assertFailure {
            Grid.fromPlacements(width = 5, height = 5, placements = listOf(a, b))
        }.messageContains("duplicate")
    }

    @Test
    fun `two placements at the same clue position with the same direction throw`() {
        val a = WordPlacement(Word("OR", "metal"), Position(Row(0), Column(0)), Direction.RIGHT)
        val b = WordPlacement(Word("AS", "carte"), Position(Row(0), Column(0)), Direction.RIGHT)
        assertFailure {
            Grid.fromPlacements(width = 5, height = 5, placements = listOf(a, b))
        }
    }

    @Test
    fun `empty placements yields an empty grid of the requested size`() {
        val grid = Grid.fromPlacements(width = 3, height = 3, placements = emptyList())
        assertThat(grid.width).isEqualTo(3)
        assertThat(grid.height).isEqualTo(3)
        assertThat(grid.cells).isEqualTo(emptyMap<Position, Cell>())
    }
}
