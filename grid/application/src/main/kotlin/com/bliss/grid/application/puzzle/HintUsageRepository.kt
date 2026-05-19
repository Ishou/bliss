package com.bliss.grid.application.puzzle

import java.sql.Connection
import java.util.UUID

/** Per-(puzzle, user) hint counter; each user has an independent spend bucket even on a shared puzzleId URL. */
interface HintUsageRepository {
    /** Atomic spend; returns new hints_used on success or null when cap reached (maps to 429). Must be called on the advisory-locked [conn]. */
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

    /** GDPR Article 17; removes all hint rows for [userId] across puzzles. Returns rows deleted. Idempotent. */
    fun deleteByUser(userId: UUID): Int
}
