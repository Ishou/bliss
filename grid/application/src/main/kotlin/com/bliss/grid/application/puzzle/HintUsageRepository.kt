package com.bliss.grid.application.puzzle

import java.util.UUID

/**
 * Per-(puzzle, player) hint counter. Decoupled from `PuzzleRepository` so a
 * shared puzzleId URL gives every caller their own bucket — a stranger
 * opening the link can't burn hints out from under the original player.
 *
 * The wire schema documents identification as a server concern; v1 keys on
 * the X-Session-Id header (UUID v7, mirrors game/api's SessionId).
 */
interface HintUsageRepository {
    /**
     * Atomic spend. Returns the new `hints_used` value when a hint was
     * granted, or `null` when the cap is already reached (no decrement
     * happened — the route maps null to 429). Implementations MUST be
     * single-statement-atomic so two concurrent spends at the cap can
     * never both succeed.
     */
    fun trySpend(
        puzzleId: UUID,
        sessionId: UUID,
        hintsAllowed: Int,
    ): Int?

    /**
     * Right-to-erasure (RGPD Article 17, ADR-0025 §5). Removes every
     * hint-usage row tied to [sessionId] across all puzzles. Returns the
     * number of rows deleted (0 if the session never used a hint).
     */
    fun deleteBySession(sessionId: UUID): Int
}
