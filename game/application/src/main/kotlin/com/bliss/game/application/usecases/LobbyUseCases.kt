package com.bliss.game.application.usecases

import com.bliss.game.application.ports.Clock
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.application.ports.PuzzleProvider
import com.bliss.game.domain.CellEntry
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
import java.time.Duration
import java.time.Instant

/** Bootstraps a new lobby in WAITING with the calling player as owner. */
class CreateLobbyUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val defaultGridConfig: GridConfig = GridConfig(7, 7),
) {
    suspend operator fun invoke(
        ownerSessionId: SessionId,
        ownerPseudonym: Pseudonym,
    ): UseCaseResult<Lobby> {
        val owner = Player(ownerSessionId, ownerPseudonym, clock.now())
        val lobby =
            Lobby(
                id = LobbyId.generate(),
                ownerSessionId = ownerSessionId,
                players = mapOf(ownerSessionId to owner),
                state = LobbyLifecycleState.WAITING,
                gridConfig = defaultGridConfig,
                game = null,
            )
        return UseCaseResult(repo.save(lobby), listOf(LobbyEvent.PlayerJoined(owner)))
    }
}

/** Idempotent join — re-joining with the same sessionId emits no event (reconnect path). */
class JoinLobbyUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        pseudonym: Pseudonym,
    ): UseCaseOutcome<Lobby> {
        var emitted: LobbyEvent? = null
        val updated =
            repo.mutate(lobbyId) { lobby ->
                when {
                    lobby.hasJoined(sessionId) -> lobby
                    lobby.isFull() -> lobby
                    else -> {
                        val player = Player(sessionId, pseudonym, clock.now())
                        emitted = LobbyEvent.PlayerJoined(player)
                        lobby.copy(players = lobby.players + (sessionId to player))
                    }
                }
            } ?: return failure(UseCaseError.LobbyNotFound)
        if (updated.isFull() && !updated.hasJoined(sessionId)) return failure(UseCaseError.LobbyFull)
        return success(updated, listOfNotNull(emitted))
    }
}

/** Updates the caller's pseudonym; player must already be in the lobby. */
class RenameSelfUseCase(
    private val repo: LobbyRepository,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        newPseudonym: Pseudonym,
    ): UseCaseOutcome<Lobby> {
        val before = repo.findById(lobbyId) ?: return failure(UseCaseError.LobbyNotFound)
        if (!before.hasJoined(sessionId)) return failure(UseCaseError.PlayerNotInLobby)
        val updated =
            repo.mutate(lobbyId) { lobby ->
                val existing = lobby.players[sessionId] ?: return@mutate lobby
                lobby.copy(players = lobby.players + (sessionId to existing.copy(pseudonym = newPseudonym)))
            } ?: return failure(UseCaseError.LobbyNotFound)
        return success(updated, listOf(LobbyEvent.PlayerRenamed(sessionId, newPseudonym)))
    }
}

/** Owner-only, WAITING-only: change the grid dimensions before Start. */
class SetGridConfigUseCase(
    private val repo: LobbyRepository,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        config: GridConfig,
    ): UseCaseOutcome<Lobby> {
        val current = repo.findById(lobbyId) ?: return failure(UseCaseError.LobbyNotFound)
        if (!current.isOwner(sessionId)) return failure(UseCaseError.NotOwner)
        if (current.state != LobbyLifecycleState.WAITING) return failure(UseCaseError.InvalidState)
        val updated =
            repo.mutate(lobbyId) { lobby -> lobby.copy(gridConfig = config) }
                ?: return failure(UseCaseError.LobbyNotFound)
        return success(updated, listOf(LobbyEvent.GridConfigChanged(config)))
    }
}

