package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.PairRatingRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.PairRating
import com.bliss.survey.domain.model.PairRatingId
import com.bliss.survey.domain.model.PairVerdict
import com.bliss.survey.domain.model.PreferenceVerdict
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.UserId

sealed interface SubmitPairRatingResult {
    data class Recorded(
        val campaignId: CampaignId,
    ) : SubmitPairRatingResult

    data object Skipped : SubmitPairRatingResult

    data object AlreadyExists : SubmitPairRatingResult

    data object ItemNotFound : SubmitPairRatingResult

    data object PairMotMismatch : SubmitPairRatingResult

    data object SameItem : SubmitPairRatingResult

    data object Locked : SubmitPairRatingResult
}

data class SubmitPairRatingCommand(
    val leftItemId: ItemId,
    val rightItemId: ItemId,
    val userId: UserId?,
    val verdict: PairVerdict,
    val difficulte: Int,
    val latencyMs: Int,
)

class SubmitPairRatingUseCase(
    private val items: SurveyItemRepository,
    private val ratings: RatingRepository,
    private val pairRatings: PairRatingRepository,
    private val progress: UserProgressRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val campaigns: CampaignRepository,
) {
    suspend fun execute(cmd: SubmitPairRatingCommand): SubmitPairRatingResult {
        // SKIP is never persisted — preserve the no-write contract regardless of lock state.
        if (cmd.verdict == PairVerdict.SKIP) return SubmitPairRatingResult.Skipped
        val openCampaign = campaigns.findOpen() ?: return SubmitPairRatingResult.Locked
        if (cmd.leftItemId == cmd.rightItemId) return SubmitPairRatingResult.SameItem
        val left = items.findById(cmd.leftItemId) ?: return SubmitPairRatingResult.ItemNotFound
        val right = items.findById(cmd.rightItemId) ?: return SubmitPairRatingResult.ItemNotFound
        if (left.mot != right.mot) return SubmitPairRatingResult.PairMotMismatch

        val now = clock.now()
        val submittedAs = if (cmd.userId != null) SubmittedAs.AUTH else SubmittedAs.ANON

        return when (cmd.verdict) {
            PairVerdict.LEFT_WINS, PairVerdict.RIGHT_WINS -> insertPreference(cmd, now, openCampaign)
            PairVerdict.BOTH_GOOD -> insertAbsolutePair(cmd, left, right, qualite = 5, submittedAs, now, openCampaign)
            PairVerdict.BOTH_BAD -> insertAbsolutePair(cmd, left, right, qualite = 1, submittedAs, now, openCampaign)
            PairVerdict.SKIP -> SubmitPairRatingResult.Skipped
        }
    }

    private suspend fun insertPreference(
        cmd: SubmitPairRatingCommand,
        now: java.time.Instant,
        openCampaign: Campaign,
    ): SubmitPairRatingResult {
        val preference =
            when (cmd.verdict) {
                PairVerdict.LEFT_WINS -> PreferenceVerdict.LEFT_WINS
                PairVerdict.RIGHT_WINS -> PreferenceVerdict.RIGHT_WINS
                else -> error("unreachable: insertPreference invoked for non-preference verdict ${cmd.verdict}")
            }
        val row =
            PairRating(
                id = PairRatingId(ids.next()),
                leftItemId = cmd.leftItemId,
                rightItemId = cmd.rightItemId,
                userId = cmd.userId,
                verdict = preference,
                difficulte = cmd.difficulte,
                latencyMs = cmd.latencyMs,
                createdAt = now,
                campaignId = openCampaign.id,
            )
        val inserted = pairRatings.insert(row)
        if (!inserted) return SubmitPairRatingResult.AlreadyExists
        if (cmd.userId != null) progress.incrementItemsRated(cmd.userId, now)
        return SubmitPairRatingResult.Recorded(openCampaign.id)
    }

    private suspend fun insertAbsolutePair(
        cmd: SubmitPairRatingCommand,
        left: SurveyItem,
        right: SurveyItem,
        qualite: Int,
        submittedAs: SubmittedAs,
        now: java.time.Instant,
        openCampaign: Campaign,
    ): SubmitPairRatingResult {
        // Skip the BOTH_* path if the auth caller already rated either side in binary mode.
        if (cmd.userId != null) {
            ratings.findAuthRating(left.id, cmd.userId)?.let { return SubmitPairRatingResult.AlreadyExists }
            ratings.findAuthRating(right.id, cmd.userId)?.let { return SubmitPairRatingResult.AlreadyExists }
        }
        val rows =
            listOf(left.id, right.id).map { itemId ->
                Rating(
                    id = RatingId(ids.next()),
                    itemId = itemId,
                    userId = cmd.userId,
                    submittedAs = submittedAs,
                    qualite = qualite,
                    difficulte = cmd.difficulte,
                    flag = null,
                    proposedItemId = null,
                    latencyMs = cmd.latencyMs,
                    createdAt = now,
                    campaignId = openCampaign.id,
                )
            }
        for (r in rows) ratings.insert(r)
        if (cmd.userId != null) {
            progress.incrementItemsRated(cmd.userId, now)
            progress.incrementItemsRated(cmd.userId, now)
        }
        return SubmitPairRatingResult.Recorded(openCampaign.id)
    }
}
