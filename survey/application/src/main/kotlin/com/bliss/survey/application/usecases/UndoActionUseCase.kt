package com.bliss.survey.application.usecases

import com.bliss.survey.application.CLOSE_GRACE
import com.bliss.survey.application.ports.ActionLogRepository
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.PairRatingRepository
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.TransactionManager
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.application.sha256
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.UserId

sealed interface UndoActionResult {
    data object Undone : UndoActionResult

    data object NotFound : UndoActionResult

    data object Expired : UndoActionResult
}

/** Reverses one logged action within the campaign-close grace window (ADR-0059). */
class UndoActionUseCase(
    private val actions: ActionLogRepository,
    private val ratings: RatingRepository,
    private val pairRatings: PairRatingRepository,
    private val items: SurveyItemRepository,
    private val proposedBy: ProposedByRepository,
    private val progress: UserProgressRepository,
    private val campaigns: CampaignRepository,
    private val tx: TransactionManager,
    private val clock: Clock,
) {
    suspend fun execute(
        token: String,
        sessionUserId: UserId?,
    ): UndoActionResult {
        val action = actions.findByTokenHash(sha256(token)) ?: return UndoActionResult.NotFound
        // Fast-path only; the authoritative single-redemption gate is the conditional markUndone inside the tx.
        if (action.undoneAt != null) return UndoActionResult.NotFound
        // Capability check: an authed action is only undoable by its owner; anon by anyone holding the token.
        if (action.userId != null && action.userId != sessionUserId) return UndoActionResult.NotFound
        val campaign = campaigns.findById(action.campaignId) ?: return UndoActionResult.NotFound
        val now = clock.now()
        val closedAt = campaign.closedAt
        if (closedAt != null && now.isAfter(closedAt.plus(CLOSE_GRACE))) return UndoActionResult.Expired
        return tx.inTransaction {
            val claimed = actions.markUndone(action.id, now)
            if (!claimed) {
                UndoActionResult.NotFound
            } else {
                reverse(action)
                UndoActionResult.Undone
            }
        }
    }

    private suspend fun reverse(action: SurveyAction) {
        if (action.createdRatingIds.isNotEmpty()) ratings.deleteByIds(action.createdRatingIds)
        action.createdPairId?.let { pairRatings.deleteById(it) }
        val patchedItemId = action.patchedItemId
        val priorPos = action.priorPos
        if (patchedItemId != null && priorPos != null) items.updatePos(patchedItemId, priorPos)
        val proposedItemId = action.proposedItemId
        val owner = action.userId
        if (proposedItemId != null && owner != null) proposedBy.delete(proposedItemId, owner)
        val createdItemId = action.createdItemId
        if (createdItemId != null && ratings.countByItem(createdItemId) == 0) {
            items.deleteByIds(listOf(createdItemId))
        }
        val actor = action.userId ?: return
        val decrement = if (action.kind == ActionKind.PAIR && action.createdRatingIds.size == 2) 2 else 1
        progress.decrementItemsRated(actor, decrement, action.priorLastRatedAt)
    }
}
