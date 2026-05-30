package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.UserId
import java.time.Instant

interface ActionLogRepository {
    suspend fun insert(action: SurveyAction)

    suspend fun findByTokenHash(tokenHash: ByteArray): SurveyAction?

    // Conditional single-redemption: flips undone_at only if still null; returns true iff a row changed.
    suspend fun markUndone(
        id: ActionId,
        at: Instant,
    ): Boolean

    // RGPD: null out user_id for the erased user's actions.
    suspend fun scrubUser(userId: UserId)
}
