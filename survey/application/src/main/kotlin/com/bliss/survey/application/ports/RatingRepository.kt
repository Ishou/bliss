package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.UserId
import java.time.Instant

interface RatingRepository {
    suspend fun findAuthRating(
        itemId: ItemId,
        userId: UserId,
    ): Rating?

    suspend fun insert(rating: Rating)

    suspend fun deleteByIds(ids: List<RatingId>)

    suspend fun countByItem(itemId: ItemId): Int

    suspend fun anonymiseForUser(userId: UserId)

    // settledBefore excludes ratings whose campaign is still open or within the close grace (ADR-0059).
    suspend fun aggregateForExport(
        since: Instant?,
        settledBefore: Instant,
    ): List<RatingAggregate>

    // Raw senses + sub-tags across prior ratings of items sharing this lemma, most-recent first.
    // Dedup/normalization is the caller's concern (GetLemmaMetaUseCase).
    suspend fun priorMetaForMot(mot: String): PriorLemmaMeta
}

data class PriorLemmaMeta(
    val senses: List<String>,
    val subTags: List<String>,
)

data class RatingAggregate(
    val itemId: ItemId,
    val qualiteAuthSum: Int,
    val qualiteAuthN: Int,
    val qualiteAnonSum: Int,
    val qualiteAnonN: Int,
    val difficulteAuthSum: Int,
    val difficulteAuthN: Int,
    val difficulteAnonSum: Int,
    val difficulteAnonN: Int,
    val flagCount: Int,
    val qualiteSquaredAuthSum: Int,
    val qualiteSquaredAnonSum: Int,
)
