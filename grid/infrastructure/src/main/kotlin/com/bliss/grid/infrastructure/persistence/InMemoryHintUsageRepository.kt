package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.HintUsageRepository
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory adapter for [HintUsageRepository]. Pairs with
 * [InMemoryPuzzleRepository] in local dev / tests; not used in production
 * (the [PostgresHintUsageRepository] is the durable path).
 *
 * The [Connection] parameter is ignored — there is no transactional state
 * to thread through; concurrency is handled with `AtomicInteger.CAS`.
 */
class InMemoryHintUsageRepository : HintUsageRepository {
    private val counters = ConcurrentHashMap<Pair<UUID, UUID>, AtomicInteger>()

    override fun trySpend(
        conn: Connection,
        puzzleId: UUID,
        userId: UUID,
        hintsAllowed: Int,
    ): Int? = spend(puzzleId, userId, hintsAllowed)

    fun trySpend(
        puzzleId: UUID,
        userId: UUID,
        hintsAllowed: Int,
    ): Int? = spend(puzzleId, userId, hintsAllowed)

    private fun spend(
        puzzleId: UUID,
        userId: UUID,
        hintsAllowed: Int,
    ): Int? {
        val counter = counters.computeIfAbsent(puzzleId to userId) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= hintsAllowed) return null
            if (counter.compareAndSet(current, current + 1)) return current + 1
        }
    }

    override fun usedFor(
        puzzleId: UUID,
        userId: UUID,
    ): Int = counters[puzzleId to userId]?.get() ?: 0

    override fun deleteByUser(userId: UUID): Int {
        val keys = counters.keys.filter { it.second == userId }
        keys.forEach { counters.remove(it) }
        return keys.size
    }
}
