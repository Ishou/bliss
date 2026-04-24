package com.bliss.grid.domain.model

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test

class CellTest {

    @Test
    fun `LetterCell holds an uppercase letter`() {
        assertThat(LetterCell('A').letter).isEqualTo('A')
    }

    @Test
    fun `LetterCell rejects lowercase letters`() {
        assertFailure { LetterCell('a') }.messageContains("uppercase")
    }

    @Test
    fun `LetterCell rejects non-letters`() {
        assertFailure { LetterCell('1') }.messageContains("uppercase")
    }

    @Test
    fun `ClueCell with a single clue is valid`() {
        val clue = Clue("animal", Direction.RIGHT)
        assertThat(ClueCell(listOf(clue)).clues).isEqualTo(listOf(clue))
    }

    @Test
    fun `ClueCell with two clues in different directions is valid`() {
        val right = Clue("animal", Direction.RIGHT)
        val down = Clue("color", Direction.DOWN)
        assertThat(ClueCell(listOf(right, down)).clues).isEqualTo(listOf(right, down))
    }

    @Test
    fun `ClueCell rejects two clues in the same direction`() {
        val a = Clue("animal", Direction.RIGHT)
        val b = Clue("color", Direction.RIGHT)
        assertFailure { ClueCell(listOf(a, b)) }.messageContains("direction")
    }

    @Test
    fun `ClueCell rejects more than two clues`() {
        val a = Clue("a", Direction.RIGHT)
        val b = Clue("b", Direction.DOWN)
        val c = Clue("c", Direction.RIGHT)
        assertFailure { ClueCell(listOf(a, b, c)) }
    }

    @Test
    fun `ClueCell rejects empty clue list`() {
        assertFailure { ClueCell(emptyList()) }.messageContains("clue")
    }

    @Test
    fun `EmptyCell is a singleton`() {
        assertThat(EmptyCell).isEqualTo(EmptyCell)
    }
}
