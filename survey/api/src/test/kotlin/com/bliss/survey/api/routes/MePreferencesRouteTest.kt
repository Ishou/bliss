package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.survey.api.auth.SESSION_COOKIE_NAME
import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.UserId
import io.ktor.client.request.cookie
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.UUID

class MePreferencesRouteTest {
    private val userUuid = UUID.fromString("33333333-3333-7333-8333-333333333333")

    @Test
    fun `anon caller gets 401`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { mePreferencesRoute(RecordingRepo()) }
            }
            val resp =
                client.patch("/v1/me/preferences") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"deleteProposedOnErasure":true}""")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.Unauthorized)
        }

    @Test
    fun `auth caller 204 and opt-out persists`() =
        testApplication {
            val repo = RecordingRepo()
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing { mePreferencesRoute(repo) }
            }
            val resp =
                client.patch("/v1/me/preferences") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"deleteProposedOnErasure":true}""")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.NoContent)
            assertThat(repo.lastCall).isNotNull()
            assertThat(repo.lastCall!!.first).isEqualTo(UserId(userUuid))
            assertThat(repo.lastCall!!.second).isEqualTo(true)
        }

    @Test
    fun `auth caller can flip opt-out off`() =
        testApplication {
            val repo = RecordingRepo()
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing { mePreferencesRoute(repo) }
            }
            val resp =
                client.patch("/v1/me/preferences") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"deleteProposedOnErasure":false}""")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.NoContent)
            assertThat(repo.lastCall!!.second).isEqualTo(false)
        }
}

private class RecordingRepo : ProposedByRepository {
    var lastCall: Pair<UserId, Boolean>? = null
        private set

    override suspend fun insert(
        itemId: ItemId,
        userId: UserId,
        optedOut: Boolean,
    ) {}

    override suspend fun setOptOut(
        userId: UserId,
        optedOut: Boolean,
    ) {
        lastCall = userId to optedOut
    }

    override suspend fun listOptedOutByUser(userId: UserId): List<ItemId> = emptyList()

    override suspend fun deleteByUser(userId: UserId) {}
}
