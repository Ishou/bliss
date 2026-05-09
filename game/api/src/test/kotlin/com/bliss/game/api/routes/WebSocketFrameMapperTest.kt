package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.api.dto.ServerToClientFrame
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Roundtrip tests for the domain -> WebSocket DTO mapping. The mapper
 * functions are `internal`; this file lives in the same package to call
 * them directly. Test names use ASCII hyphens (per the JVM-backend playbook
 * rule about non-ASCII chars in @Test names crashing CI).
 */
class WebSocketFrameMapperTest {
    private val ownerId = SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b")
    private val ownerPseudonym = Pseudonym("Alice")
    private val joinedAt = Instant.parse("2026-05-02T15:30:00Z")
    private val startedAt = Instant.parse("2026-05-02T15:35:00Z")
    private val writtenAt1 = Instant.parse("2026-05-02T15:35:42Z")
    private val writtenAt2 = Instant.parse("2026-05-02T15:35:43Z")

    @Test
    fun `lobbyState snapshot serializes domain entries map into a sorted CellEntryDto array`() {
        // Insert entries in non-stable order so the test exercises the
        // mapper's sort: row 1 col 0 should land AFTER row 0 col 4.
        val entries =
            mapOf(
                Position(1, 0) to CellEntry(ownerId, Letter('Q'), writtenAt2),
                Position(0, 4) to CellEntry(ownerId, Letter('A'), writtenAt1),
                Position(0, 3) to CellEntry(ownerId, Letter('P'), writtenAt1),
            )
        val lobby = inProgressLobby(entries)

        val frame = lobby.toLobbyStateFrame()

        val game = frame.game
        assertThat(game).isNotNull()
        // Stable order = sort by (row, column). The domain map's iteration
        // order is undefined; the wire MUST be deterministic.
        val rowsCols = game!!.entries.map { it.row to it.column }
        assertThat(rowsCols).containsExactly(0 to 3, 0 to 4, 1 to 0)
        // Field roundtrip: every CellEntry property reaches the wire.
        val first = game.entries[0]
        assertThat(first.sessionId).isEqualTo(ownerId.value)
        assertThat(first.letter).isEqualTo("P")
        assertThat(first.writtenAt).isEqualTo(writtenAt1.toString())
    }

