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
    val targetCategories: List<Categorie> = emptyList(),
    val targetSense: String? = null,
    val isMultisense: Boolean = false,
    val subTags: List<String> = emptyList(),
) {
    init {
        require(qualite in 1..5) { "qualite must be in 1..5 (was $qualite)" }
        require(difficulte in 1..5) { "difficulte must be in 1..5 (was $difficulte)" }
        if (submittedAs == SubmittedAs.ANON) {
            require(userId == null) { "anon rating must have null user_id" }
            require(proposedItemId == null) {
                "anon rating must have null proposed_item_id (contributions require auth)"
            }
            require(targetCategories.isEmpty() && targetSense == null && !isMultisense && subTags.isEmpty()) {
                "anon rating must carry no meta (annotation requires sign-in)"
            }
        }
        require(targetCategories.size <= MAX_TARGET_CATEGORIES) {
            "targetCategories bounded to $MAX_TARGET_CATEGORIES"
        }
        targetSense?.let {
            require(it.isNotBlank()) { "targetSense must not be blank when present" }
            require(it.length <= MAX_SENSE_LENGTH) { "targetSense bounded to $MAX_SENSE_LENGTH chars" }
        }
        require(subTags.size <= MAX_SUB_TAGS) { "subTags bounded to $MAX_SUB_TAGS" }
        require(subTags.all { it.isNotBlank() }) { "subTags must not contain blank entries" }
        require(subTags.all { it.length <= MAX_SUB_TAG_LENGTH }) {
            "subTags entries bounded to $MAX_SUB_TAG_LENGTH chars"
        }
    }

    companion object {
        const val MAX_TARGET_CATEGORIES = 6
        const val MAX_SUB_TAGS = 12
        const val MAX_SUB_TAG_LENGTH = 40
        const val MAX_SENSE_LENGTH = 80
    }
}
