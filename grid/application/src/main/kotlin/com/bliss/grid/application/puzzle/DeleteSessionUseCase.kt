package com.bliss.grid.application.puzzle

import com.bliss.grid.domain.generation.ClueCooldownRepository
import java.util.UUID

/** GDPR Article 17 erasure (ADR-0025 §5) for anonymous session data; removes clue-cooldown rows tied to [sessionId]. */
class DeleteSessionUseCase(
    private val cooldownRepository: ClueCooldownRepository? = null,
) {
    fun execute(sessionId: UUID): Int = cooldownRepository?.deleteBySession(sessionId) ?: 0
}
