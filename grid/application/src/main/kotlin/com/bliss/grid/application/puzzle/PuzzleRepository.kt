package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.model.Grid
import java.time.Instant
import java.util.UUID

/**
 * Server-side puzzle persistence keyed by the wire `puzzleId`. Used by
 * `LoadOrGeneratePuzzleUseCase` to keep GET idempotent and by the
 * validate / hints flows to find the canonical solution attached to an id.
 *
 * Two adapters in v1:
 *  - `InMemoryPuzzleRepository`  — local dev, tests, no DB.
 *  - `PostgresPuzzleRepository` — production, atomic getOrCompute via
 *    `INSERT ... ON CONFLICT DO NOTHING RETURNING`.
 *
 * Module wiring picks the adapter on `Database.dataSource()`: non-null →
 * Postgres, null → in-memory. Production guarantees `DATABASE_URL` via
 * Helm chart env (deployment-time concern, not app code).
 */
interface PuzzleRepository {
    /** Snapshot for the supplied id, or `null` if no GET has populated it yet. */
    fun get(puzzleId: UUID): StoredPuzzle?

    /**
     * Atomically returns the existing snapshot or creates one via [factory]
     * and stores it. The lambda runs at most once per missing id; concurrent
     * GETs racing on the same id observe a single canonical grid.
     *
     * Returns `null` only if [factory] itself returns `null` (generation
     * failure); the use case maps that to a 422.
     */
    fun getOrCompute(
        puzzleId: UUID,
        factory: () -> StoredPuzzle?,
    ): StoredPuzzle?
}

/**
 * Server-side puzzle snapshot. Carries the canonical [Grid] (with its
 * letters — server-private, never serialized to clients) plus the wire-side
 * fields needed to render the response on subsequent GETs.
 */
data class StoredPuzzle(
    val grid: Grid,
    val title: String,
    val language: String,
    val hintsAllowed: Int,
    val createdAt: Instant,
)
