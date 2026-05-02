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

/**
 * Bootstraps a new lobby in WAITING with the calling player as owner.
 *
 * Idempotent per [SessionId]: if the caller already owns a WAITING lobby, that
 * lobby is returned with no event emitted (and no new lobby minted). This kills
 * the trivial DOS / RAM-exhaustion path where a single user creates an unbounded
 * number of lobbies by repeatedly hitting POST /v1/lobbies on the home screen.
 *
 * Note: the lookup-then-save sequence is not strictly atomic — two concurrent
 * createLobby calls from the same sessionId may both miss the existing lobby and
 * mint two. The window is microseconds and the GC sweep evicts the loser in at
 * most one TTL period. A stricter "create-or-get" would require a per-session
 * lock or a unique index in the eventual Postgres adapter; out of scope for v1.
 */
class CreateLobbyUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val defaultGridConfig: GridConfig = GridConfig(7, 7),
) {
    suspend operator fun invoke(
        ownerSessionId: SessionId,
        ownerPseudonym: Pseudonym,
    ): UseCaseResult<Lobby> {
        repo.findWaitingByOwnerSession(ownerSessionId)?.let { existing ->
            return UseCaseResult(existing, emptyList())
        }
        val now = clock.now()
        val owner = Player(ownerSessionId, ownerPseudonym, now)
        val lobby =
            Lobby(
                id = LobbyId.generate(),
                ownerSessionId = ownerSessionId,
                players = mapOf(ownerSessionId to owner),
                state = LobbyLifecycleState.WAITING,
                gridConfig = defaultGridConfig,
                game = null,
                lastActivityAt = now,
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
                    // Reconnect path: bump lastActivityAt so an idle re-open keeps the lobby alive.
                    lobby.hasJoined(sessionId) -> lobby.touched(clock.now())
                    lobby.isFull() -> lobby
                    else -> {
                        val now = clock.now()
                        val player = Player(sessionId, pseudonym, now)
                        emitted = LobbyEvent.PlayerJoined(player)
                        lobby.copy(players = lobby.players + (sessionId to player), lastActivityAt = now)
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
    private val clock: Clock,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        newPseudonym: Pseudonym,
    ): UseCaseOutcome<Lobby> {
        val before = repo.findById(lobbyId) ?: return failure(UseCaseError.LobbyNotFound)
        if (!before.hasJoined(sessionId)) return failure(UseCaseError.PlayerNotInLobby)
        var renamed = false
        val updated =
            repo.mutate(lobbyId) { lobby ->
                val existing = lobby.players[sessionId] ?: return@mutate lobby
                renamed = true
                lobby.copy(
                    players = lobby.players + (sessionId to existing.copy(pseudonym = newPseudonym)),
                    lastActivityAt = clock.now(),
                )
            } ?: return failure(UseCaseError.LobbyNotFound)
        // Player left between findById and mutate; mutator no-oped silently.
        if (!renamed) return failure(UseCaseError.PlayerNotInLobby)
        return success(updated, listOf(LobbyEvent.PlayerRenamed(sessionId, newPseudonym)))
    }
}

/** Owner-only, WAITING-only: change the grid dimensions before Start. */
class SetGridConfigUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        config: GridConfig,
    ): UseCaseOutcome<Lobby> {
        val current = repo.findById(lobbyId) ?: return failure(UseCaseError.LobbyNotFound)
        if (!current.isOwner(sessionId)) return failure(UseCaseError.NotOwner)
        if (current.state != LobbyLifecycleState.WAITING) return failure(UseCaseError.InvalidState)
        var changed = false
        val updated =
            repo.mutate(lobbyId) { lobby ->
                // Re-verify inside the lock: a concurrent startGame may have transitioned the lobby.
                if (!lobby.isOwner(sessionId) || lobby.state != LobbyLifecycleState.WAITING) return@mutate lobby
                changed = true
                lobby.copy(gridConfig = config, lastActivityAt = clock.now())
            } ?: return failure(UseCaseError.LobbyNotFound)
        if (!changed) {
            return if (!updated.isOwner(sessionId)) failure(UseCaseError.NotOwner) else failure(UseCaseError.InvalidState)
        }
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
        // Fetch outside the lock — IO must not stall other lobbies' mutators.
        val puzzle = puzzleProvider.fetch(current.gridConfig.width, current.gridConfig.height)
        // Session is created inside the mutator so startedAt is stamped under the lock and a
        // concurrent double-tap cannot overwrite a live session with a second one.
        var session: GameSession? = null
        val updated =
            repo.mutate(lobbyId) { lobby ->
                if (!lobby.isOwner(sessionId) || lobby.state != LobbyLifecycleState.WAITING) return@mutate lobby
                val now = clock.now()
                val s = GameSession(puzzle, emptyMap(), now, null)
                session = s
                lobby.copy(state = LobbyLifecycleState.IN_PROGRESS, game = s, lastActivityAt = now)
            } ?: return failure(UseCaseError.LobbyNotFound)
        val started = session ?: return failure(UseCaseError.InvalidState)
        return success(updated, listOf(LobbyEvent.GameStarted(started)))
    }
}

/** Removes the player; transfers ownership or closes the lobby when the owner leaves. */
class LeaveLobbyUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
    ): UseCaseOutcome<Lobby?> {
        val events = mutableListOf<LobbyEvent>(LobbyEvent.PlayerLeft(sessionId))
        var playerWasPresent = false
        val updated =
            repo.mutate(lobbyId) { lobby ->
                if (!lobby.hasJoined(sessionId)) return@mutate lobby
                playerWasPresent = true
                val liveRemaining = lobby.players - sessionId
                if (liveRemaining.isEmpty()) {
                    // null signals the repo to delete atomically inside the lock,
                    // closing the window between "decide to delete" and "execute delete".
                    null
                } else {
                    // Compute nextOwner from the live snapshot inside the lock so a concurrent
                    // join/leave between the outer read and mutate cannot produce a ghost owner.
                    val nextOwner =
                        if (lobby.isOwner(sessionId)) {
                            liveRemaining.values.minBy { it.joinedAt }.sessionId
                        } else {
                            lobby.ownerSessionId
                        }
                    lobby.copy(players = liveRemaining, ownerSessionId = nextOwner, lastActivityAt = clock.now())
                }
            }
        // Distinguish: null+!present = lobby not found; null+present = deleted; non-null+!present = not a member.
        if (!playerWasPresent && updated == null) return failure(UseCaseError.LobbyNotFound)
        if (!playerWasPresent) return failure(UseCaseError.PlayerNotInLobby)
        if (updated == null) {
            events += LobbyEvent.LobbyClosed("last player left")
            return success(null, events)
        }
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
                    lobby.copy(state = LobbyLifecycleState.COMPLETED, game = completed, lastActivityAt = now)
                } else {
                    lobby.copy(game = nextSession, lastActivityAt = now)
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