    @Test
    fun `lobbyState snapshot emits an empty entries array when no cells have been typed`() {
        // Important: the field is REQUIRED on the wire (asyncapi `required:
        // [puzzle, entries, startedAt, completedAt]`). Empty list, not absent.
        val lobby = inProgressLobby(emptyMap())

        val frame = lobby.toLobbyStateFrame()

        val game = frame.game
        assertThat(game).isNotNull()
        assertThat(game!!.entries).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `LobbyEvent Typing maps to ServerToClientFrame Typing with the same boolean`() {
        val frame = LobbyEvent.Typing(ownerId, typing = true).toFrameOrNull()
        assertThat(frame).isNotNull()
        val typed = frame as ServerToClientFrame.Typing
        assertThat(typed.sessionId).isEqualTo(ownerId.value)
        assertThat(typed.typing).isEqualTo(true)
    }

    @Test
    fun `LobbyEvent Idle maps to ServerToClientFrame Idle with the same boolean`() {
        val frame = LobbyEvent.Idle(ownerId, idle = false).toFrameOrNull()
        assertThat(frame).isNotNull()
        val typed = frame as ServerToClientFrame.Idle
        assertThat(typed.sessionId).isEqualTo(ownerId.value)
        assertThat(typed.idle).isEqualTo(false)
    }

    @Test
    fun `LobbyEvent ConnectionLost maps to ServerToClientFrame ConnectionLost`() {
        val frame = LobbyEvent.ConnectionLost(ownerId).toFrameOrNull()
        assertThat(frame).isNotNull()
        assertThat((frame as ServerToClientFrame.ConnectionLost).sessionId).isEqualTo(ownerId.value)
    }

    @Test
    fun `LobbyEvent WordLocked sorts positions by row then column on the wire`() {
        // Cross-positions deliberately listed out of (row, column) order so the
        // mapper has to sort them — we want a stable wire shape regardless of
        // how the use case happens to enumerate the words.
        val event =
            LobbyEvent.WordLocked(
                positions = setOf(Position(2, 3), Position(0, 4), Position(0, 3), Position(1, 3)),
                lockedAt = writtenAt2,
            )
        val frame = event.toFrameOrNull() as ServerToClientFrame.WordLocked
        assertThat(frame.positions.map { it.row to it.column })
            .containsExactly(0 to 3, 0 to 4, 1 to 3, 2 to 3)
        assertThat(frame.lockedAt).isEqualTo(writtenAt2.toString())
    }

    @Test
    fun `lobbyState snapshot carries the lobby code as a first-class field`() {
        // `code` is always present — the server mints one at create-time and never sends a snapshot without it.
        val lobby = inProgressLobby(emptyMap())
        val frame = lobby.toLobbyStateFrame()
        assertThat(frame.code).isEqualTo(lobby.code.value)
    }

    @Test
    fun `LobbyEvent CodeRotated maps to no dedicated frame (snapshot path)`() {
        // Wire mapping mirrors GridConfigChanged: route re-broadcasts a refreshed lobbyState.
        val frame = LobbyEvent.CodeRotated(LobbyCode.generate()).toFrameOrNull()
        assertThat(frame).isNull()
    }

    @Test
    fun `lobbyState snapshot serializes lockedPositions sorted by row then column`() {
        val locks = setOf(Position(2, 0), Position(0, 1), Position(0, 0))
        val lobby = inProgressLobby(emptyMap(), lockedPositions = locks)

        val frame = lobby.toLobbyStateFrame()
        val game = frame.game
        assertThat(game).isNotNull()
        assertThat(game!!.lockedPositions.map { it.row to it.column })
            .containsExactly(0 to 0, 0 to 1, 2 to 0)
    }

    @Test
    fun `lobbyState snapshot emits an empty lockedPositions array on a fresh session`() {
        // Required-on-the-wire field; empty list, never absent.
        val lobby = inProgressLobby(emptyMap())

        val frame = lobby.toLobbyStateFrame()
        val game = frame.game
        assertThat(game).isNotNull()
        assertThat(game!!.lockedPositions).isEqualTo(emptyList<Any>())
    }

    @Test
    fun `LobbyEvent CursorBumped maps direction enum to wire string`() {
        val acrossFrame =
            LobbyEvent
                .CursorBumped(
                    ownerId,
                    Position(2, 0),
                    GameClueDirection.ACROSS,
                ).toFrameOrNull()
        val downFrame =
            LobbyEvent
                .CursorBumped(
                    ownerId,
                    Position(0, 2),
                    GameClueDirection.DOWN,
                ).toFrameOrNull()

        assertThat(acrossFrame).isNotNull()
        val across = acrossFrame as ServerToClientFrame.CursorBumped
        assertThat(across.row).isEqualTo(2)
        assertThat(across.column).isEqualTo(0)
        assertThat(across.direction).isEqualTo("across")

        assertThat(downFrame).isNotNull()
        val down = downFrame as ServerToClientFrame.CursorBumped
        assertThat(down.row).isEqualTo(0)
        assertThat(down.column).isEqualTo(2)
        assertThat(down.direction).isEqualTo("down")
    }

    private fun inProgressLobby(
        entries: Map<Position, CellEntry>,
        lockedPositions: Set<Position> = emptySet(),
    ): Lobby =
        Lobby(
            id = LobbyId.generate(),
            ownerSessionId = ownerId,
            players = mapOf(ownerId to Player(ownerId, ownerPseudonym, joinedAt)),
            state = LobbyLifecycleState.IN_PROGRESS,
            gridConfig = GridConfig(7, 7),
            game =
                GameSession(
                    puzzle = puzzle(),
                    entries = entries,
                    startedAt = startedAt,
                    completedAt = null,
                    lockedPositions = lockedPositions,
                ),
            lastActivityAt = startedAt,
            code = LobbyCode.generate(),
        )

    private fun puzzle(): GamePuzzle =
        GamePuzzle(
            id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"),
            title = "Petite grille",
            language = "fr",
            width = 5,
            height = 5,
            cells = emptyList(),
            clues = emptyList(),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
