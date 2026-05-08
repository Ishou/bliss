package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.HintUsageRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory adapter for [HintUsageRepository]. Pairs with
 * [InMemoryPuzzleRepository] in local dev / tests; not used in production
 * (the [PostgresHintUsageRepository] is the durable path).
 *
 * Atomic spend uses `AtomicInteger.compareAndSet` against a per-key counter
 * — concurrent spends on the same `(puzzleId, sessionId)` race cleanly
 * without locks, and a spend that would exceed the cap is rejected.
 *
 * Lost on restart, like the puzzle store. A player whose hints were all
 * spent gets a fresh budget after a process restart (degraded gracefully
 * — solo play, low stakes).
 */
class InMemoryHintUsageRepository : HintUsageRepository {
    private val counters = ConcurrentHashMap<Pair<UUID, UUID>, AtomicInteger>()

    override fun trySpend(
        puzzleId: UUID,
        sessionId: UUID,
        hintsAllowed: Int,
    ): Int? {
        val counter = counters.computeIfAbsent(puzzleId to sessionId) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= hintsAllowed) return null
            if (counter.compareAndSet(current, current + 1)) return current + 1
            // CAS failed — another spend won the race; loop and re-read.
        }
    }

    override fun deleteBySession(sessionId: UUID): Int {
        val keys = counters.keys.filter { it.second == sessionId }
        keys.forEach { counters.remove(it) }
        return keys.size
    }
}
