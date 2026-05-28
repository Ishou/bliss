package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
import java.time.Instant

interface SurveyItemRepository {
    suspend fun findById(id: ItemId): SurveyItem?

    suspend fun insert(item: SurveyItem)

    suspend fun retire(
        id: ItemId,
        at: Instant,
    )

    suspend fun pickUnratedForUser(
        userId: UserId?,
        tier: Tier,
        exclude: Set<ItemId>,
    ): SurveyItem?

    /**
     * Pick two distinct items for the same `mot`, both unrated by the caller in either binary or pair mode.
     * Returns null when no mot has at least two such candidates available.
     */
    suspend fun pickPairForUser(
        userId: UserId?,
        exclude: Set<ItemId>,
    ): ItemPair?

    suspend fun countUnretiredByTier(): Map<Tier, Int>

    suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId>

    suspend fun listProposedByUser(userId: UserId): List<ProposedContribution>

    suspend fun deleteByIds(ids: Collection<ItemId>)
}

data class ProposedContribution(
    val item: SurveyItem,
    val optedOut: Boolean,
    val kCoverage: Int,
)
