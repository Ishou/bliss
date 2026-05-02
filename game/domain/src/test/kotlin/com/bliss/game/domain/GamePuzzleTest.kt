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
                title = "Mini",
                language = "en",
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
            GamePuzzle(id, "T", "en", 0, 1, emptyList(), emptyList(), createdAt)
        }.messageContains("width")
    }

    @Test
    fun `GamePuzzle rejects width above 50`() {
        assertFailure {
            GamePuzzle(id, "T", "en", 51, 1, emptyList(), emptyList(), createdAt)
        }.messageContains("width")
    }

    @Test
    fun `GamePuzzle rejects height 0`() {
        assertFailure {
            GamePuzzle(id, "T", "en", 1, 0, emptyList(), emptyList(), createdAt)
        }.messageContains("height")
    }

    @Test
    fun `GamePuzzle rejects height above 50`() {
        assertFailure {
            GamePuzzle(id, "T", "en", 1, 51, emptyList(), emptyList(), createdAt)
        }.messageContains("height")
    }

    @Test
    fun `GamePuzzle rejects out-of-bounds cell`() {
        assertFailure {
            GamePuzzle(
                id,
                title = "T",
                language = "en",
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
                title = "T",
                language = "en",
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

    @Test
    fun `GamePuzzle rejects blank title`() {
        assertFailure {
            GamePuzzle(id, "  ", "en", 1, 1, emptyList(), emptyList(), createdAt)
        }.messageContains("title")
    }

    @Test
    fun `GamePuzzle rejects title longer than 200 chars`() {
        assertFailure {
            GamePuzzle(id, "x".repeat(201), "en", 1, 1, emptyList(), emptyList(), createdAt)
        }.messageContains("title")
    }

    @Test
    fun `GamePuzzle accepts title of exactly 200 chars`() {
        val p = GamePuzzle(id, "x".repeat(200), "en", 1, 1, emptyList(), emptyList(), createdAt)
        assertThat(p.title.length).isEqualTo(200)
    }

    @Test
    fun `GamePuzzle accepts valid BCP-47 language tags`() {
        // simple 2-letter
        assertThat(
            GamePuzzle(id, "T", "fr", 1, 1, emptyList(), emptyList(), createdAt).language,
        ).isEqualTo("fr")
        // with subtag
        assertThat(
            GamePuzzle(id, "T", "zh-Hant", 1, 1, emptyList(), emptyList(), createdAt).language,
        ).isEqualTo("zh-Hant")
    }

    @Test
    fun `GamePuzzle rejects invalid language tag`() {
        assertFailure {
            GamePuzzle(id, "T", "not-valid!!", 1, 1, emptyList(), emptyList(), createdAt)
        }.messageContains("language")
    }
}
