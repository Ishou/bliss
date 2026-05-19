package com.bliss.game.application.ports

import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.UserId
import com.bliss.game.domain.analytics.AnalyticsEvent
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Atomic lobby state operations. The infrastructure adapter (Wave D) holds a
 * per-lobby [java.util.concurrent.locks.ReentrantLock] under [mutate] so use
 * cases can call it without per-call locking concerns.
 */
interface LobbyRepository {
    suspend fun findById(id: LobbyId): Lobby?

    /**
     * Lookup by the human-friendly join [LobbyCode]. Returns `null` when no
     * lobby carries the code. Powers the Accueil "Rejoindre avec un code"
     * flow + `CreateLobbyUseCase`'s mint-collision check. O(n) in the
     * in-memory v1 — fine while lobby counts are small (ADR-0018 §3); a
     * Postgres adapter can index `code` when it lands.
     */
    suspend fun findByCode(code: LobbyCode): Lobby?

    /**
     * Returns lobbies the given session is currently a member of and that
     * have entered play — IN_PROGRESS or COMPLETED only — ordered by
     * lastActivityAt descending. WAITING (un-started) lobbies are excluded
     * because they are "salons d'attente", not "parties": surfacing them
     * conflates the two and produces 404-toast races when the WAITING TTL
     * elapses between the list fetch and a rejoin click (ADR-0039
     * amendment 2026-05-12). Returns an empty list if the session has no
     * matching lobby. Used by the "Mes parties" surface (ADR-0039).
     */
    suspend fun findBySessionId(sessionId: SessionId): List<Lobby>

    /**
     * RGPD Article 17 erasure (ADR-0039). Atomic per lobby. Idempotent.
     * This is the ONLY method that transfers lobby ownership — regular
     * LeaveLobbyUseCase keeps ownerSessionId by design.
     */
    suspend fun eraseSession(sessionId: SessionId): EraseSessionResult

    suspend fun save(lobby: Lobby): Lobby

    /**
     * Read-modify-write under a per-lobby lock. Returns the new state, or `null`
     * when no lobby exists for [id] or when the mutator returns `null` (delete signal).
     * A `null` return from the mutator atomically deletes the lobby inside the lock,
     * closing the TOCTOU window between "decide to delete" and "execute delete".
     * The mutator is invoked at most once.
     */
    suspend fun mutate(
        id: LobbyId,
        mutator: (Lobby) -> Lobby?,
    ): Lobby?

    suspend fun delete(id: LobbyId)

    /**
     * Returns the WAITING lobby owned by [ownerSessionId] if one exists. Used by
     * `CreateLobbyUseCase` for idempotency: a player who already owns a WAITING
     * lobby gets that lobby back instead of minting a new one. Currently O(n) over
     * the in-memory store — fine for v1 single-replica (ADR-0018 §3); a Postgres
     * adapter can index `(owner_session_id, state)` if/when it lands.
     */
    suspend fun findWaitingByOwnerSession(ownerSessionId: SessionId): Lobby?

    /**
     * Returns WAITING lobbies whose [Lobby.lastActivityAt] is at or before [cutoff].
     * Consumed by the lobby garbage collector to evict abandoned lobbies. Snapshot —
     * callers must re-validate inside [mutate] (or [delete]) to avoid TOCTOU between
     * the scan and the eviction.
     */
    suspend fun findIdleWaiting(cutoff: Instant): List<Lobby>

    /**
     * Returns COMPLETED lobbies whose [Lobby.lastActivityAt] is at or before [cutoff].
     * Consumed by the lobby garbage collector per the ADR-0039 §c retention matrix
     * (COMPLETED lobbies kept 7 days). Snapshot — callers must re-validate inside
     * [mutate] (or [delete]) to avoid TOCTOU between the scan and the eviction.
     */
    suspend fun findIdleCompleted(cutoff: Instant): List<Lobby>

    /** Anon→authed: sets userId + pseudonym on seats where sessionId == anonSessionId AND userId == null. Must be called on a [LobbyWriteCoordinator]-locked [conn]. Idempotent. Returns touched lobby ids. */
    suspend fun rebindAnonSeats(
        conn: Connection,
        anonSessionId: SessionId,
        userId: UserId,
        newPseudonym: Pseudonym,
    ): Set<LobbyId>

    /** Sign-out reversal of rebindAnonSeats: clears userId and reverts pseudonym on all seats for this user. Must be called on a [LobbyWriteCoordinator]-locked [conn]. Idempotent. Returns touched lobby ids. */
    suspend fun unbindUserSeats(
        conn: Connection,
        userId: UserId,
        anonPseudonym: Pseudonym,
    ): Set<LobbyId>

    /** ADR-0049 RGPD Article 17 user deletion: clear userId and replace pseudonym on every matching seat. Must be called on a [LobbyWriteCoordinator]-locked [conn]. Idempotent. Returns touched lobby ids. */
    suspend fun anonymizeUserSeats(
        conn: Connection,
        userId: UserId,
        replacementPseudonym: Pseudonym,
    ): Set<LobbyId>

