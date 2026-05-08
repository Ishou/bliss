package com.bliss.game.application.ports

import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.analytics.AnalyticsEvent
import java.time.Instant

/**
 * Atomic lobby state operations. The infrastructure adapter (Wave D) holds a
 * per-lobby [java.util.concurrent.locks.ReentrantLock] under [mutate] so use
 * cases can call it without per-call locking concerns.
 */
interface LobbyRepository {
    suspend fun findById(id: LobbyId): Lobby?

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
    suspend fun record(
        event: AnalyticsEvent,
        sessionId: SessionId? = null,
    )
}
