package com.bliss.game.infrastructure

import com.bliss.game.application.ports.EraseSessionResult
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyCode
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.LobbyLifecycleState
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.UserId
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

    // owner OR member, state != WAITING; ownership survives leave-grace (ADR-0039, 2026-05-13).
    override suspend fun findBySessionId(sessionId: SessionId): List<Lobby> =
        store.values
            .filter {
                (it.ownerSessionId == sessionId || it.players.containsKey(sessionId)) &&
                    it.state != LobbyLifecycleState.WAITING
            }.sortedByDescending { it.lastActivityAt }

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

    // RGPD Article 17 erasure (ADR-0039). Snapshot the affected lobby ids first, then
    // process each under its own per-lobby lock so the cascade is atomic per lobby.
    // ConcurrentHashMap.values() is weakly consistent — fine, we re-validate inside
    // the lock and a lobby created after the snapshot cannot reference an erased
    // session (the client cleared its session id before issuing the DELETE).
    override suspend fun eraseSession(sessionId: SessionId): EraseSessionResult {
        var deletedLobbies = 0
        var transferredLobbies = 0
        var removedPlayerships = 0
        var anonymisedEntries = 0
        val targets = store.values.filter { it.players.containsKey(sessionId) }.map { it.id }
        for (id in targets) {
            lockFor(id).withLock {
                val current = store[id] ?: return@withLock
                if (!current.players.containsKey(sessionId)) return@withLock
                val remaining = current.players - sessionId
                if (current.isOwner(sessionId) && remaining.isEmpty()) {
                    // Rule 1: owner is the sole player. Delete the lobby outright.
                    store.remove(id)
                    locks.remove(id)
                    deletedLobbies += 1
                    return@withLock
                }
                removedPlayerships += 1
                val newOwner =
                    if (current.isOwner(sessionId)) {
                        // Rule 2: owner + others. Earliest-joined remaining player inherits.
                        transferredLobbies += 1
                        remaining.values.minBy { it.joinedAt }.sessionId
                    } else {
                        // Rule 3: non-owner. Ownership unchanged.
                        current.ownerSessionId
                    }
                val newGame =
                    current.game?.let { game ->
                        var count = 0
                        val rewritten =
                            game.entries.mapValues { (_, entry) ->
                                if (entry.sessionId == sessionId) {
                                    count += 1
                                    entry.copy(sessionId = SessionId.ANON)
                                } else {
                                    entry
                                }
                            }
                        anonymisedEntries += count
                        game.copy(entries = rewritten)
                    }
                store[id] =
                    current.copy(
                        players = remaining,
                        ownerSessionId = newOwner,
                        game = newGame,
                    )
            }
        }
        return EraseSessionResult(deletedLobbies, transferredLobbies, removedPlayerships, anonymisedEntries)
    }

    override suspend fun rebindAnonSeats(
        anonSessionId: SessionId,
        userId: UserId,
        newPseudonym: Pseudonym,
    ): Set<LobbyId> {
        val touched = mutableSetOf<LobbyId>()
        val targets = store.values.filter { it.players[anonSessionId]?.userId == null }.map { it.id }
        for (id in targets) {
            lockFor(id).withLock {
                val current = store[id] ?: return@withLock
                val seat = current.players[anonSessionId] ?: return@withLock
                if (seat.userId != null) return@withLock
                val updated = seat.copy(userId = userId, pseudonym = newPseudonym)
                store[id] = current.copy(players = current.players + (anonSessionId to updated))
                touched += id
            }
        }
        return touched
    }

    override suspend fun unbindUserSeats(
        userId: UserId,
        anonPseudonym: Pseudonym,
    ): Set<LobbyId> {
        val touched = mutableSetOf<LobbyId>()
        val targets = store.values.filter { lobby -> lobby.players.values.any { it.userId == userId } }.map { it.id }
        for (id in targets) {
            lockFor(id).withLock {
                val current = store[id] ?: return@withLock
                val matches = current.players.values.filter { it.userId == userId }
                if (matches.isEmpty()) return@withLock
                val newPlayers =
                    current.players.mapValues { (_, player) ->
                        if (player.userId == userId) player.copy(userId = null, pseudonym = anonPseudonym) else player
                    }
                store[id] = current.copy(players = newPlayers)
                touched += id
            }
        }
        return touched
    }
}
