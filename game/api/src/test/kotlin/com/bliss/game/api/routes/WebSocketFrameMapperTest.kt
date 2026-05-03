package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Lobby
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

    private fun inProgressLobby(entries: Map<Position, CellEntry>): Lobby =
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
                ),
            lastActivityAt = startedAt,
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
