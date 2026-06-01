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
    val targetSenses: List<String> = emptyList(),
) {
    init {
        require(qualite in 1..5) { "qualite must be in 1..5 (was $qualite)" }
        require(difficulte in 1..5) { "difficulte must be in 1..5 (was $difficulte)" }
        if (submittedAs == SubmittedAs.ANON) {
            require(userId == null) { "anon rating must have null user_id" }
            require(proposedItemId == null) {
                "anon rating must have null proposed_item_id (contributions require auth)"
            }
            require(targetSenses.isEmpty()) {
                "anon rating must have empty target_senses (sense annotation requires auth, ADR-0061)"
            }
        }
        require(targetSenses.size <= MAX_TARGET_SENSES) {
            "targetSenses bounded to $MAX_TARGET_SENSES (ADR-0061)"
        }
        require(targetSenses.all { it.isNotBlank() }) {
            "targetSenses must not contain blank entries"
        }
        require(targetSenses.all { it.length <= MAX_TARGET_SENSE_LENGTH }) {
            "targetSenses entries bounded to $MAX_TARGET_SENSE_LENGTH chars (ADR-0061)"
        }
    }

    companion object {
        const val MAX_TARGET_SENSES = 8
        const val MAX_TARGET_SENSE_LENGTH = 80
    }
}
