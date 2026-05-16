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

    /**
     * Returns thin (id, totalLetterCells) summaries for the supplied ids,
     * in unspecified order. Missing ids — no row, or a pre-V4 row whose
     * `total_letter_cells` column is still NULL — are silently absent from
     * the result. The list endpoint zips the response back against its
     * request date list, so missing ids drop out of the wire output too.
     *
     * Default implementation falls back to `get(id)` on each id, deriving
     * the summary from `StoredPuzzle.totalLetterCells`. Production adapters
     * override with a single SQL round trip; test fakes that don't care
     * about the list endpoint inherit the default for free.
     */
    fun findSummariesByIds(puzzleIds: List<UUID>): List<StoredSummary> =
        puzzleIds.mapNotNull { id ->
            val stored = get(id) ?: return@mapNotNull null
            StoredSummary(puzzleId = id, totalLetterCells = stored.totalLetterCells)
        }
}

/**
 * Thin projection over the puzzles table for the archive endpoint.
 * See [PuzzleRepository.findSummariesByIds].
 */
data class StoredSummary(
    val puzzleId: UUID,
    val totalLetterCells: Int,
)

/**
 * Server-side puzzle snapshot. Carries the canonical [Grid] (with its
 * letters — server-private, never serialized to clients) plus the wire-side
 * fields needed to render the response on subsequent GETs.
 *
 * `totalLetterCells` is denormalised here so the list endpoint can return
 * thin summaries without re-reading every payload JSON document.
 */
data class StoredPuzzle(
    val grid: Grid,
    val title: String,
    val language: String,
    val hintsAllowed: Int,
    val createdAt: Instant,
) {
    val totalLetterCells: Int = grid.cells.values.count { it is LetterCell }
}
