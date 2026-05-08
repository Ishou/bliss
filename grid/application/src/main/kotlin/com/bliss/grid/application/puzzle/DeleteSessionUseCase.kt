package com.bliss.grid.application.puzzle

import java.util.UUID

/**
 * Right-to-erasure (RGPD Article 17, ADR-0025 §5). Removes every server-side
 * row attributable to [sessionId]. Today that's just `puzzle_hint_usage`; any
 * future table that retains a `session_id` column must extend this use case.
 *
 * Returns the count of rows deleted across all tables. The route exposes this
 * for client confirmation but the count is not load-bearing for compliance —
 * a successful (200) response with `deleted = 0` is the expected outcome for
 * a session that never used a hint.
 *
 * Matomo visit erasure is intentionally not invoked from here. The
 * daily-rotated salted hash (ADR-0025 §3) makes prior-day visits already
 * non-attributable to any individual; current-day visits become non-linkable
 * the moment the user clears their browser `localStorage` and gets a fresh
 * `sessionId` (a different daily hash). The privacy notice discloses this.
 */
class DeleteSessionUseCase(
    private val hintUsageRepository: HintUsageRepository,
) {
    fun execute(sessionId: UUID): Int = hintUsageRepository.deleteBySession(sessionId)
}
