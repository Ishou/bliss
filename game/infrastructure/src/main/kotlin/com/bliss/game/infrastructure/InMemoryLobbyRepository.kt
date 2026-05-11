package com.bliss.game.infrastructure

import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.SessionId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// In-memory adapter; state is lost on restart — acceptable until the Postgres adapter lands.
class InMemoryLobbyRepository : LobbyRepository {
    private val store: ConcurrentHashMap<LobbyId, Lobby> = ConcurrentHashMap()
    private val locks: ConcurrentHashMap<LobbyId, ReentrantLock> = ConcurrentHashMap()

    private fun lockFor(id: LobbyId): ReentrantLock = locks.computeIfAbsent(id) { ReentrantLock() }

    override suspend fun findById(id: LobbyId): Lobby? = store[id]

    // O(n) scan; same trade-off as findWaitingByOwnerSession above. Volume
    // stays small in v1 and a Postgres adapter would index `code`.
    override suspend fun findByCode(code: LobbyCode): Lobby? = store.values.firstOrNull { it.code == code }

    // O(n) scan over the lobby map. Acceptable for v1 single-replica (ADR-0018 §3); a Postgres
    // adapter would index `(session_id)` via the membership table for this lookup. ConcurrentHashMap.values()
    // is weakly consistent — fine: this is a read-only projection for the "My games" surface (ADR-0039),
    // and a concurrently mutating lobby simply lands in the next call.
    override suspend fun findBySessionId(sessionId: SessionId): List<Lobby> =
        store.values
            .filter { it.players.containsKey(sessionId) }
            .sortedByDescending { it.lastActivityAt }

    override suspend fun save(lobby: Lobby): Lobby =
        lockFor(lobby.id).withLock {
            store[lobby.id] = lobby
            lobby
        }

    override suspend fun mutate(
        id: LobbyId,
        mutator: (Lobby) -> Lobby?,
    ): Lobby? =
        lockFor(id).withLock {
            val current = store[id] ?: return@withLock null
            val next = mutator(current)
            if (next == null) {
                store.remove(id)
                locks.remove(id)
                null
            } else {
                store[id] = next
                next
            }
        }

    override suspend fun delete(id: LobbyId) {
        lockFor(id).withLock {
            store.remove(id)
            locks.remove(id)
        }
    }

    // O(n) scan over the lobby map. Acceptable for v1 single-replica (ADR-0018 §3); a Postgres
    // adapter would index (owner_session_id, state) for this lookup. ConcurrentHashMap.values()
    // is weakly consistent — fine: callers re-validate inside mutate() before acting on the result.
    override suspend fun findWaitingByOwnerSession(ownerSessionId: SessionId): Lobby? =
        store.values.firstOrNull { it.ownerSessionId == ownerSessionId && it.state == LobbyLifecycleState.WAITING }

    override suspend fun findIdleWaiting(cutoff: Instant): List<Lobby> =
        store.values.filter { it.state == LobbyLifecycleState.WAITING && !it.lastActivityAt.isAfter(cutoff) }

    override suspend fun findIdleCompleted(cutoff: Instant): List<Lobby> =
        store.values.filter { it.state == LobbyLifecycleState.COMPLETED && !it.lastActivityAt.isAfter(cutoff) }
}
