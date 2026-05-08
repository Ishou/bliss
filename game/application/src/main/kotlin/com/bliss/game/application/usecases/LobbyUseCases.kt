package com.bliss.game.application.usecases

import com.bliss.game.application.ports.AnalyticsEventSink
import com.bliss.game.application.ports.Clock
import com.bliss.game.application.ports.LobbyEvent
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.application.ports.PuzzleProvider
import com.bliss.game.application.ports.WordValidator
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
import com.bliss.game.domain.analytics.AnalyticsEvent
import com.bliss.game.domain.wordsContaining
import java.time.Duration
import java.time.Instant

private fun GridConfig.toLabel(): String = "${width}x$height"

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
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
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
        val saved = repo.save(lobby)
        analyticsEventSink.record(AnalyticsEvent.LobbyCreated(saved.gridConfig.toLabel()), ownerSessionId)
        return UseCaseResult(saved, listOf(LobbyEvent.PlayerJoined(owner)))
    }
}

/** Idempotent join — re-joining with the same sessionId emits no event (reconnect path). */
class JoinLobbyUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
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
        if (emitted != null) {
            analyticsEventSink.record(AnalyticsEvent.LobbyJoined(updated.players.size), sessionId)
        }
        return success(updated, listOfNotNull(emitted))
    }
}

/** Updates the caller's pseudonym; player must already be in the lobby. */
class RenameSelfUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
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
        analyticsEventSink.record(AnalyticsEvent.PlayerRenamed, sessionId)
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
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
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
        analyticsEventSink.record(
            AnalyticsEvent.GameStarted(updated.gridConfig.toLabel(), updated.players.size),
            sessionId,
        )
        return success(updated, listOf(LobbyEvent.GameStarted(started)))
    }
}

