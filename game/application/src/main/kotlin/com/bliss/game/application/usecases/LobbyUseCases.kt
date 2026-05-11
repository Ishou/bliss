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
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.Player
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.analytics.AnalyticsEvent
import com.bliss.game.domain.wordsContaining
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant

private fun GridConfig.toLabel(): String = "${width}x$height"

/**
 * Shared collision-bounded `LobbyCode` minter — used by
 * [CreateLobbyUseCase] and [RotateLobbyCodeUseCase]. 32^6 keyspace makes
 * collisions vanishingly unlikely; the bounded retry fails loudly on a
 * future volume regression instead of returning a duplicate code. Same
 * TOCTOU caveat as elsewhere — Postgres `UNIQUE (code)` will tighten it.
 */
private const val MAX_CODE_MINT_ATTEMPTS = 8

private suspend fun mintUniqueCode(repo: LobbyRepository): LobbyCode {
    repeat(MAX_CODE_MINT_ATTEMPTS) {
        val candidate = LobbyCode.generate()
        if (repo.findByCode(candidate) == null) return candidate
    }
    error(
        "LobbyCode mint exhausted $MAX_CODE_MINT_ATTEMPTS attempts - keyspace saturation or a generator bug; investigate before retrying.",
    )
}

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
    private val defaultGridConfig: GridConfig = GridConfig(15, 12),
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
        val code = mintUniqueCode(repo)
        val lobby =
            Lobby(
                id = LobbyId.generate(),
                ownerSessionId = ownerSessionId,
                players = mapOf(ownerSessionId to owner),
                state = LobbyLifecycleState.WAITING,
                gridConfig = defaultGridConfig,
                game = null,
                lastActivityAt = now,
                code = code,
            )
        val saved = repo.save(lobby)
        analyticsEventSink.record(AnalyticsEvent.LobbyCreated(saved.gridConfig.toLabel()), ownerSessionId)
        return UseCaseResult(saved, listOf(LobbyEvent.PlayerJoined(owner)))
    }
}

/**
 * Owner-only: re-mint the lobby's [LobbyCode] in place (ADR-0029).
 * Membership and game state are unchanged; reconnects key on
 * `sessionId` so already-joined players keep their seats. Owner check
 * is re-verified inside the mutator (canonical TOCTOU posture) — a
 * concurrent ownership transfer is absorbed by leaving the lobby
 * unchanged.
 */
class RotateLobbyCodeUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
    ): UseCaseOutcome<Lobby> {
        val current = repo.findById(lobbyId) ?: return failure(UseCaseError.LobbyNotFound)
        if (!current.isOwner(sessionId)) return failure(UseCaseError.NotOwner)
        val newCode = mintUniqueCode(repo)
        var rotated = false
        val updated =
            repo.mutate(lobbyId) { lobby ->
                if (!lobby.isOwner(sessionId)) return@mutate lobby
                rotated = true
                lobby.copy(code = newCode, lastActivityAt = clock.now())
            } ?: return failure(UseCaseError.LobbyNotFound)
        if (!rotated) return failure(UseCaseError.NotOwner)
        analyticsEventSink.record(AnalyticsEvent.LobbyCodeRotated, sessionId)
        return success(updated, listOf(LobbyEvent.CodeRotated(newCode)))
    }
}

/**
 * Idempotent join — re-joining with the same sessionId emits no event (reconnect path).
 *
 * ADR-0027: new joiners must present `code` matching `lobby.code`; the
 * mutator records `wrongCode = true` on a mismatch so the post-mutate
 * branch can return [UseCaseError.WrongCode] without conflating it with
 * `LobbyFull` (both leave the lobby unchanged). Reconnects (sessionId
 * already a member) bypass the check by construction — no code is ever
 * read on that branch.
 */
