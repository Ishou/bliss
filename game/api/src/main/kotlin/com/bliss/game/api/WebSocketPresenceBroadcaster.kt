package com.bliss.game.api

import com.bliss.game.api.routes.toFrameOrNull
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.ports.PresenceBroadcaster
import com.bliss.game.domain.LobbyId

/**
 * `PresenceBroadcaster` adapter that bridges [LobbyEvent] edges from the
 * application layer onto the lobby's WebSocket fan-out via [SessionManager].
 *
 * The adapter reuses the existing `LobbyEvent.toFrameOrNull()` mapper so the
 * wire shape for `Typing` / `Idle` / `ConnectionLost` / `CursorBumped` stays
 * a single source of truth — there is no parallel mapping table to drift.
 * Events that map to `null` (e.g. `LobbyClosed`, `GridConfigChanged`) are
 * dropped silently; the aggregator never emits those, but the fallback keeps
 * the adapter total in case a future domain event is added without a wire
 * frame yet.
 */
class WebSocketPresenceBroadcaster(
    private val sessions: SessionManager,
) : PresenceBroadcaster {
    override suspend fun broadcast(
        lobbyId: LobbyId,
        event: LobbyEvent,
    ) {
        val frame = event.toFrameOrNull() ?: return
        sessions.broadcast(lobbyId, frame)
    }
}
