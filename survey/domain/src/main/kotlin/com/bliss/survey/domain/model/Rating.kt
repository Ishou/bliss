package com.bliss.survey.domain.model

import java.time.Instant

data class Rating(
    val id: RatingId,
    val itemId: ItemId,
    val userId: UserId?,
    val submittedAs: SubmittedAs,
    val qualite: Int,
    val difficulte: Int,
    val flag: FlagReason?,
    val proposedItemId: ItemId?,
    val latencyMs: Int?,
    val createdAt: Instant,
    val campaignId: CampaignId? = null,
) {
    init {
        require(qualite in 1..5) { "qualite must be in 1..5 (was $qualite)" }
        require(difficulte in 1..5) { "difficulte must be in 1..5 (was $difficulte)" }
        if (submittedAs == SubmittedAs.ANON) {
            require(userId == null) { "anon rating must have null user_id" }
            require(proposedItemId == null) {
                "anon rating must have null proposed_item_id (contributions require auth)"
            }
        }
    }
}
