package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test
import java.util.UUID

class GameCellTest {
    private val clueId = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a70")
    private val clueIdB = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a71")
    private val clueIdC = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a72")

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
    fun `DefinitionCell accepts a single clue`() {
        val cell = DefinitionCell(Position(0, 0), listOf(GameDefinitionClue(clueId, "across", GameArrow.RIGHT)))
        assertThat(cell.clues.map { it.id }).containsExactly(clueId)
    }

    @Test
    fun `DefinitionCell accepts a stacked pair (mots-fleches corner)`() {
        val cell =
            DefinitionCell(
                Position(0, 0),
                listOf(
                    GameDefinitionClue(clueId, "across", GameArrow.RIGHT),
                    GameDefinitionClue(clueIdB, "down", GameArrow.DOWN),
                ),
            )
        assertThat(cell.clues.map { it.id }).containsExactly(clueId, clueIdB)
    }

    @Test
    fun `DefinitionCell rejects an empty clue list`() {
        assertFailure { DefinitionCell(Position(0, 0), emptyList()) }
            .messageContains("1 or 2 clues")
    }

    @Test
    fun `DefinitionCell rejects three or more clues`() {
        assertFailure {
            DefinitionCell(
                Position(0, 0),
                listOf(
                    GameDefinitionClue(clueId, "a", GameArrow.RIGHT),
                    GameDefinitionClue(clueIdB, "b", GameArrow.DOWN),
                    GameDefinitionClue(clueIdC, "c", GameArrow.DOWN_RIGHT),
                ),
            )
        }.messageContains("1 or 2 clues")
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
