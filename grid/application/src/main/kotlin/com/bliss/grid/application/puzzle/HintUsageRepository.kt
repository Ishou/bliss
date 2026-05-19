package com.bliss.grid.application.puzzle

import java.sql.Connection
import java.util.UUID

/**
 * Per-(puzzle, user) hint counter. Decoupled from `PuzzleRepository` so a
 * shared puzzleId URL gives every caller their own bucket — a stranger
 * opening the link can't burn hints out from under the original player.
 *
 * Keyed on the authenticated user resolved from the `__Secure-ws_session`
 * cookie; the hints POST is authed-only.
 */
interface HintUsageRepository {
    /**
     * Atomic spend. Returns the new `hints_used` value when a hint was
     * granted, or `null` when the cap is already reached (no decrement
     * happened — the route maps null to 429). Implementations MUST be
     * single-statement-atomic so two concurrent spends at the cap can
     * never both succeed.
     *
     * The write path passes the locked [conn] from
     * [HintWriteCoordinator.withUserLock] so `trySpend` runs inside the
     * same transaction as `pg_advisory_xact_lock` and the under-lock
     * fresh cookie re-verify.
     */
    fun trySpend(
        conn: Connection,
        puzzleId: UUID,
        userId: UUID,
        hintsAllowed: Int,
    ): Int?

    /** Returns `hints_used` for ([puzzleId], [userId]), or 0 if no row exists. Used by the read path to embed `hintsRemaining`. */
    fun usedFor(
        puzzleId: UUID,
        userId: UUID,
    ): Int

    /**
     * RGPD Article 17. Removes every hint-usage row tied to [userId]
     * across all puzzles. Returns the number of rows deleted. Idempotent.
     */
    fun deleteByUser(userId: UUID): Int
}
