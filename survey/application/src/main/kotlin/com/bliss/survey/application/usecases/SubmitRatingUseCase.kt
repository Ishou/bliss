package com.bliss.survey.application.usecases

import com.bliss.survey.application.filters.FilterInput
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.filters.FilterResult
import com.bliss.survey.application.ports.ActionLogRepository
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.TokenGenerator
import com.bliss.survey.application.ports.TransactionManager
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.application.ports.WordMetaRepository
import com.bliss.survey.application.sha256
import com.bliss.survey.application.text.GlossNormalizer
import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.FlagReason
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.model.WordMeta
import java.time.Instant
import java.time.ZoneOffset

sealed interface SubmitRatingResult {
    data class Accepted(
        val rating: Rating,
        val undoToken: String,
    ) : SubmitRatingResult

    data class AlreadyExists(
        val existing: Rating,
    ) : SubmitRatingResult

    data class CorrectifRejected(
        val filterId: Int,
        val reason: String,
    ) : SubmitRatingResult

    data object AnonCorrectifForbidden : SubmitRatingResult

    data object AnonTargetSensesForbidden : SubmitRatingResult

    data object ItemNotFound : SubmitRatingResult

    data object Locked : SubmitRatingResult
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
    val targetSenses: List<String> = emptyList(),
)

class SubmitRatingUseCase(
    private val items: SurveyItemRepository,
    private val ratings: RatingRepository,
    private val proposedBy: ProposedByRepository,
    private val progress: UserProgressRepository,
    private val filters: FilterPipeline,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val campaigns: CampaignRepository,
    private val recompute: RecomputeTrainingWeightUseCase,
    private val actions: ActionLogRepository,
    private val tokens: TokenGenerator,
    private val tx: TransactionManager,
    private val wordMeta: WordMetaRepository,
) {
    suspend fun execute(cmd: SubmitRatingCommand): SubmitRatingResult {
        val openCampaign = campaigns.findOpen() ?: return SubmitRatingResult.Locked
        val now = clock.now()
        val parent = items.findById(cmd.itemId) ?: return SubmitRatingResult.ItemNotFound

        if (cmd.userId == null && cmd.correctif != null) return SubmitRatingResult.AnonCorrectifForbidden
        if (cmd.userId == null && cmd.targetSenses.isNotEmpty()) {
            return SubmitRatingResult.AnonTargetSensesForbidden
        }

        if (cmd.userId != null) {
            ratings.findAuthRating(cmd.itemId, cmd.userId)?.let {
                return SubmitRatingResult.AlreadyExists(it)
            }
        }

        // Build the (possibly null) text-correctif item OUTSIDE the transaction so a filter Reject never opens one.
        var newItem: SurveyItem? = null
        var patchedPos: Pos? = null
        if (cmd.correctif != null) {
            requireNotNull(cmd.userId) { "userId must be non-null when correctif is provided" }
            val (text, claimed, requestedPos) = cmd.correctif
            val textChanged = text.trim() != parent.definition.trim()
            if (!textChanged) {
                if (requestedPos != null && requestedPos != parent.pos) patchedPos = requestedPos
            } else {
                val effectivePos = requestedPos ?: parent.pos
                val r = filters.run(FilterInput(mot = parent.mot, definition = text, pos = effectivePos, style = claimed))
                if (r is FilterResult.Reject) return SubmitRatingResult.CorrectifRejected(r.filterId, r.reason)
                newItem =
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
            }
        }

        val token = tokens.newToken()
        val priorLastRatedAt = if (cmd.userId != null) progress.get(cmd.userId)?.lastRatedAt else null

        val rating =
            tx.inTransaction {
                val createdRatingIds = mutableListOf<RatingId>()
                var createdItemId: ItemId? = null
                var proposedItemId: ItemId? = null
                var patchedItemId: ItemId? = null
                var priorPos: Pos? = null

                if (patchedPos != null) {
                    patchedItemId = parent.id
                    priorPos = parent.pos
                    items.updatePos(parent.id, patchedPos)
                }
                if (newItem != null) {
                    // On (mot, definition) conflict, reuse the existing row's id so the auto-GOOD rating never dangles.
                    val stored = items.insertIfAbsent(newItem)
                    proposedItemId = stored.id
                    if (stored.id == newItem.id) createdItemId = stored.id
                    proposedBy.insert(stored.id, cmd.userId!!, optedOut = false)
                    recompute.forItem(stored.id, cmd.userId)
                }

                val r =
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
                        campaignId = openCampaign.id,
                        targetSenses = cmd.targetSenses,
                    )
                ratings.insert(r)
                createdRatingIds += r.id

                if (cmd.targetSenses.isNotEmpty()) mergeIntoSenseInventory(parent.mot, cmd.targetSenses, now)

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
                            campaignId = openCampaign.id,
                        )
                    ratings.insert(autoGood)
                    createdRatingIds += autoGood.id
                }
                if (cmd.userId != null) progress.incrementItemsRated(cmd.userId, now)

                actions.insert(
                    SurveyAction(
                        id = ActionId(ids.next()),
                        undoTokenHash = sha256(token),
                        userId = cmd.userId,
                        kind = if (cmd.correctif != null) ActionKind.CORRECTIF else ActionKind.BINARY,
                        campaignId = openCampaign.id,
                        createdAt = now,
                        undoneAt = null,
                        createdRatingIds = createdRatingIds.toList(),
                        createdPairId = null,
                        createdItemId = createdItemId,
                        proposedItemId = proposedItemId,
                        patchedItemId = patchedItemId,
                        priorPos = priorPos,
                        priorLastRatedAt = priorLastRatedAt,
                    ),
                )
                r
            }
        return SubmitRatingResult.Accepted(rating, token)
    }

    private fun monthKey(t: Instant): String {
        val zdt = t.atZone(ZoneOffset.UTC)
        return "%04d-%02d".format(zdt.year, zdt.monthValue)
    }

    // Merge new glosses into the lemma's sense inventory, dedup by normalized form; preserve first-seen spelling.
    // findForUpdate locks the row inside the surrounding transaction so concurrent merges serialize (avoids lost-update).
    private suspend fun mergeIntoSenseInventory(
        mot: String,
        incoming: List<String>,
        now: Instant,
    ) {
        val existing = wordMeta.findForUpdate(mot)
        val seen = HashSet<String>()
        val merged = ArrayList<String>()
        for (gloss in incoming + (existing?.senseInventory ?: emptyList())) {
            val key = GlossNormalizer.normalize(gloss)
            if (key.isNotBlank() && seen.add(key)) merged += gloss
        }
        wordMeta.save(
            WordMeta(
                mot = mot,
                subTags = existing?.subTags ?: emptyList(),
                senseInventory = merged,
                updatedAt = now,
            ),
        )
    }
}
