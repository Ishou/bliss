package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.RandomFactory
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.usecases.GetNextItemUseCase
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
import com.bliss.survey.domain.routing.StratifiedSampler
import com.bliss.survey.domain.routing.TierWeights
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class NextItemRouteTest {
    private val fixedItem =
        SurveyItem(
            id = ItemId(UUID.fromString("11111111-1111-7111-8111-111111111111")),
            mot = "TESTMOT",
            definition = "a probe definition",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.AUTRE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = 7,
            source = Source.CURATED_V1,
            sourceBatch = "test_2026-05",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.parse("2026-05-25T00:00:00Z"),
        )

    @Test
    fun `200 returns item shape when pool has a pick`() =
        testApplication {
            val repo = StubItemRepo(listOf(fixedItem))
            val useCase = GetNextItemUseCase(repo, StratifiedSampler(TierWeights.DEFAULT), RandomFactory { Random(0) })
            application {
                install(ContentNegotiation) { json() }
                routing { nextItemRoute(useCase) }
            }
            val resp = client.get("/v1/items/next")
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            val body = resp.bodyAsText()
            assertThat(body).contains("\"itemId\":\"11111111-1111-7111-8111-111111111111\"")
            assertThat(body).contains("\"mot\":\"TESTMOT\"")
            assertThat(body).contains("\"pos\":\"nom_commun\"")
            assertThat(body).contains("\"tier\":\"mid\"")
        }

    @Test
    fun `204 when pool is empty`() =
        testApplication {
            val repo = StubItemRepo(emptyList())
            val useCase = GetNextItemUseCase(repo, StratifiedSampler(TierWeights.DEFAULT), RandomFactory { Random(0) })
            application {
                install(ContentNegotiation) { json() }
                routing { nextItemRoute(useCase) }
            }
            val resp = client.get("/v1/items/next")
            assertThat(resp.status).isEqualTo(HttpStatusCode.NoContent)
        }

    @Test
    fun `excluded query param filters out items`() =
        testApplication {
            val repo = StubItemRepo(listOf(fixedItem))
            val useCase = GetNextItemUseCase(repo, StratifiedSampler(TierWeights.DEFAULT), RandomFactory { Random(0) })
            application {
                install(ContentNegotiation) { json() }
                routing { nextItemRoute(useCase) }
            }
            val resp = client.get("/v1/items/next?excluded=${fixedItem.id.value}")
            assertThat(resp.status).isEqualTo(HttpStatusCode.NoContent)
        }
}

// In-memory impl; mocking own code violates CLAUDE.md.
private class StubItemRepo(
    private val items: List<SurveyItem>,
) : SurveyItemRepository {
    override suspend fun findById(id: ItemId): SurveyItem? = items.firstOrNull { it.id == id }

    override suspend fun insert(item: SurveyItem) {}

    override suspend fun retire(
        id: ItemId,
        at: Instant,
    ) {}

    override suspend fun pickUnratedForUser(
        userId: UserId?,
        tier: Tier,
        exclude: Set<ItemId>,
    ): SurveyItem? = items.firstOrNull { it.tier == tier && it.id !in exclude }

    override suspend fun countUnretiredByTier(): Map<Tier, Int> = emptyMap()

    override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = emptyList()

    override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> = emptyList()

    override suspend fun deleteByIds(ids: Collection<ItemId>) {}
}
