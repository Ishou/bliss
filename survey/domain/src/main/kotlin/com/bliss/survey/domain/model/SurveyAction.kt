package com.bliss.survey.domain.model

import java.time.Instant
import java.util.UUID

@JvmInline
value class ActionId(
    val value: UUID,
)

enum class ActionKind { BINARY, PAIR, CORRECTIF }

// undoTokenHash = sha256(capability token); identity is on `id` (ByteArray defeats data-class equality).
data class SurveyAction(
    val id: ActionId,
    val undoTokenHash: ByteArray,
    val userId: UserId?,
    val kind: ActionKind,
    val campaignId: CampaignId,
    val createdAt: Instant,
    val undoneAt: Instant?,
    val createdRatingIds: List<RatingId>,
    val createdPairId: PairRatingId?,
    val createdItemId: ItemId?,
    val proposedItemId: ItemId?,
    val patchedItemId: ItemId?,
    val priorPos: Pos?,
    val priorLastRatedAt: Instant?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SurveyAction) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
