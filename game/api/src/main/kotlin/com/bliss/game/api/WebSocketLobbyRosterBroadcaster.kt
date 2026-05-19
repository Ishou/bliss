package com.bliss.game.api

import com.bliss.game.api.routes.toLobbyStateFrame
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.application.ports.LobbyRosterBroadcaster
import com.bliss.game.domain.LobbyId

/**
 * Roster-update fan-out for cross-context user events (ADR-0049). The NATS subscriber
 * mutates lobby state via [LobbyRepository.anonymizeUserSeats] /
 * [LobbyRepository.refreshUserPseudonym] and then asks this adapter to push the fresh
 * [com.bliss.game.api.dto.ServerToClientFrame.LobbyState] snapshot to every connected
 * socket in the touched lobby. Re-reads the lobby AFTER the mutation so the broadcast
 * carries the post-update roster — the alternative (passing the touched seat in the
 * call) would duplicate the mapper logic that already lives in `toLobbyStateFrame`.
 *
 * A missing lobby (id not found, or evicted between mutate and broadcast) is silently
 * skipped — the subscriber's contract is best-effort UX, not a state machine.
 */
class WebSocketLobbyRosterBroadcaster(
    private val lobbies: LobbyRepository,
    private val sessions: SessionManager,
) : LobbyRosterBroadcaster {
    override suspend fun notifyRosterChanged(lobbyId: LobbyId) {
        val lobby = lobbies.findById(lobbyId) ?: return
        sessions.broadcast(lobbyId, lobby.toLobbyStateFrame(sessions.getPresence(lobbyId)))
    }
}
