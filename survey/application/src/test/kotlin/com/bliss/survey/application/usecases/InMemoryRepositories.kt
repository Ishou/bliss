package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.PairRatingRepository
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.RatingAggregate
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgress
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
import com.bliss.survey.domain.model.PairRating
import com.bliss.survey.domain.model.Pos
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
    var ratedByUser: Map<UserId, Set<ItemId>> = emptyMap()

    // Items the caller rated qualite=5 (unflagged) in binary mode — eligible as anchors.
    var knownGoodByUser: Map<UserId, Set<ItemId>> = emptyMap()

    // Unordered {leftItemId, rightItemId} sets the caller already gave a pair verdict on.
    var pairRatedByUser: Map<UserId, Set<Set<ItemId>>> = emptyMap()

    override suspend fun findById(id: ItemId): SurveyItem? = items[id]

    override suspend fun insert(item: SurveyItem) {
        items[item.id] = item
    }

    override suspend fun insertIfAbsent(item: SurveyItem): SurveyItem {
        existingUnretired(item.mot, item.definition)?.let { return it }
        items[item.id] = item
        return item
    }

    private fun existingUnretired(
        mot: String,
        definition: String,
    ): SurveyItem? =
        items.values.firstOrNull {
            it.mot == mot && it.definition == definition && it.retiredAt == null && it.id !in retired
        }

    override suspend fun retire(
        id: ItemId,
        at: Instant,
    ) {
        retired += id
        items[id]?.let { items[id] = it.copy(retiredAt = at) }
    }

    override suspend fun updatePos(
        id: ItemId,
        pos: Pos,
    ) {
        items[id]?.let { items[id] = it.copy(pos = pos) }
    }

    override suspend fun pickUnratedForUser(
        userId: UserId?,
        tier: Tier,
        exclude: Set<ItemId>,
    ): SurveyItem? =
        items.values
            .filter { it.tier == tier && it.id !in exclude && it.id !in retired }
            .firstOrNull()

    override suspend fun pickPairForUser(
        userId: UserId?,
        exclude: Set<ItemId>,
    ): ItemPair? = anchorPairForUser(userId, exclude) ?: fallbackPairForUser(userId, exclude)

    private fun anchorPairForUser(
        userId: UserId?,
        exclude: Set<ItemId>,
    ): ItemPair? {
        if (userId == null) return null
        val rated = ratedByUser[userId].orEmpty()
        val knownGood = knownGoodByUser[userId].orEmpty()
        val pairRated = pairRatedByUser[userId].orEmpty()
        for (anchor in items.values) {
            if (anchor.id in retired || anchor.id !in knownGood || anchor.id in exclude) continue
            val sibling =
                items.values.firstOrNull {
                    it.mot == anchor.mot &&
                        it.id != anchor.id &&
                        it.id !in retired &&
                        it.id !in exclude &&
                        it.id !in rated &&
                        setOf(anchor.id, it.id) !in pairRated
                } ?: continue
            return ItemPair(mot = anchor.mot, left = anchor, right = sibling)
        }
        return null
    }

    private fun fallbackPairForUser(
        userId: UserId?,
        exclude: Set<ItemId>,
    ): ItemPair? {
        val rated = userId?.let { ratedByUser[it] }.orEmpty()
        val pairRated = userId?.let { pairRatedByUser[it] }.orEmpty()
        val eligible =
            items.values.filter {
                it.id !in retired && it.id !in exclude && it.id !in rated
            }
        val byMot = eligible.groupBy { it.mot }.filterValues { it.size >= 2 }
        for ((mot, candidates) in byMot) {
            val pair =
                candidates.indices
                    .asSequence()
                    .flatMap { i ->
                        ((i + 1) until candidates.size).asSequence().map { candidates[i] to candidates[it] }
                    }.firstOrNull { (l, r) -> setOf(l.id, r.id) !in pairRated }
            if (pair != null) return ItemPair(mot = mot, left = pair.first, right = pair.second)
        }
        return null
    }

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

    val trainingWeights: MutableMap<ItemId, Double> = linkedMapOf()

    override suspend fun updateTrainingWeight(
        id: ItemId,
        weight: Double,
    ) {
        trainingWeights[id] = weight
    }
}

class InMemoryPairRatingRepository : PairRatingRepository {
    val rows: MutableList<PairRating> = mutableListOf()

    override suspend fun insert(rating: PairRating): Boolean {
        if (rating.userId != null) {
            val unorderedKey =
                setOf(rating.leftItemId, rating.rightItemId)
            val duplicate =
                rows.any { existing ->
                    existing.userId == rating.userId &&
                        setOf(existing.leftItemId, existing.rightItemId) == unorderedKey
                }
            if (duplicate) return false
        }
        rows += rating
        return true
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