class JoinLobbyUseCase(
    private val repo: LobbyRepository,
    private val clock: Clock,
    private val analyticsEventSink: AnalyticsEventSink = AnalyticsEventSink.Noop,
) {
    suspend operator fun invoke(
        lobbyId: LobbyId,
        sessionId: SessionId,
        pseudonym: Pseudonym,
        code: String?,
    ): UseCaseOutcome<Lobby> {
        var emitted: LobbyEvent? = null
        var wrongCode = false
        val updated =
            repo.mutate(lobbyId) { lobby ->
                when {
                    // Reconnect path: bump lastActivityAt so an idle re-open keeps the lobby alive.
                    // Code is intentionally NOT checked here — see ADR-0027.
                    lobby.hasJoined(sessionId) -> lobby.touched(clock.now())
                    code != lobby.code.value -> {
                        wrongCode = true
                        lobby
                    }
                    lobby.isFull() -> lobby
                    else -> {
                        val now = clock.now()
                        val player = Player(sessionId, pseudonym, now)
                        emitted = LobbyEvent.PlayerJoined(player)
                        lobby.copy(players = lobby.players + (sessionId to player), lastActivityAt = now)
                    }
                }
            } ?: return failure(UseCaseError.LobbyNotFound)
        if (wrongCode) return failure(UseCaseError.WrongCode)
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

/**
 * Removes a player from a lobby. Does NOT transfer ownership when the
 * owner leaves — the owner is expected to return via My-games
 * (ADR-0039). The lobby persists even when the last player leaves;
 * cleanup is handled by [LobbyGarbageCollector]'s state-specific TTL.
 *
 * Manual ownership transfer is intentionally out of scope. The only
 * code path that transfers ownership is RGPD erasure (see ADR-0039 §f
 * and EraseSessionUseCase), where the user is gone permanently and
 * leaving a "dead owner" would lock the rest out of owner-gated
 * actions.
 *
 * If an owner clears localStorage without erasing, owner-only actions
 * (start, kick) become unavailable until they rejoin; non-owner play
 * continues. Acceptable until OAuth.
 */
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
                // Remove the player but keep ownerSessionId unchanged on every branch.
                // The lobby persists even when emptied; GC TTLs (see [LobbyGarbageCollector]) handle cleanup.
                lobby.copy(
                    players = lobby.players - sessionId,
                    lastActivityAt = clock.now(),
                )
            }
        // mutate's mutator never returns null, so updated == null
        // is unambiguously "lobby with this id does not exist" (the repo's only other
        // null path). PlayerNotInLobby is signalled by playerWasPresent=false on a
        // non-null mutate return — the mutator short-circuited without mutating.
        if (updated == null) return failure(UseCaseError.LobbyNotFound)
        if (!playerWasPresent) return failure(UseCaseError.PlayerNotInLobby)
        analyticsEventSink.record(AnalyticsEvent.LobbyLeft, sessionId)
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
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                // Validator failure must NOT take down the cellUpdate. The cell
                // entry is already committed; the player will still see their
                // letter. The lock just won't fire on this keystroke.
                return success(updated, events).withSolved(solved)
            }
        // Positions that just transitioned to locked. Words crossing an
        // already-locked word reuse its cells — those cells are already
        // sage on every client, so we emit only the freshly-locked ones.
        // The WordLocked event payload becomes the diff, not the union;
        // re-broadcasting an already-locked position would be wire noise.
        val newLocks =
            candidateWords
                .asSequence()
                .filter { word -> word.none { it in incorrect } }
                .flatMap { it.asSequence() }
                .filter { it !in session.lockedPositions }
                .toSet()
        if (newLocks.isEmpty()) return success(updated, events).withSolved(solved)

        // Step 3: re-enter the mutator to commit the locks. Filter to positions
        // whose live letter still matches what was validated — a concurrent
        // UpdateCellUseCase may have written a different letter between step 1
        // and here. That write was already broadcast via cellUpdated, so locking
        // a position with a stale letter would show peers a sage cell with the
        // wrong letter. Leaving it unlocked lets the correct-letter player
        // retype to retrigger the lock.
        var actualLocks = emptySet<Position>()
        repo.mutate(lobbyId) { lobby ->
            val s = lobby.game ?: return@mutate lobby
            val stillCorrect =
                newLocks
                    .filter { pos ->
                        s.entries[pos]?.letter == entriesAfter[pos]?.letter
                    }.toSet()
            if (stillCorrect.isEmpty()) return@mutate lobby
            actualLocks = stillCorrect
            lobby.copy(
                game = s.copy(lockedPositions = s.lockedPositions + stillCorrect),
                lastActivityAt = stamp,
            )
        }
        if (actualLocks.isEmpty()) return success(updated, events).withSolved(solved)
        events += LobbyEvent.WordLocked(actualLocks, stamp)
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
            // Skip only if the entire word is already locked — a perpendicular
            // word crossing a locked one reuses one cell but its other cells
            // still need to be validated. Skipping on `any` (the previous
            // behavior) silently dropped every word that crossed a lock.
            if (word.all { it in session.lockedPositions }) continue
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
