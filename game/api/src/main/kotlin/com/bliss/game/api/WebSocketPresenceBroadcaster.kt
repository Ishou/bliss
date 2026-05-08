package com.bliss.game.api

import com.bliss.game.api.routes.toFrameOrNull
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.ports.PresenceBroadcaster
import com.bliss.game.domain.LobbyId

/** Routes [LobbyEvent] presence edges to [SessionManager.broadcast] via the shared mapper. */
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
