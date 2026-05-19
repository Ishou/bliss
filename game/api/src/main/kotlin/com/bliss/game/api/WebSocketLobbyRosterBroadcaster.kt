package com.bliss.game.api

import com.bliss.game.api.routes.toLobbyStateFrame
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.application.ports.LobbyRosterBroadcaster
import com.bliss.game.domain.LobbyId

/** ADR-0049 roster fan-out: re-reads the lobby after mutation and broadcasts a fresh LobbyState snapshot to every connected socket; silently skips missing lobbies. */
class WebSocketLobbyRosterBroadcaster(
    private val lobbies: LobbyRepository,
    private val sessions: SessionManager,
) : LobbyRosterBroadcaster {
    override suspend fun notifyRosterChanged(lobbyId: LobbyId) {
        val lobby = lobbies.findById(lobbyId) ?: return
        sessions.broadcast(lobbyId, lobby.toLobbyStateFrame(sessions.getPresence(lobbyId)))
    }
}
