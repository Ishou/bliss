package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import com.bliss.game.domain.Fixtures.lobby
import com.bliss.game.domain.Fixtures.player
import com.bliss.game.domain.Fixtures.sessionA
import com.bliss.game.domain.Fixtures.sessionB
import org.junit.jupiter.api.Test

class LobbyTest {
    @Test
    fun `Lobby in WAITING with no game is valid`() {
        val l = lobby()
        assertThat(l.state).isEqualTo(LobbyLifecycleState.WAITING)
        assertThat(l.game).isEqualTo(null)
    }

    @Test
    fun `Lobby in WAITING rejects a non-null game`() {
        assertFailure {
            lobby(state = LobbyLifecycleState.WAITING, game = Fixtures.gameSession())
        }.messageContains("WAITING")
    }

    @Test
    fun `Lobby in IN_PROGRESS requires a game`() {
        assertFailure {
            lobby(state = LobbyLifecycleState.IN_PROGRESS, game = null)
        }.messageContains("IN_PROGRESS")
    }

    @Test
    fun `Lobby in COMPLETED requires a game`() {
        assertFailure {
            lobby(state = LobbyLifecycleState.COMPLETED, game = null)
        }.messageContains("COMPLETED")
    }

    @Test
    fun `Lobby in COMPLETED requires a completedAt on the game session`() {
        assertFailure {
            lobby(
                state = LobbyLifecycleState.COMPLETED,
                game = Fixtures.gameSession(completedAt = null),
            )
        }.messageContains("completedAt")
    }

    @Test
    fun `Lobby in COMPLETED with completedAt is valid`() {
        val l =
            lobby(
                state = LobbyLifecycleState.COMPLETED,
                game = Fixtures.gameSession(completedAt = Fixtures.later),
            )
        assertThat(l.state).isEqualTo(LobbyLifecycleState.COMPLETED)
    }

    @Test
    fun `Lobby in IN_PROGRESS with a game session is valid`() {
        val l = lobby(state = LobbyLifecycleState.IN_PROGRESS, game = Fixtures.gameSession())
        assertThat(l.state).isEqualTo(LobbyLifecycleState.IN_PROGRESS)
    }

    @Test
    fun `Lobby rejects more than 8 players`() {
        val nine =
            (0 until 9).associate { i ->
                val s = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a%02x".format(i))
                s to player(s, "P$i")
            }
        // Owner must be a member; pick the first.
        assertFailure {
            lobby(players = nine, ownerSessionId = nine.keys.first())
        }.messageContains("8")
    }

    @Test
    fun `Lobby rejects an owner that is not a member`() {
        assertFailure {
            lobby(
                players = mapOf(sessionB to player(sessionB, "Bob")),
                ownerSessionId = sessionA,
            )
        }.messageContains("must be a member")
    }

    @Test
    fun `isOwner returns true only for the owner sessionId`() {
        val l = lobby()
        assertThat(l.isOwner(sessionA)).isTrue()
        assertThat(l.isOwner(sessionB)).isFalse()
    }

    @Test
    fun `isFull is true at 8 players and false below`() {
        val eight =
            (0 until 8).associate { i ->
                val s = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a%02x".format(i))
                s to player(s, "P$i")
            }
        val full = lobby(players = eight, ownerSessionId = eight.keys.first())
        assertThat(full.isFull()).isTrue()
        assertThat(lobby().isFull()).isFalse()
    }

    @Test
    fun `hasJoined is true for present session and false otherwise`() {
        val l = lobby()
        assertThat(l.hasJoined(sessionA)).isTrue()
        assertThat(l.hasJoined(sessionB)).isFalse()
    }
}
