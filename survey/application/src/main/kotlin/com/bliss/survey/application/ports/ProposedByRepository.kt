package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.UserId

interface ProposedByRepository {
    suspend fun insert(
        itemId: ItemId,
        userId: UserId,
        optedOut: Boolean,
    )

    suspend fun setOptOut(
        userId: UserId,
        optedOut: Boolean,
    )

    suspend fun listOptedOutByUser(userId: UserId): List<ItemId>

    suspend fun delete(
        itemId: ItemId,
        userId: UserId,
    )

    suspend fun deleteByUser(userId: UserId)
}
