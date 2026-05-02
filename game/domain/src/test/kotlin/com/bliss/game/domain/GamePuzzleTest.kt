package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GamePuzzleTest {
    private val id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c")
    private val createdAt = Instant.parse("2026-04-24T15:30:00Z")

    @Test
    fun `GamePuzzle accepts a 1x1 minimum`() {
        val p =
            GamePuzzle(
                id = id,
                width = 1,
                height = 1,
                cells = listOf(LetterCell(Position(0, 0), Letter('A'))),
                clues = emptyList(),
                createdAt = createdAt,
            )
        assertThat(p.width).isEqualTo(1)
    }

    @Test
    fun `GamePuzzle rejects width 0`() {
        assertFailure {
            GamePuzzle(id, 0, 1, emptyList(), emptyList(), createdAt)
        }.messageContains("width")
    }

    @Test
    fun `GamePuzzle rejects width above 50`() {
        assertFailure {
            GamePuzzle(id, 51, 1, emptyList(), emptyList(), createdAt)
        }.messageContains("width")
    }

    @Test
    fun `GamePuzzle rejects height 0`() {
        assertFailure {
            GamePuzzle(id, 1, 0, emptyList(), emptyList(), createdAt)
        }.messageContains("height")
    }

    @Test
    fun `GamePuzzle rejects height above 50`() {
        assertFailure {
            GamePuzzle(id, 1, 51, emptyList(), emptyList(), createdAt)
        }.messageContains("height")
    }

    @Test
    fun `GamePuzzle rejects out-of-bounds cell`() {
        assertFailure {
            GamePuzzle(
                id,
                width = 5,
                height = 5,
                cells = listOf(LetterCell(Position(0, 5), Letter('A'))),
                clues = emptyList(),
                createdAt = createdAt,
            )
        }.messageContains("out of bounds")
    }

    @Test
    fun `GamePuzzle rejects two cells at the same position`() {
        assertFailure {
            GamePuzzle(
                id,
                width = 5,
                height = 5,
                cells =
                    listOf(
                        LetterCell(Position(0, 0), Letter('A')),
                        BlockCell(Position(0, 0)),
                    ),
                clues = emptyList(),
                createdAt = createdAt,
            )
        }.messageContains("Duplicate")
    }
}
