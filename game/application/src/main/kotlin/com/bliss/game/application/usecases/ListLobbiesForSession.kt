package com.bliss.game.application.usecases

import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.LobbyTitle
import com.bliss.game.domain.SessionId
import java.time.Instant

/**
 * Light-weight projection of [Lobby] for the "My games" surface (ADR-0039).
 * Carries only the fields a session list card needs — full [Lobby] state
 * (players, game grid, locks) stays server-side until the user opens a
 * lobby.
 *
 * Kept in the same file as [ListLobbiesForSession] because the use case is
 * the sole producer and the DTO is part of its public contract.
 */
data class LobbySummary(
    val id: LobbyId,
    val code: LobbyCode,
    val state: LobbyLifecycleState,
    val gridConfig: GridConfig,
    val playerCount: Int,
    val lastActivityAt: Instant,
    val title: LobbyTitle?,
)

/**
 * Returns a session's lobbies as light-weight summaries, ordered by
 * lastActivityAt descending. Every lifecycle state is included so a user
 * can re-open finished games (ADR-0039).
 *
 * Pure transform: no events emitted, no clock injection — we deliberately
 * do not bump [Lobby.lastActivityAt] on a read.
 */
class ListLobbiesForSession(
    private val repo: LobbyRepository,
) {
    suspend operator fun invoke(sessionId: SessionId): List<LobbySummary> = repo.findBySessionId(sessionId).map { it.toSummary() }
}

private fun Lobby.toSummary(): LobbySummary =
    LobbySummary(
        id = id,
        code = code,
        state = state,
        gridConfig = gridConfig,
        playerCount = players.size,
        lastActivityAt = lastActivityAt,
        title = title,
    )
