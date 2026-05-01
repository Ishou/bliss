package com.bliss.grid.domain.validation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.model.Cell
import com.bliss.grid.domain.model.Clue
import com.bliss.grid.domain.model.ClueCell
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
        val placement =
            WordPlacement(
                Word("OR", "metal"),
                Position(Row(0), Column(0)),
                Direction.RIGHT,
            )
        val grid = Grid.fromPlacements(width = 3, height = 1, placements = listOf(placement))

        assertThat(validator.validate(grid)).isEmpty()
    }

    @Test
    fun `out of bounds cell is reported`() {
        val cells: Map<Position, Cell> =
            mapOf(
                Position(Row(5), Column(5)) to LetterCell('A'),
            )
        val grid = Grid(width = 3, height = 3, cells = cells, placements = emptyList())

        assertThat(validator.validate(grid)).contains(
            GridViolation.OutOfBounds(Position(Row(5), Column(5)), gridWidth = 3, gridHeight = 3),
        )
    }

    @Test
    fun `clue and letter cell overlap is reported`() {
        val letterPlacement =
            WordPlacement(
                Word("OR", "x"),
                Position(Row(0), Column(0)),
                Direction.RIGHT,
            )
        val cluePlacement =
            WordPlacement(
                Word("AS", "y"),
                cluePosition = Position(Row(0), Column(1)),
                direction = Direction.DOWN,
            )
        val cells: Map<Position, Cell> =
            mapOf(
                Position(Row(0), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
                Position(Row(0), Column(1)) to LetterCell('O'),
                Position(Row(0), Column(2)) to LetterCell('R'),
                Position(Row(1), Column(1)) to LetterCell('A'),
                Position(Row(2), Column(1)) to LetterCell('S'),
            )
        val grid =
            Grid(
                width = 4,
                height = 4,
                cells = cells,
                placements = listOf(letterPlacement, cluePlacement),
            )

        assertThat(validator.validate(grid)).contains(
            GridViolation.ClueCellLetterCellOverlap(Position(Row(0), Column(1))),
        )
    }

    @Test
    fun `inconsistent intersecting letters are reported`() {
        val horizontal = WordPlacement(Word("CHAT", "x"), Position(Row(1), Column(0)), Direction.RIGHT)
        val vertical = WordPlacement(Word("ZAZA", "y"), Position(Row(0), Column(2)), Direction.DOWN)

        val cells: Map<Position, Cell> =
            mapOf(
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
        val grid =
            Grid(
                width = 6,
                height = 6,
                cells = cells,
                placements = listOf(horizontal, vertical),
            )

        val violations = validator.validate(grid)
        assertThat(violations).contains(
            GridViolation.InconsistentIntersection(Position(Row(1), Column(2)), expected = 'H', actual = 'Z'),
        )
    }

    @Test
    fun `duplicate words are reported`() {
        val a = WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT)
        val b = WordPlacement(Word("OR", "x"), Position(Row(2), Column(0)), Direction.RIGHT)
        val cells: Map<Position, Cell> =
            mapOf(
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
    fun `duplicate lemmas across distinct surface forms are reported`() {
        // "AIME" and "AIMA" share lemma "AIMER" — distinct surface forms, no
        // DuplicateWord violation, but a DuplicateLemma violation grouping the
        // two offenders under their shared headword.
        val a = WordPlacement(Word("AIME", "x", lemma = "AIMER"), Position(Row(0), Column(0)), Direction.RIGHT)
        val b = WordPlacement(Word("AIMA", "x", lemma = "AIMER"), Position(Row(2), Column(0)), Direction.RIGHT)
        val cells: Map<Position, Cell> =
            mapOf(
                Position(Row(0), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
                Position(Row(0), Column(1)) to LetterCell('A'),
                Position(Row(0), Column(2)) to LetterCell('I'),
                Position(Row(0), Column(3)) to LetterCell('M'),
                Position(Row(0), Column(4)) to LetterCell('E'),
                Position(Row(2), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
                Position(Row(2), Column(1)) to LetterCell('A'),
                Position(Row(2), Column(2)) to LetterCell('I'),
                Position(Row(2), Column(3)) to LetterCell('M'),
                Position(Row(2), Column(4)) to LetterCell('A'),
            )
        val grid = Grid(width = 5, height = 4, cells = cells, placements = listOf(a, b))

        val violations = validator.validate(grid)
        val lemmaDup = violations.filterIsInstance<GridViolation.DuplicateLemma>()
        assertThat(lemmaDup.size).isEqualTo(1)
        assertThat(lemmaDup[0].lemma).isEqualTo("AIMER")
        assertThat(lemmaDup[0].words.map { it.text }.toSet()).isEqualTo(setOf("AIME", "AIMA"))
    }

    @Test
    fun `uncrossed interior cell is reported`() {
        // Only horizontal words, no vertical — interior cells at (1,1+) are uncrossed
        val placements =
            listOf(
                WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT),
                WordPlacement(Word("AS", "y"), Position(Row(1), Column(0)), Direction.RIGHT),
            )
        val grid = Grid.fromPlacements(width = 4, height = 3, placements = placements)

        val uncrossed = GridValidator.uncrossedCells(grid)
        // Interior cells (row > 0 and col > 0) without vertical coverage are violations
        assertThat(uncrossed).contains(
            GridViolation.UncrossedCell(Position(Row(1), Column(1)), inHorizontal = true, inVertical = false),
        )
    }

    @Test
    fun `edge cells pass with single-axis coverage`() {
        // A horizontal word in row 0 — those edge cells only need one direction
        val placement = WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT)
        val grid = Grid.fromPlacements(width = 3, height = 1, placements = listOf(placement))

        val uncrossed = GridValidator.uncrossedCells(grid)
        // (0,1) and (0,2) are edge cells (row==0) with horizontal coverage — valid
        assertThat(uncrossed).isEmpty()
    }

    @Test
    fun `partially interlocked grid reports uncrossed interior cells`() {
        // Horizontal CHAT at (1,0)→RIGHT: clue(1,0), C(1,1) H(1,2) A(1,3) T(1,4)
        // Vertical HAS at (0,2)→DOWN: clue(0,2), H(1,2) A(2,2) S(3,2)
        // Vertical ACE at (0,3)→DOWN: clue(0,3), A(1,3) C(2,3) E(3,3)
        // Vertical TAS at (0,4)→DOWN: clue(0,4), T(1,4) A(2,4) S(3,4)
        //
        // Interior cells: (1,1)C=H-only, (1,2)H=both, (1,3)A=both, (1,4)T=both
        //                  (2,2)A=V-only, (3,2)S=V-only, (2,3)C=V-only, (3,3)E=V-only
        //                  (2,4)A=V-only, (3,4)S=V-only
        // (1,1) is interior and only horizontal → uncrossed
        // (2,2) etc are interior and only vertical → uncrossed
        //
        // So this layout is NOT fully interlocked. Add a vertical for col 1:
        val grid =
            Grid.fromPlacements(
                width = 5,
                height = 5,
                placements =
                    listOf(
                        WordPlacement(Word("CHAT", "felin"), Position(Row(1), Column(0)), Direction.RIGHT),
                        WordPlacement(Word("COW", "vache"), Position(Row(0), Column(1)), Direction.DOWN),
                        WordPlacement(Word("HAS", "posseder"), Position(Row(0), Column(2)), Direction.DOWN),
                        WordPlacement(Word("ACE", "carte"), Position(Row(0), Column(3)), Direction.DOWN),
                        WordPlacement(Word("TAS", "pile"), Position(Row(0), Column(4)), Direction.DOWN),
                        // Second horizontal crossing the verticals at row 2:
                        // Needs letters matching: col1=O, col2=A, col3=C, col4=A → OACA? Not a word.
                        // Let me just verify partial interlocking instead.
                    ),
            )

        val uncrossed = GridValidator.uncrossedCells(grid)
        // C(1,1) is crossed (CHAT horizontal + COW vertical) — OK
        // H(1,2) is crossed — OK
        // A(1,3) is crossed — OK
        // T(1,4) is crossed — OK
        // O(2,1), W(3,1), A(2,2), S(3,2), C(2,3), E(3,3), A(2,4), S(3,4) — vertical only, interior → uncrossed
        // So there ARE uncrossed cells. But the key cells (1,1) through (1,4) are properly crossed.
        assertThat(uncrossed.map { it.position }).contains(Position(Row(2), Column(1)))
    }

    @Test
    fun `orphaned letter cells are reported`() {
        val placement = WordPlacement(Word("OR", "x"), Position(Row(0), Column(0)), Direction.RIGHT)
        val cells: Map<Position, Cell> =
            mapOf(
                Position(Row(0), Column(0)) to ClueCell(listOf(Clue("x", Direction.RIGHT))),
                Position(Row(0), Column(1)) to LetterCell('O'),
                Position(Row(0), Column(2)) to LetterCell('R'),
                Position(Row(2), Column(2)) to LetterCell('Z'),
            )
        val grid = Grid(width = 3, height = 3, cells = cells, placements = listOf(placement))

        assertThat(validator.validate(grid)).contains(
            GridViolation.OrphanedLetterCell(Position(Row(2), Column(2))),
        )
    }
}
