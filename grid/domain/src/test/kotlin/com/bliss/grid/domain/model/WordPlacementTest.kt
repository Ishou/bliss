package com.bliss.grid.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WordPlacementTest {

    @Test
    fun `letterPositions for RIGHT starts one column after the clue`() {
        val placement = WordPlacement(
            word = Word("CHAT", "x"),
            cluePosition = Position(Row(2), Column(1)),
            direction = Direction.RIGHT,
        )
        assertThat(placement.letterPositions()).isEqualTo(
            listOf(
                Position(Row(2), Column(2)) to 'C',
                Position(Row(2), Column(3)) to 'H',
                Position(Row(2), Column(4)) to 'A',
                Position(Row(2), Column(5)) to 'T',
            )
        )
    }

    @Test
    fun `letterPositions for DOWN starts one row after the clue`() {
        val placement = WordPlacement(
            word = Word("OR", "x"),
            cluePosition = Position(Row(0), Column(3)),
            direction = Direction.DOWN,
        )
        assertThat(placement.letterPositions()).isEqualTo(
            listOf(
                Position(Row(1), Column(3)) to 'O',
                Position(Row(2), Column(3)) to 'R',
            )
        )
    }
}
