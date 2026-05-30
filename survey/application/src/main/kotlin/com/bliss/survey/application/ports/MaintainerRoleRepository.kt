package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.UserId
import java.time.Instant

interface MaintainerRoleRepository {
    suspend fun find(userId: UserId): MaintainerRole?

    suspend fun upsert(role: MaintainerRole)

    suspend fun delete(userId: UserId)

    suspend fun listMaintainers(): List<UserId>
}

data class MaintainerRole(
    val userId: UserId,
    val role: String,
    val changedAt: Instant,
)
