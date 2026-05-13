package com.bliss.game.application.usecases

import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.GameSession
import com.bliss.game.domain.GridConfig
import com.bliss.game.domain.LetterCell
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
    val progress: LobbyProgress,
    val title: LobbyTitle?,
)

data class LobbyProgress(
    val solvedCells: Int,
    val totalCells: Int,
) {
    init {
        require(solvedCells >= 0) { "solvedCells must be non-negative, was $solvedCells" }
        require(totalCells >= 0) { "totalCells must be non-negative, was $totalCells" }
        require(solvedCells <= totalCells) {
            "solvedCells ($solvedCells) must not exceed totalCells ($totalCells)"
        }
    }
}

/**
 * Returns a session's lobbies as light-weight summaries, ordered by
 * lastActivityAt descending. Only IN_PROGRESS and COMPLETED lobbies are
 * returned — WAITING (un-started) lobbies are excluded because they are
 * "salons d'attente", not "parties". Surfacing them produced confusing
 * 404 toasts when the WAITING TTL evicted them between the list fetch
 * and a rejoin click (ADR-0039 amendment 2026-05-12). WAITING lobbies
 * remain reachable via direct URL / invite code.
 *
 * Filtering lives in the repository so the listing is consistent across
 * adapters and avoids leaking WAITING lobby ids to the client.
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
        // WAITING is excluded above; IN_PROGRESS/COMPLETED always carry a GameSession.
        progress = game!!.toProgress(),
        title = title,
    )

// Uses lockedPositions: grid strips LetterCell.answer from the wire, so answer-based counting always returns 0.
private fun GameSession.toProgress(): LobbyProgress {
    val totalCells =
        puzzle.cells
            .asSequence()
            .filterIsInstance<LetterCell>()
            .count()
    return LobbyProgress(solvedCells = lockedPositions.size, totalCells = totalCells)
}
