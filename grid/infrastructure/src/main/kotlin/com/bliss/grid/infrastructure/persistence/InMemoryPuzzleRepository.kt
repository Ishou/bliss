package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.StoredPuzzle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory adapter for [PuzzleRepository]. Used in local dev / route tests
 * (when `DATABASE_URL` is unset) so the wire path stays exercisable without
 * standing up Postgres or Testcontainers per test class.
 *
 * Storage is unbounded for v1 single-replica posture; memory grows with the
 * number of distinct puzzleIds GET-ed during the process lifetime. Lost on
 * restart — a player who shared a URL across a server restart will see a
 * fresh grid from the new GET (acceptable trade-off; production runs
 * Postgres for persistence).
 */
class InMemoryPuzzleRepository : PuzzleRepository {
    private val store = ConcurrentHashMap<UUID, StoredPuzzle>()

    override fun get(puzzleId: UUID): StoredPuzzle? = store[puzzleId]

    override fun getOrCompute(
        puzzleId: UUID,
        factory: () -> StoredPuzzle?,
    ): StoredPuzzle? {
        // Fast path: hit. computeIfAbsent would also work but evaluates the
        // factory under a per-key lock; if generation is slow that serializes
        // unrelated GETs sharing the same hash bucket.
        store[puzzleId]?.let { return it }
        val produced = factory() ?: return null
        // putIfAbsent makes concurrent GETs on the same id observe a single
        // canonical grid — whoever inserted first wins; the late winner's
        // generated grid is discarded.
        return store.putIfAbsent(puzzleId, produced) ?: produced
    }

}
