package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.ClueId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory adapter for [ClueCooldownRepository]. Used in local dev and
 * route tests; not used in production (the Postgres adapter lands in
 * PR 3 and is the durable path).
 *
 * Concurrency: each bucket has an [AtomicLong] counter and its own
 * [ConcurrentHashMap] of cooldown rows. Concurrent
 * [recordGeneration] calls on the same bucket bump the counter
 * atomically and upsert their row sets independently. No locks held
 * across the row writes — same posture as
 * [InMemoryHintUsageRepository].
 *
 * Lost on restart, like the puzzle store. A bucket whose counter and
 * rows reset gets fresh variety after a process restart (degraded
 * gracefully — the cooldown is best-effort variety, not correctness).
 *
 * The roll function is injected so tests can pin the result (see
 * [InMemoryClueCooldownRepositoryTest] property check around the ADR-0031
 * "current_seq - last_used_seq < rolledN" invariant).
 */
class InMemoryClueCooldownRepository(
    private val randomCooldown: (rollMaxInclusive: Int) -> Int =
        { max -> ThreadLocalRandom.current().nextInt(1, max + 1) },
) : ClueCooldownRepository {
    private data class Row(
        val cooldownUntilSeq: Long,
        val lastUsedSeq: Long,
    )

    private val counters = ConcurrentHashMap<UUID, AtomicLong>()
    private val rows = ConcurrentHashMap<UUID, ConcurrentHashMap<ClueId, Row>>()

    override fun snapshot(bucketId: UUID): ClueCooldownRepository.Snapshot {
        val current = counters[bucketId]?.get() ?: 0L
        val active =
            rows[bucketId]
                ?.entries
                ?.asSequence()
                ?.filter { it.value.cooldownUntilSeq > current }
                ?.map { it.key }
                ?.toSet()
                ?: emptySet()
        return ClueCooldownRepository.Snapshot(currentSeq = current, onCooldown = active)
    }

    override fun recordGeneration(
        bucketId: UUID,
        usedClues: Collection<ClueId>,
        rollMaxInclusive: Int,
    ): Long {
        require(rollMaxInclusive >= 1) {
            "rollMaxInclusive must be >= 1, was $rollMaxInclusive"
        }
        val newSeq = counters.computeIfAbsent(bucketId) { AtomicLong(0L) }.incrementAndGet()
        if (usedClues.isNotEmpty()) {
            val sessionRows = rows.computeIfAbsent(bucketId) { ConcurrentHashMap() }
            for (clue in usedClues) {
                val n = randomCooldown(rollMaxInclusive)
                sessionRows[clue] = Row(cooldownUntilSeq = newSeq + n, lastUsedSeq = newSeq)
            }
        }
        return newSeq
    }

    override fun deleteBySession(bucketId: UUID): Int {
        val sessionRows = rows.remove(bucketId)
        counters.remove(bucketId)
        return sessionRows?.size ?: 0
    }
}
