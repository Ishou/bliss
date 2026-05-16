package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
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

    /** Missing ids (no row or NULL total_letter_cells) are silently absent from the result. */
    fun findSummariesByIds(puzzleIds: List<UUID>): List<StoredSummary> =
        puzzleIds.mapNotNull { id ->
            val stored = get(id) ?: return@mapNotNull null
            StoredSummary(puzzleId = id, totalLetterCells = stored.totalLetterCells)
        }
}

/** Thin projection used by the archive list endpoint. */
data class StoredSummary(
    val puzzleId: UUID,
    val totalLetterCells: Int,
)

/** Server-private snapshot (Grid letters never serialised); totalLetterCells denormalised for archive queries. */
data class StoredPuzzle(
    val grid: Grid,
    val title: String,
    val language: String,
    val hintsAllowed: Int,
    val createdAt: Instant,
) {
    val totalLetterCells: Int = grid.cells.values.count { it is LetterCell }
}
