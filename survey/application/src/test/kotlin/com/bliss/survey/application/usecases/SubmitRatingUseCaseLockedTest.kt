package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
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

class SubmitRatingUseCaseLockedTest {
    private val now: Instant = Instant.parse("2026-05-30T12:00:00Z")
    private val clock = Clock { now }
    private val idGen =
        object : IdGenerator {
            private var counter = 0L

            override fun next(): UUID = UUID(0L, counter++)
        }

    private fun openCampaign(): Campaign =
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
        }

    private fun seedItem(items: InMemorySurveyItemRepository): SurveyItem {
        val item =
            SurveyItem(
                id = ItemId(UUID.randomUUID()),
                mot = "POMME",
                definition = "Fruit du pommier",
                pos = Pos.NOM_COMMUN,
                categorie = Categorie.ALIMENTS,
                style = Style.DEFINITION_DIRECTE,
                forceClaimed = 3,
                longueur = 5,
                source = Source.CURATED_V1,
                sourceBatch = "batch1",
                tier = Tier.MID,
                isCalibration = false,
                expected = null,
                retiredAt = null,
                createdAt = now,
            )
        kotlinx.coroutines.runBlocking { items.insert(item) }
        return item
    }

    private fun useCase(currentCampaign: Campaign?) =
        Setup(
            InMemorySurveyItemRepository(),
            InMemoryRatingRepository(),
            InMemoryProposedByRepository(),
            InMemoryUserProgressRepository(),
        ).let { setup ->
            val uc =
                SubmitRatingUseCase(
                    items = setup.items,
                    ratings = setup.ratings,
                    proposedBy = setup.proposed,
                    progress = setup.progress,
                    filters = FilterPipeline.default { _ -> false },
                    ids = idGen,
                    clock = clock,
                    campaigns = campaignsRepo(currentCampaign),
                )
            setup.copy(uc = uc)
        }

    private data class Setup(
        val items: InMemorySurveyItemRepository,
        val ratings: InMemoryRatingRepository,
        val proposed: InMemoryProposedByRepository,
        val progress: InMemoryUserProgressRepository,
        val uc: SubmitRatingUseCase? = null,
    )

    @Test
    fun `returns Locked when no open campaign exists`() =
        runTest {
            val setup = useCase(currentCampaign = null)
            val parent = seedItem(setup.items)
            val r =
                setup.uc!!.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = UserId(UUID.randomUUID()),
                        qualite = 5,
                        difficulte = 3,
                        flag = null,
                        correctif = null,
                        latencyMs = 1200,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Locked::class)
            assertThat(setup.ratings.ratings.size).isEqualTo(0)
        }

    @Test
    fun `stamps campaign_id on accepted insert`() =
        runTest {
            val campaign = openCampaign()
            val setup = useCase(currentCampaign = campaign)
            val parent = seedItem(setup.items)
            val r =
                setup.uc!!.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = UserId(UUID.randomUUID()),
                        qualite = 5,
                        difficulte = 3,
                        flag = null,
                        correctif = null,
                        latencyMs = 1200,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            val accepted = r as SubmitRatingResult.Accepted
            assertThat(accepted.rating.campaignId).isNotNull()
            assertThat(accepted.rating.campaignId!!.value).isEqualTo(campaign.id.value)
            assertThat(
                setup.ratings.ratings
                    .single()
                    .campaignId,
            ).isEqualTo(campaign.id)
        }

    @Test
    fun `stamps campaign_id on correctif auto-GOOD insert`() =
        runTest {
            val campaign = openCampaign()
            val setup = useCase(currentCampaign = campaign)
            val parent = seedItem(setup.items)
            val userId = UserId(UUID.randomUUID())
            val r =
                setup.uc!!.execute(
                    SubmitRatingCommand(
                        itemId = parent.id,
                        userId = userId,
                        qualite = 3,
                        difficulte = 3,
                        flag = null,
                        correctif = CorrectifInput("Fruit defendu d'Eve", Style.PERIPHRASE, null),
                        latencyMs = 1500,
                    ),
                )
            assertThat(r).isInstanceOf(SubmitRatingResult.Accepted::class)
            assertThat(setup.ratings.ratings.size).isEqualTo(2)
            assertThat(setup.ratings.ratings.all { it.campaignId == campaign.id }).isEqualTo(true)
        }
}