/** Owner-only: fetch a puzzle for the current grid size and transition to IN_PROGRESS. */
class StartGameUseCase(
    private val repo: LobbyRepository,
    private val puzzleProvider: PuzzleProvider,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
    ): UseCaseOutcome<Lobby> {
        val current = repo.findById(lobbyId) ?: return failure(UseCaseError.LobbyNotFound)
        if (!current.isOwner(sessionId)) return failure(UseCaseError.NotOwner)
        if (current.state != LobbyLifecycleState.WAITING) return failure(UseCaseError.InvalidState)
        // Fetch outside the lock — IO must not stall other lobbies.
        val puzzle = puzzleProvider.fetch(current.gridConfig.width, current.gridConfig.height)
        val session = GameSession(puzzle, emptyMap(), clock.now(), null)
        val updated =
            repo.mutate(lobbyId) { lobby ->
                lobby.copy(state = LobbyLifecycleState.IN_PROGRESS, game = session)
            } ?: return failure(UseCaseError.LobbyNotFound)
        return success(updated, listOf(LobbyEvent.GameStarted(session)))
    }
}

/** Removes the player; transfers ownership or closes the lobby when the owner leaves. */
class LeaveLobbyUseCase(
    private val repo: LobbyRepository,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
    ): UseCaseOutcome<Lobby?> {
        val before = repo.findById(lobbyId) ?: return failure(UseCaseError.LobbyNotFound)
        if (!before.hasJoined(sessionId)) return failure(UseCaseError.PlayerNotInLobby)
        val remaining = before.players - sessionId
        val events = mutableListOf<LobbyEvent>(LobbyEvent.PlayerLeft(sessionId))
        if (remaining.isEmpty()) {
            repo.delete(lobbyId)
            events += LobbyEvent.LobbyClosed("last player left")
            return success(null, events)
        }
        val nextOwner =
            if (before.isOwner(sessionId)) {
                remaining.values.minBy { it.joinedAt }.sessionId
            } else {
                before.ownerSessionId
            }
        val updated =
            repo.mutate(lobbyId) { lobby ->
                lobby.copy(players = lobby.players - sessionId, ownerSessionId = nextOwner)
            } ?: return failure(UseCaseError.LobbyNotFound)
        return success(updated, events)
    }
}

/**
 * Records a single cell write under last-write-wins (ADR-0018 §"Conflict policy"). When the new
 * write transitions the puzzle to fully solved, the lobby moves to COMPLETED and a [LobbyEvent.GameSolved]
 * event is appended. Lobby must be IN_PROGRESS and the player must be a member.
 */
class UpdateCellUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        position: Position,
        letter: Letter?,
    ): UseCaseOutcome<Lobby> {
        var solved: Pair<Long, Map<Position, CellEntry>>? = null
        var writtenAt: Instant? = null
        val updated =
            repo.mutate(lobbyId) { lobby ->
                if (lobby.state != LobbyLifecycleState.IN_PROGRESS) return@mutate lobby
                if (!lobby.hasJoined(sessionId)) return@mutate lobby
                val session = lobby.game ?: return@mutate lobby
                val now = clock.now().also { writtenAt = it }
                val entries =
                    if (letter == null) {
                        session.entries - position
                    } else {
                        session.entries + (position to CellEntry(sessionId, letter, now))
                    }
                val nextSession = session.copy(entries = entries)
                if (nextSession.isSolved() && session.completedAt == null) {
                    val completed = nextSession.copy(completedAt = now)
                    solved = Duration.between(session.startedAt, now).toMillis() to entries
                    lobby.copy(state = LobbyLifecycleState.COMPLETED, game = completed)
                } else {
                    lobby.copy(game = nextSession)
                }
            } ?: return failure(UseCaseError.LobbyNotFound)
        // Validate post-conditions (writtenAt is null only if mutator short-circuited).
        val stamp = writtenAt ?: return reasonFor(updated, sessionId)
        val events = mutableListOf<LobbyEvent>(LobbyEvent.CellUpdated(sessionId, position, letter, stamp))
        solved?.let { (durationMs, finalEntries) ->
            events += LobbyEvent.GameSolved(durationMs, finalEntries)
        }
        return success(updated, events)
    }

    private fun reasonFor(
        lobby: Lobby,
        sessionId: SessionId,
    ): UseCaseOutcome<Lobby> =
        when {
            !lobby.hasJoined(sessionId) -> failure(UseCaseError.PlayerNotInLobby)
            else -> failure(UseCaseError.InvalidState)
        }
}