/** Removes the player; transfers ownership or closes the lobby when the owner leaves. */
class LeaveLobbyUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
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
        analyticsEventSink.record(AnalyticsEvent.LobbyLeft, sessionId)
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
 *
 * Word lock detection: per the v1 wire (grid/api/openapi.yaml `LetterCell`),
 * the canonical letter is stripped from `GET /v1/puzzles/{id}` so the
 * client (and game-api) never see the solution. To know whether a fill
 * just completed a correct word, this use case delegates to
 * [WordValidator] (HTTP adapter calls grid's `POST /validate`). The
 * validator is queried OUTSIDE the per-lobby mutator so the lock is not
 * held across an HTTP call.
 */
class UpdateCellUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val wordValidator: WordValidator,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        position: Position,
        letter: Letter?,
    ): UseCaseOutcome<Lobby> {
        // Step 1: write the cell entry (or clear) and capture the post-state
        // we'll need to drive lock detection. Locked-cell writes short-circuit
        // here. We also evaluate isSolved() inside the mutator so a fully-
        // correct grid still emits GameSolved + transitions to COMPLETED on
        // the same write — the lock detection in step 2 is independent.
        var writtenAt: Instant? = null
        var entriesAfter: Map<Position, CellEntry> = emptyMap()
        var solved: Pair<Long, Map<Position, CellEntry>>? = null
        val updated =
            repo.mutate(lobbyId) { lobby ->
                if (lobby.state != LobbyLifecycleState.IN_PROGRESS) return@mutate lobby
                if (!lobby.hasJoined(sessionId)) return@mutate lobby
                val session = lobby.game ?: return@mutate lobby
                // Locked cells silently ignore writes — no event, no broadcast, no lastActivityAt
                // bump (so peers' idle timers do not reset on attempts to overwrite a sage cell).
                if (position in session.lockedPositions) return@mutate lobby
                val now = clock.now().also { writtenAt = it }
                val entries =
                    if (letter == null) {
                        session.entries - position
                    } else {
                        session.entries + (position to CellEntry(sessionId, letter, now))
                    }
                entriesAfter = entries
                val nextSession = session.copy(entries = entries)
                if (nextSession.isSolved() && session.completedAt == null) {
                    val durationMs = Duration.between(session.startedAt, now).toMillis()
                    solved = durationMs to entries
                    analyticsEventSink.record(
                        AnalyticsEvent.GameSolved(
                            gridSize = lobby.gridConfig.toLabel(),
                            playerCount = lobby.players.size,
                            durationMs = durationMs,
                        ),
                        sessionId,
                    )
                    lobby.copy(
                        state = LobbyLifecycleState.COMPLETED,
                        game = nextSession.copy(completedAt = now),
                        lastActivityAt = now,
                    )
                } else {
                    lobby.copy(game = nextSession, lastActivityAt = now)
                }
            } ?: return failure(UseCaseError.LobbyNotFound)
        // writtenAt is null when the mutator short-circuited (player not in lobby, not IN_PROGRESS,
        // or position already locked). The locked-no-op case must surface as success-with-no-events.
        val stamp = writtenAt ?: return passthroughOrFailure(updated, sessionId)

        val events = mutableListOf<LobbyEvent>(LobbyEvent.CellUpdated(sessionId, position, letter, stamp))

        // Step 2: only ask grid about the words that contain the just-written
        // position. If none of them are fully filled, skip the HTTP call.
        val session = updated.game ?: return success(updated, events).withSolved(solved)
        val candidateWords = candidateWordsToCheck(session, position, entriesAfter)
        if (candidateWords.isEmpty() || letter == null) {
            return success(updated, events).withSolved(solved)
        }

        val incorrect =
            try {
                wordValidator.incorrectPositions(session.puzzle.id, lettersOf(entriesAfter))
            } catch (cause: Exception) {
                // Validator failure must NOT take down the cellUpdate. The cell
                // entry is already committed; the player will still see their
                // letter. The lock just won't fire on this keystroke.
                return success(updated, events).withSolved(solved)
            }
        val newLocks =
            candidateWords
                .filter { word -> word.none { it in incorrect } }
                .flatten()
                .toSet()
        if (newLocks.isEmpty()) return success(updated, events).withSolved(solved)

        // Step 3: re-enter the mutator to commit the locks. A concurrent write
        // could have changed entries; locks are monotonically additive so the
        // worst case is locking a position whose live letter no longer matches
        // — at that point the cell already holds the correct letter though
        // (peers can't see it changed), so the visible UX stays consistent.
        repo.mutate(lobbyId) { lobby ->
            val s = lobby.game ?: return@mutate lobby
            lobby.copy(
                game = s.copy(lockedPositions = s.lockedPositions + newLocks),
                lastActivityAt = stamp,
            )
        }
        events += LobbyEvent.WordLocked(newLocks, stamp)
        val finalLobby = repo.findById(lobbyId) ?: updated
        return success(finalLobby, events).withSolved(solved)
    }

    private fun UseCaseOutcome<Lobby>.withSolved(solved: Pair<Long, Map<Position, CellEntry>>?): UseCaseOutcome<Lobby> =
        if (solved == null) {
            this
        } else {
            when (this) {
                is UseCaseOutcome.Success ->
                    success(
                        result.value,
                        result.events + LobbyEvent.GameSolved(solved.first, solved.second),
                    )
                is UseCaseOutcome.Failure -> this
            }
        }

    /**
     * Returns the words containing [justWritten] that are now fully filled and not yet
     * locked. We only ask the validator about these — the request is bounded by the
     * candidate-word count (1 or 2 per cell, matching across × down), not by grid size.
     */
    private fun candidateWordsToCheck(
        session: GameSession,
        justWritten: Position,
        entries: Map<Position, CellEntry>,
    ): List<List<Position>> {
        val candidates = mutableListOf<List<Position>>()
        for (word in session.puzzle.wordsContaining(justWritten)) {
            if (word.any { it in session.lockedPositions }) continue
            if (word.all { entries[it] != null }) candidates += word
        }
        return candidates
    }

    private fun lettersOf(entries: Map<Position, CellEntry>): Map<Position, Letter> = entries.mapValues { (_, entry) -> entry.letter }

    private fun passthroughOrFailure(
        lobby: Lobby,
        sessionId: SessionId,
    ): UseCaseOutcome<Lobby> =
        when {
            lobby.state != LobbyLifecycleState.IN_PROGRESS -> failure(UseCaseError.InvalidState)
            !lobby.hasJoined(sessionId) -> failure(UseCaseError.PlayerNotInLobby)
            // Otherwise the short-circuit was a locked-cell no-op: success with no events.
            else -> success(lobby, emptyList())
        }
}
