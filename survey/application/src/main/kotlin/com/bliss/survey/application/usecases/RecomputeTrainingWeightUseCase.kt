package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.weight.GoldWindowPolicy
import java.time.Instant

class RecomputeTrainingWeightUseCase(
    private val maintainerRoles: MaintainerRoleRepository,
    private val items: SurveyItemRepository,
    private val policy: GoldWindowPolicy,
) {
    suspend fun onRoleChanged(
        userId: UserId,
        role: String,
        changedAt: Instant,
    ) {
        val cached = maintainerRoles.find(userId)
        if (cached != null && changedAt.isBefore(cached.changedAt)) return
        maintainerRoles.upsert(MaintainerRole(userId, role, changedAt))
        forUser(userId, isMaintainer = role == MAINTAINER)
    }

    suspend fun forItem(
        itemId: ItemId,
        authorUserId: UserId,
    ) {
        val item = items.findById(itemId) ?: return
        val isMaintainer = maintainerRoles.find(authorUserId)?.role == MAINTAINER
        items.updateTrainingWeight(itemId, policy.weightFor(item.createdAt, isMaintainer))
    }

    suspend fun recomputeAll() {
        for (userId in maintainerRoles.listMaintainers()) forUser(userId, isMaintainer = true)
    }

    private suspend fun forUser(
        userId: UserId,
        isMaintainer: Boolean,
    ) {
        for (c in items.listProposedByUser(userId)) {
            items.updateTrainingWeight(c.item.id, policy.weightFor(c.item.createdAt, isMaintainer))
        }
    }

    private companion object {
        const val MAINTAINER = "maintainer"
    }
}
