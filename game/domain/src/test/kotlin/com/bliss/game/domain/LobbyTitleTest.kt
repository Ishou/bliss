package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import org.junit.jupiter.api.Test

class LobbyTitleTest {
    @Test
    fun `LobbyTitle accepts a normal display string`() {
        assertThat(LobbyTitle("Crossword Night").value).isEqualTo("Crossword Night")
    }

    @Test
    fun `LobbyTitle accepts exactly 64 chars`() {
        val sixtyFour = "a".repeat(64)
        assertThat(LobbyTitle(sixtyFour).value).hasLength(64)
    }

    @Test
    fun `LobbyTitle rejects empty string`() {
        assertFailure { LobbyTitle("") }.messageContains("blank")
    }

    @Test
    fun `LobbyTitle rejects whitespace-only string`() {
        assertFailure { LobbyTitle(" ") }.messageContains("blank")
    }

    @Test
    fun `LobbyTitle rejects newline-only string`() {
        assertFailure { LobbyTitle("\n") }.messageContains("blank")
    }

    @Test
    fun `LobbyTitle rejects strings longer than 64 chars`() {
        assertFailure { LobbyTitle("a".repeat(65)) }.messageContains("1..64")
    }

    @Test
    fun `LobbyTitle rejects strings containing newline control char`() {
        assertFailure { LobbyTitle("line1\nline2") }.messageContains("control")
    }

    @Test
    fun `LobbyTitle rejects strings containing tab control char`() {
        assertFailure { LobbyTitle("col1\tcol2") }.messageContains("control")
    }

    @Test
    fun `LobbyTitle rejects strings containing bell control char`() {
        assertFailure { LobbyTitle("x\u0007y") }.messageContains("control")
    }

    @Test
    fun `LobbyTitle rejects strings containing DEL control char`() {
        assertFailure { LobbyTitle("x\u007Fy") }.messageContains("control")
    }
}
