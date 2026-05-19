package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.ClueCooldownRepository
import java.util.UUID

/**
 * Right-to-erasure (RGPD Article 17, ADR-0025 §5) for anonymous session
 * data. Removes every server-side row attributable to [sessionId] and
 * returns the total deleted across all extended tables. Today that covers
 * `clue_cooldown` whenever a non-null [cooldownRepository] is wired.
 *
 * Hint-usage rows are NO LONGER session-keyed (see Flyway V6) — they live
 * under `(puzzle_id, user_id)` and are cleared by the NATS `user.deleted`
 * consumer in identity-api's account-deletion flow, not by this endpoint.
 *
 * Matomo visit erasure is intentionally not invoked from here. The
 * daily-rotated salted hash (ADR-0025 §3) makes prior-day visits already
 * non-attributable to any individual.
 */
class DeleteSessionUseCase(
    private val cooldownRepository: ClueCooldownRepository? = null,
) {
    fun execute(sessionId: UUID): Int = cooldownRepository?.deleteBySession(sessionId) ?: 0
}
