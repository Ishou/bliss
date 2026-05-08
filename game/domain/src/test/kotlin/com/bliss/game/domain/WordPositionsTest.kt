package com.bliss.game.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.game.domain.Fixtures.now
import org.junit.jupiter.api.Test
import java.util.UUID

class WordPositionsTest {
    private val acrossClue =
        GameClue(
            id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a01"),
            direction = GameClueDirection.ACROSS,
            start = Position(0, 1),
            length = 4,
            text = "Capitale (P, A, R, I)",
        )
    private val downClue =
        GameClue(
            id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a02"),
            direction = GameClueDirection.DOWN,
            start = Position(0, 2),
            length = 3,
            text = "Trois lettres en colonne",
        )

    @Test
    fun `wordPositions walks across from start for length cells`() {
        assertThat(wordPositions(acrossClue))
            .containsExactly(
                Position(0, 1),
                Position(0, 2),
                Position(0, 3),
                Position(0, 4),
            )
    }

    @Test
    fun `wordPositions walks down from start for length cells`() {
        assertThat(wordPositions(downClue))
            .containsExactly(
                Position(0, 2),
                Position(1, 2),
                Position(2, 2),
            )
    }

    @Test
    fun `wordPositions for a length-1 clue returns just the start position`() {
        val unitClue = acrossClue.copy(length = 1)
        assertThat(wordPositions(unitClue)).containsExactly(Position(0, 1))
    }

    @Test
    fun `wordsContaining returns empty when the position is in no word`() {
        val puzzle = puzzleWith(acrossClue, downClue)
        assertThat(puzzle.wordsContaining(Position(4, 4))).isEmpty()
    }

    @Test
    fun `wordsContaining returns the across word when the position lies on it`() {
        val puzzle = puzzleWith(acrossClue, downClue)
        val words = puzzle.wordsContaining(Position(0, 4))
        assertThat(words.size).isEqualTo(1)
        assertThat(words[0]).containsExactly(
            Position(0, 1),
            Position(0, 2),
            Position(0, 3),
            Position(0, 4),
        )
    }

    @Test
    fun `wordsContaining returns both words when the position is the across-down crossing`() {
        val puzzle = puzzleWith(acrossClue, downClue)
        val words = puzzle.wordsContaining(Position(0, 2))
        assertThat(words.size).isEqualTo(2)
        // Order is the order clues appear in the puzzle.
        assertThat(words[0]).containsExactly(
            Position(0, 1),
            Position(0, 2),
            Position(0, 3),
            Position(0, 4),
        )
        assertThat(words[1]).containsExactly(
            Position(0, 2),
            Position(1, 2),
            Position(2, 2),
        )
    }

    private fun puzzleWith(vararg clues: GameClue): GamePuzzle =
        GamePuzzle(
            id = UUID.randomUUID(),
            title = "Word positions fixture",
            language = "fr",
            width = 5,
            height = 5,
            cells = emptyList(),
            clues = clues.toList(),
            createdAt = now,
        )
}
