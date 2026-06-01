package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.usecases.GetNextPairUseCase
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
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

class GetNextPairRouteTest {
    private val left =
        SurveyItem(
            id = ItemId(UUID.fromString("11111111-1111-7111-8111-111111111111")),
            mot = "POMME",
            definition = "Fruit du pommier",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.NOURRITURE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = 5,
            source = Source.SYNTHETIC_V1,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.parse("2026-05-25T00:00:00Z"),
        )

    private val right =
        left.copy(
            id = ItemId(UUID.fromString("22222222-2222-7222-8222-222222222222")),
            definition = "Fruit rouge ou vert",
        )

    @Test
    fun `200 returns the pair when available`() =
        testApplication {
            val repo = StubPairRepo(ItemPair("POMME", left, right))
            val useCase = GetNextPairUseCase(repo)
            application {
                install(ContentNegotiation) { json() }
                routing { getNextPairRoute(useCase) }
            }
            val resp = client.get("/v1/items/pairs/next")
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            val body = resp.bodyAsText()
            assertThat(body).contains("\"mot\":\"POMME\"")
            assertThat(body).contains("\"itemId\":\"11111111-1111-7111-8111-111111111111\"")
            assertThat(body).contains("\"itemId\":\"22222222-2222-7222-8222-222222222222\"")
        }

    @Test
    fun `204 when no pair is available`() =
        testApplication {
            val useCase = GetNextPairUseCase(StubPairRepo(null))
            application {
                install(ContentNegotiation) { json() }
                routing { getNextPairRoute(useCase) }
            }
            val resp = client.get("/v1/items/pairs/next")
            assertThat(resp.status).isEqualTo(HttpStatusCode.NoContent)
        }

    @Test
    fun `excluded query is forwarded to the use case`() =
        testApplication {
            val repo = StubPairRepo(null, recordExclude = true)
            val useCase = GetNextPairUseCase(repo)
            application {
                install(ContentNegotiation) { json() }
                routing { getNextPairRoute(useCase) }
            }
            client.get("/v1/items/pairs/next?excluded=${left.id.value}")
            assertThat(repo.lastExclude.contains(left.id)).isTrue()
        }
}

private class StubPairRepo(
    private val pair: ItemPair?,
    private val recordExclude: Boolean = false,
) : SurveyItemRepository {
    var lastExclude: Set<ItemId> = emptySet()

    override suspend fun findById(id: ItemId): SurveyItem? = null

    override suspend fun insert(item: SurveyItem) {}

    override suspend fun insertIfAbsent(item: SurveyItem): SurveyItem = item

    override suspend fun retire(
        id: ItemId,
        at: Instant,
    ) {}

    override suspend fun updatePos(
        id: ItemId,
        pos: com.bliss.survey.domain.model.Pos,
    ) {}

    override suspend fun pickUnratedForUser(
        userId: UserId?,
        tier: Tier,
        exclude: Set<ItemId>,
    ): SurveyItem? = null

    override suspend fun pickPairForUser(
        userId: UserId?,
        exclude: Set<ItemId>,
    ): ItemPair? {
        if (recordExclude) lastExclude = exclude
        return pair
    }

    override suspend fun countUnretiredByTier(): Map<Tier, Int> = emptyMap()

    override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = emptyList()

    override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> = emptyList()

    override suspend fun deleteByIds(ids: Collection<ItemId>) {}

    override suspend fun updateTrainingWeight(
        id: ItemId,
        weight: Double,
    ) {}
}
