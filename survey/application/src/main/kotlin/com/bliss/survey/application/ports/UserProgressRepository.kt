package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.UserId
import java.time.Instant

interface UserProgressRepository {
    suspend fun incrementItemsRated(
        userId: UserId,
        at: Instant,
    )

    suspend fun decrementItemsRated(
        userId: UserId,
        by: Int,
        priorLastRatedAt: Instant?,
    )

    suspend fun updateCalibrationAgreement(
        userId: UserId,
        agreement: Double,
    )

    suspend fun get(userId: UserId): UserProgress?

    suspend fun deleteByUser(userId: UserId)
}

data class UserProgress(
    val userId: UserId,
    val itemsRated: Int,
    val calibrationAgreement: Double?,
    val lastRatedAt: Instant?,
)
