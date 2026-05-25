package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.RatingAggregate
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgress
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
import java.time.Instant

class InMemorySurveyItemRepository : SurveyItemRepository {
    val items: MutableMap<ItemId, SurveyItem> = linkedMapOf()
    val retired: MutableSet<ItemId> = mutableSetOf()

    override suspend fun findById(id: ItemId): SurveyItem? = items[id]

    override suspend fun insert(item: SurveyItem) {
        items[item.id] = item
    }

    override suspend fun retire(
        id: ItemId,
        at: Instant,
    ) {
        retired += id
        items[id]?.let { items[id] = it.copy(retiredAt = at) }
    }

    override suspend fun pickUnratedForUser(
        userId: UserId?,
        tier: Tier,
        exclude: Set<ItemId>,
    ): SurveyItem? =
        items.values
            .filter { it.tier == tier && it.id !in exclude && it.id !in retired }
            .firstOrNull()

    override suspend fun countUnretiredByTier(): Map<Tier, Int> =
        items.values
            .filter { it.id !in retired }
            .groupingBy { it.tier }
            .eachCount()

    var saturated: List<ItemId> = emptyList()

    override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = saturated

    var proposedByUser: Map<UserId, List<ProposedContribution>> = emptyMap()

    override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> = proposedByUser[userId] ?: emptyList()

    override suspend fun deleteByIds(ids: Collection<ItemId>) {
        for (id in ids) items.remove(id)
    }
}

class InMemoryRatingRepository : RatingRepository {
    val ratings: MutableList<Rating> = mutableListOf()
    val anonymisedUsers: MutableSet<UserId> = mutableSetOf()

    override suspend fun findAuthRating(
        itemId: ItemId,
        userId: UserId,
    ): Rating? =
        ratings.firstOrNull {
            it.itemId == itemId && it.userId == userId && it.submittedAs == SubmittedAs.AUTH
        }

    override suspend fun insert(rating: Rating) {
        ratings += rating
    }

    override suspend fun countByItem(itemId: ItemId): Int = ratings.count { it.itemId == itemId }

    override suspend fun anonymiseForUser(userId: UserId) {
        anonymisedUsers += userId
        val targets = ratings.withIndex().filter { it.value.userId == userId }
        for ((idx, r) in targets) {
            ratings[idx] =
                r.copy(
                    userId = null,
                    submittedAs = SubmittedAs.ANON,
                    proposedItemId = null,
                )
        }
    }

    var aggregateOverride: List<RatingAggregate>? = null

    override suspend fun aggregateForExport(since: Instant?): List<RatingAggregate> = aggregateOverride ?: emptyList()
}

class InMemoryProposedByRepository : ProposedByRepository {
    data class Link(
        val itemId: ItemId,
        val userId: UserId,
        var optedOut: Boolean,
    )

    val links: MutableList<Link> = mutableListOf()

    override suspend fun insert(
        itemId: ItemId,
        userId: UserId,
        optedOut: Boolean,
    ) {
        links += Link(itemId, userId, optedOut)
    }

    override suspend fun setOptOut(
        userId: UserId,
        optedOut: Boolean,
    ) {
        links.filter { it.userId == userId }.forEach { it.optedOut = optedOut }
    }

    override suspend fun listOptedOutByUser(userId: UserId): List<ItemId> =
        links
            .filter { it.userId == userId && it.optedOut }
            .map { it.itemId }

    override suspend fun deleteByUser(userId: UserId) {
        links.removeAll { it.userId == userId }
    }
}

class InMemoryUserProgressRepository : UserProgressRepository {
    val progress: MutableMap<UserId, UserProgress> = linkedMapOf()
    val deleted: MutableSet<UserId> = mutableSetOf()

    override suspend fun incrementItemsRated(
        userId: UserId,
        at: Instant,
    ) {
        val existing = progress[userId]
        progress[userId] =
            existing?.copy(itemsRated = existing.itemsRated + 1, lastRatedAt = at)
                ?: UserProgress(userId, 1, null, at)
    }

    override suspend fun updateCalibrationAgreement(
        userId: UserId,
        agreement: Double,
    ) {
        val existing = progress[userId]
        progress[userId] =
            existing?.copy(calibrationAgreement = agreement)
                ?: UserProgress(userId, 0, agreement, null)
    }

    override suspend fun get(userId: UserId): UserProgress? = progress[userId]

    override suspend fun deleteByUser(userId: UserId) {
        deleted += userId
        progress.remove(userId)
    }
}
