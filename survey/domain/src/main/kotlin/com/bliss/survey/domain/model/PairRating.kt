package com.bliss.survey.domain.model

import java.util.UUID

@JvmInline
value class PairRatingId(
    val value: UUID,
)

// Domain row for the pair_ratings table: strict preference only.
// BOTH_GOOD/BOTH_BAD become absolute Rating rows; SKIP is not persisted.
enum class PreferenceVerdict {
    LEFT_WINS,
    RIGHT_WINS,
}

data class PairRating(
    val id: PairRatingId,
    val leftItemId: ItemId,
    val rightItemId: ItemId,
    val userId: UserId?,
    val verdict: PreferenceVerdict,
    val difficulte: Int,
    val latencyMs: Int?,
    val createdAt: java.time.Instant,
) {
    init {
        require(leftItemId != rightItemId) { "leftItemId and rightItemId must differ" }
        require(difficulte in 1..5) { "difficulte must be in 1..5 (was $difficulte)" }
    }
}

// Two items for the same mot, both unrated by the caller (GET /v1/items/pairs/next).
data class ItemPair(
    val mot: String,
    val left: SurveyItem,
    val right: SurveyItem,
) {
    init {
        require(left.mot == right.mot) { "left.mot and right.mot must match" }
        require(left.mot == mot) { "pair mot must match item mots" }
        require(left.id != right.id) { "left and right must be distinct items" }
    }
}
