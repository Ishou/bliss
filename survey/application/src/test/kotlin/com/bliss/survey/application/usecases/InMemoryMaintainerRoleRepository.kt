package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.domain.model.UserId

class InMemoryMaintainerRoleRepository : MaintainerRoleRepository {
    val rows: MutableMap<UserId, MaintainerRole> = linkedMapOf()

    override suspend fun find(userId: UserId): MaintainerRole? = rows[userId]

    override suspend fun upsert(role: MaintainerRole) {
        rows[role.userId] = role
    }

    override suspend fun delete(userId: UserId) {
        rows.remove(userId)
    }

    override suspend fun listMaintainers(): List<UserId> = rows.values.filter { it.role == "maintainer" }.map { it.userId }
}
