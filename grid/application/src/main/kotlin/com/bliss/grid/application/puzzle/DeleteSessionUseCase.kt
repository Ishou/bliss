package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.ClueCooldownRepository
import java.util.UUID

/**
 * Right-to-erasure (RGPD Article 17, ADR-0025 §5). Removes every server-side
 * row attributable to [sessionId] and returns the total deleted across all
 * extended tables. Today that covers `puzzle_hint_usage` always, and
 * `clue_cooldown` whenever a non-null [cooldownRepository] is wired.
 *
 * Matomo visit erasure is intentionally not invoked from here. The
 * daily-rotated salted hash (ADR-0025 §3) makes prior-day visits already
 * non-attributable to any individual; current-day visits become non-linkable
 * the moment the user clears their browser `localStorage` and gets a fresh
 * `sessionId` (a different daily hash). The privacy notice discloses this.
 */
class DeleteSessionUseCase(
    private val hintUsageRepository: HintUsageRepository,
    private val cooldownRepository: ClueCooldownRepository? = null,
) {
    fun execute(sessionId: UUID): Int {
        val hintRows = hintUsageRepository.deleteBySession(sessionId)
        val cooldownRows = cooldownRepository?.deleteBySession(sessionId) ?: 0
        return hintRows + cooldownRows
    }
}
