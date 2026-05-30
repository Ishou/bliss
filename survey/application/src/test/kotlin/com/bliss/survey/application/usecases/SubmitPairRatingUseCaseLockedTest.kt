package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.PairVerdict
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SubmitPairRatingUseCaseLockedTest {
    private val now: Instant = Instant.parse("2026-05-30T12:00:00Z")
    private val clock = Clock { now }

    private fun idGen() =
        object : IdGenerator {
            private var counter = 0L

            override fun next(): UUID = UUID(0L, counter++)
        }

    private fun campaign(): Campaign =
        Campaign(
            id = CampaignId(UUID.randomUUID()),
            batchLabel = "round-7",
            openedAt = now.minusSeconds(3600),
            closedAt = null,
        )

    private fun campaignsRepo(current: Campaign?): CampaignRepository =
        object : CampaignRepository {
            override suspend fun findOpen(): Campaign? = current

            override suspend fun findCurrent(): Campaign? = current

            override suspend fun findById(id: CampaignId): Campaign? = current
        }

    private fun item(
        mot: String,
        suffix: String,
    ): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = "Definition $suffix",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.AUTRE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = mot.length,
            source = Source.SYNTHETIC_V1,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = now,
        )

    private fun wire(currentCampaign: Campaign?): Setup {
        val items = InMemorySurveyItemRepository()
        val ratings = InMemoryRatingRepository()
        val pairRatings = InMemoryPairRatingRepository()
        val progress = InMemoryUserProgressRepository()
        val uc =
            SubmitPairRatingUseCase(
                items = items,
                ratings = ratings,
                pairRatings = pairRatings,
                progress = progress,
                ids = idGen(),
                clock = clock,
                campaigns = campaignsRepo(currentCampaign),
            )
        return Setup(uc, items, ratings, pairRatings, progress)
    }

    private data class Setup(
        val uc: SubmitPairRatingUseCase,
        val items: InMemorySurveyItemRepository,
        val ratings: InMemoryRatingRepository,
        val pairRatings: InMemoryPairRatingRepository,
        val progress: InMemoryUserProgressRepository,
    )

    @Test
    fun `returns Locked when no open campaign exists`() =
        runTest {
            val setup = wire(currentCampaign = null)
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            setup.items.insert(a)
            setup.items.insert(b)
            val r =
                setup.uc.execute(
                    SubmitPairRatingCommand(
                        leftItemId = a.id,
                        rightItemId = b.id,
                        userId = UserId(UUID.randomUUID()),
                        verdict = PairVerdict.LEFT_WINS,
                        difficulte = 3,
                        latencyMs = 1200,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitPairRatingResult.Locked::class)
            assertThat(setup.pairRatings.rows).hasSize(0)
            assertThat(setup.ratings.ratings).hasSize(0)
        }

    @Test
    fun `LEFT_WINS stamps campaign_id on the pair row`() =
        runTest {
            val c = campaign()
            val setup = wire(currentCampaign = c)
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            setup.items.insert(a)
            setup.items.insert(b)
            val r =
                setup.uc.execute(
                    SubmitPairRatingCommand(a.id, b.id, UserId(UUID.randomUUID()), PairVerdict.LEFT_WINS, 3, 1200),
                )
            assertThat(r).isInstanceOf(SubmitPairRatingResult.Recorded::class)
            val recorded = r as SubmitPairRatingResult.Recorded
            assertThat(recorded.campaignId).isEqualTo(c.id)
            assertThat(
                setup.pairRatings.rows
                    .single()
                    .campaignId,
            ).isEqualTo(c.id)
        }

    @Test
    fun `BOTH_GOOD stamps campaign_id on both absolute Rating rows`() =
        runTest {
            val c = campaign()
            val setup = wire(currentCampaign = c)
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            setup.items.insert(a)
            setup.items.insert(b)
            val r =
                setup.uc.execute(
                    SubmitPairRatingCommand(a.id, b.id, UserId(UUID.randomUUID()), PairVerdict.BOTH_GOOD, 3, 1200),
                )
            assertThat(r).isInstanceOf(SubmitPairRatingResult.Recorded::class)
            assertThat((r as SubmitPairRatingResult.Recorded).campaignId).isEqualTo(c.id)
            assertThat(setup.ratings.ratings).hasSize(2)
            assertThat(setup.ratings.ratings.all { it.campaignId == c.id }).isEqualTo(true)
        }

    @Test
    fun `BOTH_BAD stamps campaign_id on both absolute Rating rows`() =
        runTest {
            val c = campaign()
            val setup = wire(currentCampaign = c)
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            setup.items.insert(a)
            setup.items.insert(b)
            setup.uc.execute(
                SubmitPairRatingCommand(a.id, b.id, null, PairVerdict.BOTH_BAD, 3, 1200),
            )
            assertThat(setup.ratings.ratings).hasSize(2)
            assertThat(setup.ratings.ratings.all { it.campaignId == c.id }).isEqualTo(true)
        }

    @Test
    fun `SKIP still returns Skipped when locked`() =
        runTest {
            val setup = wire(currentCampaign = null)
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            setup.items.insert(a)
            setup.items.insert(b)
            val r =
                setup.uc.execute(
                    SubmitPairRatingCommand(a.id, b.id, UserId(UUID.randomUUID()), PairVerdict.SKIP, 3, 500),
                )
            assertThat(r).isEqualTo(SubmitPairRatingResult.Skipped)
        }
}
