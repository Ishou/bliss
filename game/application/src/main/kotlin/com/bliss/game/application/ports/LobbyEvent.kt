package com.bliss.game.application.ports

import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import java.time.Instant

/**
 * Domain events emitted by use cases on every state transition. The API layer
 * (Wave F) maps these to AsyncAPI frames; the application layer is unaware of
 * the wire format. See `game/api/asyncapi.yaml` for the broadcast catalog.
 */
sealed interface LobbyEvent {
    data class PlayerJoined(
        val player: Player,
    ) : LobbyEvent

    data class PlayerLeft(
        val sessionId: SessionId,
    ) : LobbyEvent

    data class PlayerRenamed(
        val sessionId: SessionId,
        val pseudonym: Pseudonym,
    ) : LobbyEvent

    /**
     * Wire mapping: the API layer re-broadcasts the full [LobbyState] snapshot (not a dedicated
     * `gridConfigChanged` frame) so all clients converge on the new dimensions without a separate
     * message type. No `gridConfigChanged` entry is needed in `asyncapi.yaml`.
     */
    data class GridConfigChanged(
        val config: GridConfig,
    ) : LobbyEvent

    data class GameStarted(
        val session: GameSession,
    ) : LobbyEvent

    data class CellUpdated(
        val sessionId: SessionId,
        val position: Position,
        val letter: Letter?,
        val writtenAt: Instant,
    ) : LobbyEvent

    data class GameSolved(
        val durationMs: Long,
        val finalEntries: Map<Position, CellEntry>,
    ) : LobbyEvent

    /**
     * Wire mapping: the API layer sends a WebSocket close frame (no `lobbyClosed` broadcast to
     * remaining members — there are none). No `lobbyClosed` entry is needed in `asyncapi.yaml`.
     */
    data class LobbyClosed(
        val reason: String,
    ) : LobbyEvent
}
