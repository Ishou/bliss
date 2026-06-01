package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import com.bliss.survey.api.auth.SESSION_COOKIE_NAME
import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
import io.ktor.client.request.cookie
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

class MeContributionsRouteTest {
    private val userUuid = UUID.fromString("33333333-3333-7333-8333-333333333333")

    private val proposed =
        ProposedContribution(
            item =
                SurveyItem(
                    id = ItemId(UUID.fromString("44444444-4444-7444-8444-444444444444")),
                    mot = "BLEU",
                    definition = "couleur du ciel",
                    pos = Pos.NOM_COMMUN,
                    categorie = Categorie.CONCEPTUEL,
                    style = Style.PERIPHRASE,
                    forceClaimed = 3,
                    longueur = 4,
                    source = Source.RATER_PROPOSED,
                    sourceBatch = "rater_2026-05",
                    tier = Tier.MID,
                    isCalibration = false,
                    expected = null,
                    retiredAt = null,
                    createdAt = Instant.parse("2026-05-25T10:00:00Z"),
                ),
            optedOut = false,
            kCoverage = 2,
        )

    @Test
    fun `anon caller gets 401 problem details`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { meContributionsRoute(ContribsEmptyItemRepo()) }
            }
            val resp = client.get("/v1/me/contributions")
            assertThat(resp.status).isEqualTo(HttpStatusCode.Unauthorized)
            val body = resp.bodyAsText()
            assertThat(body).contains("sign-in required")
            assertThat(body).doesNotContain("\"detail\":null")
            assertThat(body).doesNotContain("\"instance\":null")
        }

    @Test
    fun `auth caller gets 200 with contributions array`() =
        testApplication {
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing { meContributionsRoute(ContribsStubItemRepo(listOf(proposed))) }
            }
            val resp =
                client.get("/v1/me/contributions") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            val body = resp.bodyAsText()
            assertThat(body).contains("\"mot\":\"BLEU\"")
            assertThat(body).contains("\"categorie\":\"conceptuel\"")
            assertThat(body).contains("\"kCoverage\":2")
            assertThat(body).contains("\"optedOut\":false")
        }

    @Test
    fun `auth caller with no contributions gets empty array`() =
        testApplication {
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing { meContributionsRoute(ContribsEmptyItemRepo()) }
            }
            val resp =
                client.get("/v1/me/contributions") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            assertThat(resp.bodyAsText()).isEqualTo("[]")
        }
}

private open class ContribsEmptyItemRepo : SurveyItemRepository {
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
    ): com.bliss.survey.domain.model.ItemPair? = null

    override suspend fun countUnretiredByTier(): Map<Tier, Int> = emptyMap()

    override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = emptyList()

    override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> = emptyList()

    override suspend fun deleteByIds(ids: Collection<ItemId>) {}

    override suspend fun updateTrainingWeight(
        id: ItemId,
        weight: Double,
    ) {}
}

private class ContribsStubItemRepo(
    private val proposed: List<ProposedContribution>,
) : ContribsEmptyItemRepo() {
    override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> = proposed
}
