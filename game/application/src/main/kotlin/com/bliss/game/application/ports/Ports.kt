package com.bliss.game.application.ports

import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.Letter
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Position
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.analytics.AnalyticsEvent
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
     * Returns lobbies the given session is currently a member of, in every
     * lifecycle state (WAITING, IN_PROGRESS, COMPLETED), ordered by
     * lastActivityAt descending. Returns an empty list if the session is
     * not in any lobby. Used by the "My games" surface (ADR-0039).
     */
    suspend fun findBySessionId(sessionId: SessionId): List<Lobby>

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
