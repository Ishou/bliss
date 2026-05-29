package com.bliss.survey.application.usecases

import com.bliss.survey.application.filters.FilterInput
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.filters.FilterResult
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.FlagReason
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import java.time.Instant
import java.time.ZoneOffset

sealed interface SubmitRatingResult {
    data class Accepted(
        val rating: Rating,
    ) : SubmitRatingResult

    data class AlreadyExists(
        val existing: Rating,
    ) : SubmitRatingResult

    data class CorrectifRejected(
        val filterId: Int,
        val reason: String,
    ) : SubmitRatingResult

    data object AnonCorrectifForbidden : SubmitRatingResult

    data object ItemNotFound : SubmitRatingResult
}

data class CorrectifInput(
    val text: String,
    val style: Style,
    val pos: Pos?,
)

data class SubmitRatingCommand(
    val itemId: ItemId,
    val userId: UserId?,
    val qualite: Int,
    val difficulte: Int,
    val flag: FlagReason?,
    val correctif: CorrectifInput?,
    val latencyMs: Int,
)

class SubmitRatingUseCase(
    private val items: SurveyItemRepository,
    private val ratings: RatingRepository,
    private val proposedBy: ProposedByRepository,
    private val progress: UserProgressRepository,
    private val filters: FilterPipeline,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun execute(cmd: SubmitRatingCommand): SubmitRatingResult {
        val now = clock.now()
        val parent = items.findById(cmd.itemId) ?: return SubmitRatingResult.ItemNotFound

        if (cmd.userId == null && cmd.correctif != null) return SubmitRatingResult.AnonCorrectifForbidden

        if (cmd.userId != null) {
            ratings.findAuthRating(cmd.itemId, cmd.userId)?.let {
                return SubmitRatingResult.AlreadyExists(it)
            }
        }

        var proposedItemId: ItemId? = null
        if (cmd.correctif != null) {
            val nonNullUserId =
                requireNotNull(cmd.userId) {
                    "userId must be non-null when correctif is provided"
                }
            val (text, claimed, requestedPos) = cmd.correctif
            val textChanged = text.trim() != parent.definition.trim()
            if (!textChanged) {
                // POS-only fix: patch the original item in place, no new proposed item.
                if (requestedPos != null && requestedPos != parent.pos) items.updatePos(parent.id, requestedPos)
            } else {
                val effectivePos = requestedPos ?: parent.pos
                val proposedInput =
                    FilterInput(
                        mot = parent.mot,
                        definition = text,
                        pos = effectivePos,
                        style = claimed,
                    )
                val r = filters.run(proposedInput)
                if (r is FilterResult.Reject) {
                    return SubmitRatingResult.CorrectifRejected(r.filterId, r.reason)
                }
                val newItem =
                    SurveyItem(
                        id = ItemId(ids.next()),
                        mot = parent.mot,
                        definition = text,
                        pos = effectivePos,
                        categorie = parent.categorie,
                        style = claimed,
                        forceClaimed = 3,
                        longueur = parent.mot.length,
                        source = Source.RATER_PROPOSED,
                        sourceBatch = "rater_${monthKey(now)}",
                        tier = Tier.MID,
                        isCalibration = false,
                        expected = null,
                        retiredAt = null,
                        createdAt = now,
                    )
                // On (mot, definition) conflict, reuse the existing row's id so the auto-GOOD rating never dangles.
                val stored = items.insertIfAbsent(newItem)
                proposedItemId = stored.id
                proposedBy.insert(stored.id, nonNullUserId, optedOut = false)
            }
        }

        val rating =
            Rating(
                id = RatingId(ids.next()),
                itemId = cmd.itemId,
                userId = cmd.userId,
                submittedAs = if (cmd.userId != null) SubmittedAs.AUTH else SubmittedAs.ANON,
                qualite = cmd.qualite,
                difficulte = cmd.difficulte,
                flag = cmd.flag,
                proposedItemId = proposedItemId,
                latencyMs = cmd.latencyMs,
                createdAt = now,
            )
        ratings.insert(rating)
        if (proposedItemId != null) {
            // submitting a correctif vouches for the fix — enters winners directly without a re-rating round
            val autoGood =
                Rating(
                    id = RatingId(ids.next()),
                    itemId = proposedItemId,
                    userId = cmd.userId,
                    submittedAs = SubmittedAs.AUTH,
                    qualite = 5,
                    difficulte = cmd.difficulte,
                    flag = null,
                    proposedItemId = null,
                    latencyMs = 0,
                    createdAt = now,
                )
            ratings.insert(autoGood)
        }
        if (cmd.userId != null) progress.incrementItemsRated(cmd.userId, now)
        return SubmitRatingResult.Accepted(rating)
    }

    private fun monthKey(t: Instant): String {
        val zdt = t.atZone(ZoneOffset.UTC)
        return "%04d-%02d".format(zdt.year, zdt.monthValue)
    }
}