    /** ADR-0049 user.renamed: refresh pseudonym on every seat for this userId without changing userId. Must be called on a [LobbyWriteCoordinator]-locked [conn]. Idempotent -- unchanged seats not returned. Returns touched lobby ids. */
    suspend fun refreshUserPseudonym(
        conn: Connection,
        userId: UserId,
        newPseudonym: Pseudonym,
    ): Set<LobbyId>
}

/**
 * Fetches a fresh [GamePuzzle] for the lobby owner's chosen dimensions. The
 * HTTP adapter (Wave D) calls grid/'s `GET /v1/puzzles/{id}?width&height`;
 * this layer is unaware of transport.
 */
interface PuzzleProvider {
    suspend fun fetch(
        width: Int,
        height: Int,
    ): GamePuzzle
}

/** Testable time. `SystemClock` lives in infrastructure (Wave D). */
interface Clock {
    fun now(): Instant
}

/** ADR-0049 out-bound port: called with touched lobby ids after anonymizeUserSeats/refreshUserPseudonym to push fresh LobbyState snapshots to live clients. */
interface LobbyRosterBroadcaster {
    suspend fun notifyRosterChanged(lobbyId: LobbyId)
}

/**
 * Out-bound port for ephemeral presence events ([LobbyEvent.Typing], [LobbyEvent.Idle],
 * [LobbyEvent.ConnectionLost], [LobbyEvent.CursorBumped]). The infrastructure adapter wires this
 * to `SessionManager.broadcast`; the application layer does not know transport.
 *
 * Why a dedicated port (vs. returning events from a use case as `LobbyUseCases` does): the
 * presence aggregator's edge events fire on internal timer transitions, not in response to a
 * single use-case invocation that has a return value to attach events to. The aggregator pushes
 * directly through this port whenever a threshold is crossed.
 */
interface PresenceBroadcaster {
    suspend fun broadcast(
        lobbyId: LobbyId,
        event: LobbyEvent,
    )
}

/**
 * Out-bound port for product analytics events ([AnalyticsEvent] subtypes). Adapters
 * (Matomo in production, Noop in dev/tests) live in `:game:infrastructure`.
 *
 * Implementations MUST be fire-and-forget: a call to [record] never blocks the
 * caller, never propagates a failure, and never throws. If the analytics backend
 * is unreachable or returns an error, the implementation logs and drops the event.
 *
 * `sessionId` is optional. When present, the adapter computes a daily-rotated
 * salted hash (ADR-0025 §3) so the visitor is identifiable within a day but not
 * across days. The raw `SessionId` is never sent to the analytics backend.
 */
interface AnalyticsEventSink {
    /**
     * Non-blocking by contract — implementations launch the work into their own
     * supervised scope and return immediately. Callers may invoke from synchronous
     * or coroutine code; never throws, never propagates failure.
     */
    fun record(
        event: AnalyticsEvent,
        sessionId: SessionId? = null,
    )

    companion object {
        /**
         * In-process no-op sink. Use as a default for use-case constructors so existing
         * tests continue to compile without Matomo wiring, and as the production fallback
         * when Matomo env vars are not configured.
         */
        val Noop: AnalyticsEventSink =
            object : AnalyticsEventSink {
                override fun record(
                    event: AnalyticsEvent,
                    sessionId: SessionId?,
                ) = Unit
            }
    }
}

/**
 * Asks grid which currently-filled cells don't match the canonical solution.
 *
 * Per the v1 wire spec (grid/api/openapi.yaml `LetterCell`), grid strips
 * letter answers from `GET /v1/puzzles/{id}` so the browser can never see
 * the solution and cheat. game-api therefore can't validate locally —
 * `LetterCell.answer` is null on every cell of every puzzle it ever
 * receives. To know whether a player just completed a word, `UpdateCellUseCase`
 * delegates to this port (HTTP adapter calls `POST /v1/puzzles/{id}/validate`,
 * mirroring the FE solo path's `PuzzleSolver.validate`).
 *
 * Returns the set of positions whose submitted letter does NOT match the
 * canonical solution. A position absent from the set is correct; a
 * position absent from the request is reported as incorrect (the v1
 * grid endpoint treats unfilled cells as wrong, same as FE solo).
 */
interface WordValidator {
    suspend fun incorrectPositions(
        puzzleId: UUID,
        filled: Map<Position, Letter>,
    ): Set<Position>
}

/**
 * Aggregated counts returned by [LobbyRepository.eraseSession]. Each field maps
 * to one ADR-0039 cascade rule; the sum is what `DELETE /v1/sessions/{sessionId}`
 * surfaces on the wire.
 */
data class EraseSessionResult(
    val deletedLobbies: Int,
    val transferredLobbies: Int,
    val removedPlayerships: Int,
    val anonymisedEntries: Int,
) {
    companion object {
        val Empty = EraseSessionResult(0, 0, 0, 0)
    }
}
