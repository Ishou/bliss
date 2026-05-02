package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.util.UUID

class GameCellTest {
    private val clueId = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a70")
    private val clueId2 = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a71")

    @Test
    fun `DefinitionCell accepts one clue`() {
        val cell =
            DefinitionCell(
                Position(0, 0),
                listOf(GameDefinitionClue(clueId, "Capitale", GameArrow.RIGHT)),
            )
        assertThat(cell.clues.size).isEqualTo(1)
    }

    @Test
    fun `DefinitionCell accepts two clues with distinct arrows`() {
        val cell =
            DefinitionCell(
                Position(0, 0),
                listOf(
                    GameDefinitionClue(clueId, "right", GameArrow.RIGHT),
                    GameDefinitionClue(clueId2, "down", GameArrow.DOWN),
                ),
            )
        assertThat(cell.clues.size).isEqualTo(2)
    }

    @Test
    fun `DefinitionCell rejects empty clue list`() {
        assertFailure { DefinitionCell(Position(0, 0), emptyList()) }
            .messageContains("at least one")
    }

    @Test
    fun `DefinitionCell rejects more than two clues`() {
        val three =
            listOf(
                GameDefinitionClue(clueId, "a", GameArrow.RIGHT),
                GameDefinitionClue(clueId2, "b", GameArrow.DOWN),
                GameDefinitionClue(UUID.randomUUID(), "c", GameArrow.RIGHT_DOWN),
            )
        assertFailure { DefinitionCell(Position(0, 0), three) }.messageContains("at most")
    }

    @Test
    fun `DefinitionCell rejects two clues with the same arrow`() {
        val same =
            listOf(
                GameDefinitionClue(clueId, "a", GameArrow.RIGHT),
                GameDefinitionClue(clueId2, "b", GameArrow.RIGHT),
            )
        assertFailure { DefinitionCell(Position(0, 0), same) }.messageContains("arrow")
    }

    @Test
    fun `GameDefinitionClue rejects blank text`() {
        assertFailure { GameDefinitionClue(clueId, "  ", GameArrow.RIGHT) }
            .messageContains("blank")
    }

    @Test
    fun `GameDefinitionClue rejects text longer than 500 chars`() {
        assertFailure { GameDefinitionClue(clueId, "x".repeat(501), GameArrow.RIGHT) }
            .messageContains("500")
    }

    @Test
    fun `GameClue accepts length 1 (minimum valid)`() {
        val c = GameClue(clueId, GameClueDirection.ACROSS, Position(0, 0), 1, "hint")
        assertThat(c.length).isEqualTo(1)
    }

    @Test
    fun `GameClue accepts length 50 (maximum valid)`() {
        val c = GameClue(clueId, GameClueDirection.ACROSS, Position(0, 0), 50, "hint")
        assertThat(c.length).isEqualTo(50)
    }

    @Test
    fun `GameClue accepts in-range length and non-blank text`() {
        val c = GameClue(clueId, GameClueDirection.ACROSS, Position(0, 0), 5, "hint")
        assertThat(c.length).isEqualTo(5)
    }

    @Test
    fun `GameClue rejects zero length`() {
        assertFailure {
            GameClue(clueId, GameClueDirection.ACROSS, Position(0, 0), 0, "hint")
        }.messageContains("length")
    }

    @Test
    fun `GameClue rejects length above 50`() {
        assertFailure {
            GameClue(clueId, GameClueDirection.ACROSS, Position(0, 0), 51, "hint")
        }.messageContains("length")
    }

    @Test
    fun `GameClue rejects blank text`() {
        assertFailure {
            GameClue(clueId, GameClueDirection.ACROSS, Position(0, 0), 1, "  ")
        }.messageContains("blank")
    }

    @Test
    fun `GameClue rejects text longer than 500 chars`() {
        assertFailure {
            GameClue(clueId, GameClueDirection.ACROSS, Position(0, 0), 1, "x".repeat(501))
        }.messageContains("500")
    }
}
