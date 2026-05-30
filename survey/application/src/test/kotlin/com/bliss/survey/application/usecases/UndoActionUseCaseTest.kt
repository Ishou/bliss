package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.sha256
import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.PairRating
import com.bliss.survey.domain.model.PairRatingId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.PreferenceVerdict
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UndoActionUseCaseTest {
    private val now: Instant = Instant.parse("2026-05-30T12:00:00Z")
    private val clock = Clock { now }
    private val userId = UserId(UUID.randomUUID())
    private val campaignId = CampaignId(UUID.randomUUID())

    private class Fixture(
        val useCase: UndoActionUseCase,
        val actions: InMemoryActionLogRepository,
        val ratings: InMemoryRatingRepository,
        val pairRatings: InMemoryPairRatingRepository,
        val items: InMemorySurveyItemRepository,
        val proposedBy: InMemoryProposedByRepository,
        val progress: InMemoryUserProgressRepository,
        val userId: UserId,
        val createdItemId: ItemId?,
        val patchedItemId: ItemId?,
    )

    private fun sampleItem(): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = "POMME",
            definition = "Fruit du pommier",
            pos = Pos.POLYVALENT,
            categorie = Categorie.ALIMENTS,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = 5,
            source = Source.RATER_PROPOSED,
            sourceBatch = "rater_2026-05",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = now,
        )

    private fun newFixture(
        campaignOpen: Boolean,
        closedAt: Instant? = null,
        anon: Boolean = false,
        kind: ActionKind = ActionKind.BINARY,
        freshItem: Boolean = false,
        otherRefExists: Boolean = false,
        patched: Boolean = false,
        priorPos: Pos? = null,
        twoRatings: Boolean = false,
        progressStart: Int = 1,
    ): Fixture =
        runBlockingFixture {
            val actions = InMemoryActionLogRepository()
            val ratings = InMemoryRatingRepository()
            val pairRatings = InMemoryPairRatingRepository()
            val items = InMemorySurveyItemRepository()
            val proposedBy = InMemoryProposedByRepository()
            val progress = InMemoryUserProgressRepository()
            val actorId = if (anon) null else userId

            val campaign =
                Campaign(
                    id = campaignId,
                    batchLabel = "round-7",
                    openedAt = now.minusSeconds(3600),
                    closedAt = if (campaignOpen) null else (closedAt ?: now.minusSeconds(20)),
                )

            val ratingIds = mutableListOf<RatingId>()
            val ratingCount = if (twoRatings) 2 else 1
            var createdItemId: ItemId? = null
            var patchedItemId: ItemId? = null

            if (kind == ActionKind.CORRECTIF && freshItem) {
                val created = sampleItem()
                items.insert(created)
                createdItemId = created.id
                if (actorId != null) proposedBy.insert(created.id, actorId, optedOut = false)
                val rid = RatingId(UUID.randomUUID())
                ratings.insert(rating(rid, created.id, actorId))
                ratingIds += rid
                if (otherRefExists) ratings.insert(rating(RatingId(UUID.randomUUID()), created.id, null))
            } else if (kind == ActionKind.CORRECTIF && patched) {
                val target = sampleItem().copy(pos = Pos.VERBE_INFINITIF)
                items.insert(target)
                patchedItemId = target.id
                val rid = RatingId(UUID.randomUUID())
                ratings.insert(rating(rid, target.id, actorId))
                ratingIds += rid
            } else {
                repeat(ratingCount) {
                    val rid = RatingId(UUID.randomUUID())
                    ratings.insert(rating(rid, ItemId(UUID.randomUUID()), actorId))
                    ratingIds += rid
                }
            }

            var createdPairId: PairRatingId? = null
            if (kind == ActionKind.PAIR && !twoRatings) {
                createdPairId = PairRatingId(UUID.randomUUID())
                pairRatings.insert(pairRow(createdPairId, actorId))
                ratingIds.clear()
            }

            if (actorId != null) {
                repeat(progressStart) { progress.incrementItemsRated(actorId, now) }
            }

            actions.insert(
                SurveyAction(
                    id = ActionId(UUID.randomUUID()),
                    undoTokenHash = sha256("tok"),
                    userId = actorId,
                    kind = kind,
                    campaignId = campaign.id,
                    createdAt = now.minusSeconds(10),
                    undoneAt = null,
                    createdRatingIds = ratingIds.toList(),
                    createdPairId = createdPairId,
                    createdItemId = createdItemId,
                    proposedItemId = if (kind == ActionKind.CORRECTIF && freshItem) createdItemId else null,
                    patchedItemId = patchedItemId,
                    priorPos = if (patched) priorPos else null,
                    priorLastRatedAt = null,
                ),
            )

            val useCase =
                UndoActionUseCase(
                    actions = actions,
                    ratings = ratings,
                    pairRatings = pairRatings,
                    items = items,
                    proposedBy = proposedBy,
                    progress = progress,
                    campaigns = InMemoryCampaignRepository(campaign),
                    tx = passThroughTransactionManager,
                    clock = clock,
                )
            Fixture(useCase, actions, ratings, pairRatings, items, proposedBy, progress, actorId ?: userId, createdItemId, patchedItemId)
        }

    private fun rating(
        id: RatingId,
        itemId: ItemId,
        owner: UserId?,
    ): Rating =
        Rating(
            id = id,
            itemId = itemId,
            userId = owner,
            submittedAs = if (owner != null) SubmittedAs.AUTH else SubmittedAs.ANON,
            qualite = 4,
            difficulte = 2,
            flag = null,
            proposedItemId = null,
            latencyMs = 1000,
            createdAt = now,
            campaignId = campaignId,
        )

    private fun pairRow(
        id: PairRatingId,
        owner: UserId?,
    ): PairRating =
        PairRating(
            id = id,
            leftItemId = ItemId(UUID.randomUUID()),
            rightItemId = ItemId(UUID.randomUUID()),
            userId = owner,
            verdict = PreferenceVerdict.LEFT_WINS,
            difficulte = 3,
            latencyMs = 1200,
            createdAt = now,
            campaignId = campaignId,
        )

    private fun <T> runBlockingFixture(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    @Test
    fun `binary undo deletes rating, decrements progress, marks undone`() =
        runTest {
            val f = newFixture(campaignOpen = true)
            val result = f.useCase.execute(token = "tok", sessionUserId = f.userId)
            assertThat(result).isEqualTo(UndoActionResult.Undone)
            assertThat(f.ratings.ratings.isEmpty()).isEqualTo(true)
            assertThat(f.progress.get(f.userId)?.itemsRated).isEqualTo(0)
            assertThat(f.actions.findByTokenHash(sha256("tok"))?.undoneAt).isEqualTo(now)
        }

    @Test
    fun `unknown token is NotFound`() =
        runTest {
            val f = newFixture(campaignOpen = true)
            assertThat(f.useCase.execute("nope", f.userId)).isEqualTo(UndoActionResult.NotFound)
        }

    @Test
    fun `authed action undone by a different user is NotFound`() =
        runTest {
            val f = newFixture(campaignOpen = true)
            assertThat(f.useCase.execute("tok", sessionUserId = UserId(UUID.randomUUID())))
                .isEqualTo(UndoActionResult.NotFound)
        }

    @Test
    fun `already-undone action is NotFound`() =
        runTest {
            val f = newFixture(campaignOpen = true)
            f.useCase.execute("tok", f.userId)
            assertThat(f.useCase.execute("tok", f.userId)).isEqualTo(UndoActionResult.NotFound)
        }

    @Test
    fun `concurrent redeem - second markUndone is rejected and reverse runs once`() =
        runTest {
            val f = newFixture(campaignOpen = true)
            val actionId =
                f.actions.actions
                    .single()
                    .id

            // First caller claims the token.
            assertThat(f.actions.markUndone(actionId, now)).isEqualTo(true)
            // Second concurrent caller (both passed the pre-tx guard) loses the conditional claim.
            assertThat(f.actions.markUndone(actionId, now)).isEqualTo(false)
        }

    @Test
    fun `double execute does not reverse twice`() =
        runTest {
            val f = newFixture(campaignOpen = true)
            assertThat(f.useCase.execute("tok", f.userId)).isEqualTo(UndoActionResult.Undone)
            // Simulate a second in-flight caller that also passed the pre-tx guard reaching the tx.
            val replay = RatingId(UUID.randomUUID())
            f.ratings.insert(rating(replay, ItemId(UUID.randomUUID()), f.userId))
            assertThat(f.useCase.execute("tok", f.userId)).isEqualTo(UndoActionResult.NotFound)
            // reverse() did not run a second time: the replayed rating is untouched.
            assertThat(f.ratings.ratings.any { it.id == replay }).isEqualTo(true)
        }

    @Test
    fun `closed campaign within grace is Undone`() =
        runTest {
            val f = newFixture(campaignOpen = false, closedAt = now.minusSeconds(5))
            assertThat(f.useCase.execute("tok", f.userId)).isEqualTo(UndoActionResult.Undone)
        }

    @Test
    fun `closed campaign past grace is Expired`() =
        runTest {
            val f = newFixture(campaignOpen = false, closedAt = now.minusSeconds(9))
            assertThat(f.useCase.execute("tok", f.userId)).isEqualTo(UndoActionResult.Expired)
        }

    @Test
    fun `anon action undone without a session`() =
        runTest {
            val f = newFixture(campaignOpen = true, anon = true)
            assertThat(f.useCase.execute("tok", sessionUserId = null)).isEqualTo(UndoActionResult.Undone)
        }

    @Test
    fun `text-correctif undo deletes both ratings, proposed_by, and fresh item with no other refs`() =
        runTest {
            val f = newFixture(campaignOpen = true, kind = ActionKind.CORRECTIF, freshItem = true)
            f.useCase.execute("tok", f.userId)
            assertThat(f.items.findById(f.createdItemId!!)).isNull()
            assertThat(f.proposedBy.links.any { it.itemId == f.createdItemId && it.userId == f.userId }).isFalse()
        }

    @Test
    fun `text-correctif undo keeps item when another rating references it`() =
        runTest {
            val f = newFixture(campaignOpen = true, kind = ActionKind.CORRECTIF, freshItem = true, otherRefExists = true)
            f.useCase.execute("tok", f.userId)
            assertThat(f.items.findById(f.createdItemId!!)).isNotNull()
        }

    @Test
    fun `POS-only correctif undo restores prior pos`() =
        runTest {
            val f = newFixture(campaignOpen = true, kind = ActionKind.CORRECTIF, patched = true, priorPos = Pos.NOM_COMMUN)
            f.useCase.execute("tok", f.userId)
            assertThat(f.items.findById(f.patchedItemId!!)?.pos).isEqualTo(Pos.NOM_COMMUN)
        }

    @Test
    fun `pair BOTH_ undo decrements progress twice`() =
        runTest {
            val f = newFixture(campaignOpen = true, kind = ActionKind.PAIR, twoRatings = true, progressStart = 2)
            f.useCase.execute("tok", f.userId)
            assertThat(f.progress.get(f.userId)?.itemsRated).isEqualTo(0)
        }
}
