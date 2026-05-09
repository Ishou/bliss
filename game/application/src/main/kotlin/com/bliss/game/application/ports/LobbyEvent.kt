package com.bliss.game.application.ports

import com.bliss.game.domain.CellEntry
import com.bliss.game.domain.GameClueDirection
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LobbyCode
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

    /** Owner rotated the join code (ADR-0029). Wire mapping mirrors [GridConfigChanged]: the route re-broadcasts the [LobbyState] snapshot. */
    data class CodeRotated(
        val code: LobbyCode,
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
     * Server broadcast: every position in [positions] is now locked because its containing word
     * was just completed correctly. Emitted alongside the [CellUpdated] that closed the word; a
     * crossing fill that closes two words at once produces a single event with the union. Wire
     * mapping: `wordLocked` AsyncAPI message.
     */
    data class WordLocked(
        val positions: Set<Position>,
        val lockedAt: Instant,
    ) : LobbyEvent

    /**
     * Wire mapping: the API layer sends a WebSocket close frame (no `lobbyClosed` broadcast to
     * remaining members — there are none). No `lobbyClosed` entry is needed in `asyncapi.yaml`.
     */
    data class LobbyClosed(
        val reason: String,
    ) : LobbyEvent

    /**
     * Boolean state edge: peer started ([typing] = true) or stopped ([typing] = false) receiving
     * keystrokes. Emitted by `PresenceAggregator` on each transition; the same shape covers both
     * directions. Wire mapping: `typing` AsyncAPI message.
     */
    data class Typing(
        val sessionId: SessionId,
        val typing: Boolean,
    ) : LobbyEvent

    /**
     * Boolean state edge: peer crossed the inactivity threshold ([idle] = true) or returned to
     * activity ([idle] = false). Independent of [ConnectionLost] — a connected-but-idle peer is
     * still subscribed. Wire mapping: `idle` AsyncAPI message.
     */
    data class Idle(
        val sessionId: SessionId,
        val idle: Boolean,
    ) : LobbyEvent

    /**
     * Graceful disconnect signal — peer's WebSocket closed but the slot is held during the
     * server's grace window before any `playerLeft` follows. Distinct from [PlayerLeft]: clients
     * grey-out the roster pill on this event without removing the slot. Wire mapping:
     * `connectionLost` AsyncAPI message.
     */
    data class ConnectionLost(
        val sessionId: SessionId,
    ) : LobbyEvent

    /**
     * Server-authoritative cursor relocation. Emitted when an answer-validation flow locks cells
     * that contained a peer's cursor — the server moves the named session's cursor to the next
     * clue's first letter cell so the peer is not stranded on a sage cell. Wire mapping:
     * `cursorBumped` AsyncAPI message.
     */
    data class CursorBumped(
        val sessionId: SessionId,
        val position: Position,
        val direction: GameClueDirection,
    ) : LobbyEvent
}
