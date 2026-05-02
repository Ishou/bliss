package com.bliss.game.infrastructure

import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.domain.Lobby
import com.bliss.game.domain.LobbyId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-local [LobbyRepository] backed by [ConcurrentHashMap]. Each lobby
 * gets its own [ReentrantLock]; [mutate] and [delete] run their critical
 * section under that lock so the use-case-layer TOCTOU guards introduced in
 * PR #127's review hold (decide-and-act inside the same lambda).
 *
 * This is the bootstrap adapter for `:game:infrastructure`. A Postgres-backed
 * implementation lands in a later Wave D PR; until then, lobby state is lost
 * on process restart, which is acceptable for the early multiplayer slices.
 */
class InMemoryLobbyRepository : LobbyRepository {
    private val store: ConcurrentHashMap<LobbyId, Lobby> = ConcurrentHashMap()
    private val locks: ConcurrentHashMap<LobbyId, ReentrantLock> = ConcurrentHashMap()

    private fun lockFor(id: LobbyId): ReentrantLock = locks.computeIfAbsent(id) { ReentrantLock() }

    override suspend fun findById(id: LobbyId): Lobby? = store[id]

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
        }
        // Drop the lock entry only after the critical section above releases.
        // Concurrent callers that observed this lock instance will block on it
        // and then see store[id] == null, which is the correct deleted state.
        locks.remove(id)
    }
}
