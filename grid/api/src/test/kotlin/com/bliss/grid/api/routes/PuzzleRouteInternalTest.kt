package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import org.junit.jupiter.api.Test

/** Unit tests for the internal [hasSparseHalf] check in [PuzzleRoute]. */
class PuzzleRouteInternalTest {
    @Test
    fun `hasSparseHalf returns true for an all-block grid`() {
        // 0 letter cells in every half → 0 % < 30 % threshold in all four halves.
        val grid = Grid.fromPlacements(10, 10, emptyList())
        assertThat(hasSparseHalf(grid)).isTrue()
    }

    @Test
    fun `hasSparseHalf returns false when every half exceeds the letter-density threshold`() {
        // Six horizontal words (clue at col 0, letters at cols 1–9) placed in rows
        // 0, 2, 4 (top half) and 5, 7, 9 (bottom half).
        //   top  half: 3 × 9 = 27 / 50 = 54 % > 30 %
        //   bottom half: 3 × 9 = 27 / 50 = 54 % > 30 %
        //   left  half (cols 0–4): 6 × 4 = 24 / 50 = 48 % > 30 %  (col 0 is a clue cell)
        //   right half (cols 5–9): 6 × 5 = 30 / 50 = 60 % > 30 %
        val placements =
            listOf(
                wp("AZERTYUIO", row = 0),
                wp("BZERTYUIO", row = 2),
                wp("CZERTYUIO", row = 4),
                wp("DZERTYUIO", row = 5),
                wp("EZERTYUIO", row = 7),
                wp("FZERTYUIO", row = 9),
            )
        val grid = Grid.fromPlacements(10, 10, placements)
        assertThat(hasSparseHalf(grid)).isFalse()
    }

    /** Horizontal placement: clue at (row, 0), letters at (row, 1..text.length). */
    private fun wp(
        text: String,
        row: Int,
    ): WordPlacement =
        WordPlacement(
            word = Word(text, "fixture"),
            cluePosition = Position(Row(row), Column(0)),
            direction = Direction.RIGHT,
        )
}
