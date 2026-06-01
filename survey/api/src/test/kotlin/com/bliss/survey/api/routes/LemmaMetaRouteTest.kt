package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.api.auth.SESSION_COOKIE_NAME
import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.application.usecases.UpsertSubTagsResult
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.model.WordMeta
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class LemmaMetaRouteTest {
    private val maintainer = UUID.fromString("11111111-1111-7111-8111-111111111111")
    private val player = UUID.fromString("22222222-2222-7222-8222-222222222222")
    private val now: Instant = Instant.parse("2026-06-01T08:00:00Z")

    private fun ApplicationTestBuilder.install(
        getLemmaMeta: suspend (String) -> WordMeta?,
        upsertSubTags: suspend (String, List<String>, UserId) -> UpsertSubTagsResult,
        verifyCookie: suspend (String) -> UUID? = { null },
    ) {
        application {
            install(ContentNegotiation) { json() }
            install(SessionMiddleware) { this.verifyCookie = verifyCookie }
            routing {
                lemmaMetaRoute(getLemmaMeta, upsertSubTags)
            }
        }
    }

    @Test
    fun `get returns empty arrays when lemma has no meta`() =
        testApplication {
            install(getLemmaMeta = { null }, upsertSubTags = { _, _, _ -> UpsertSubTagsResult.Ok })
            val r = client.get("/v1/lemma-meta/chat")
            assertThat(r.status).isEqualTo(HttpStatusCode.OK)
            assertThat(r.bodyAsText()).contains("\"priorSenses\":[]")
            assertThat(r.bodyAsText()).contains("\"priorSubTags\":[]")
        }

    @Test
    fun `get returns existing inventory`() =
        testApplication {
            val meta = WordMeta("chat", listOf("felin"), listOf("animal félin"), now)
            install(getLemmaMeta = { if (it == "chat") meta else null }, upsertSubTags = { _, _, _ -> UpsertSubTagsResult.Ok })
            val r = client.get("/v1/lemma-meta/chat")
            assertThat(r.status).isEqualTo(HttpStatusCode.OK)
            assertThat(r.bodyAsText()).contains("animal félin")
            assertThat(r.bodyAsText()).contains("felin")
        }

    @Test
    fun `put without session returns 401`() =
        testApplication {
            install(getLemmaMeta = { null }, upsertSubTags = { _, _, _ -> UpsertSubTagsResult.Ok })
            val r =
                client.put("/v1/lemma-meta/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"subTags":["felin"]}""")
                }
            assertThat(r.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `put as non-maintainer returns 403`() =
        testApplication {
            install(
                getLemmaMeta = { null },
                upsertSubTags = { _, _, _ -> UpsertSubTagsResult.Forbidden },
                verifyCookie = { cookie -> if (cookie == "p") player else null },
            )
            val r =
                client.put("/v1/lemma-meta/chat") {
                    contentType(ContentType.Application.Json)
                    cookie(SESSION_COOKIE_NAME, "p")
                    setBody("""{"subTags":["felin"]}""")
                }
            assertThat(r.status).isEqualTo(HttpStatusCode.Forbidden)
        }

    @Test
    fun `put as maintainer returns 204`() =
        testApplication {
            var capturedSubTags: List<String>? = null
            install(
                getLemmaMeta = { null },
                upsertSubTags = { _, tags, _ ->
                    capturedSubTags = tags
                    UpsertSubTagsResult.Ok
                },
                verifyCookie = { cookie -> if (cookie == "m") maintainer else null },
            )
            val r =
                client.put("/v1/lemma-meta/chat") {
                    contentType(ContentType.Application.Json)
                    cookie(SESSION_COOKIE_NAME, "m")
                    setBody("""{"subTags":["felin","domestique"]}""")
                }
            assertThat(r.status).isEqualTo(HttpStatusCode.NoContent)
            assertThat(capturedSubTags!!).isEqualTo(listOf("felin", "domestique"))
        }
}
