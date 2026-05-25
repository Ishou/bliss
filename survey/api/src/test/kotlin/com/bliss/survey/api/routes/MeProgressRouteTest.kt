package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.api.WIRE_JSON
import com.bliss.survey.api.auth.SESSION_COOKIE_NAME
import com.bliss.survey.api.auth.SessionMiddleware
import com.bliss.survey.application.ports.UserProgress
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.UserId
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

class MeProgressRouteTest {
    private val userUuid = UUID.fromString("33333333-3333-7333-8333-333333333333")

    @Test
    fun `anon caller gets 401 problem details`() =
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { meProgressRoute(EmptyProgressRepo()) }
            }
            val resp = client.get("/v1/me/progress")
            assertThat(resp.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(resp.bodyAsText()).contains("sign-in required")
        }

    @Test
    fun `auth caller gets 200 with itemsRated zero defaults`() =
        testApplication {
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json(WIRE_JSON) }
                routing { meProgressRoute(EmptyProgressRepo()) }
            }
            val resp =
                client.get("/v1/me/progress") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            val body = resp.bodyAsText()
            assertThat(body).contains("\"itemsRated\":0")
            // required-nullable fields must appear as null, not be absent (ADR-0003 §6)
            assertThat(body).contains("\"calibrationAgreement\":null")
            assertThat(body).contains("\"lastRatedAt\":null")
        }

    @Test
    fun `auth caller gets populated progress`() =
        testApplication {
            val state =
                UserProgress(
                    userId = UserId(userUuid),
                    itemsRated = 42,
                    calibrationAgreement = 0.75,
                    lastRatedAt = Instant.parse("2026-05-25T10:30:00Z"),
                )
            application {
                install(SessionMiddleware) { verifyCookie = { userUuid } }
                install(ContentNegotiation) { json() }
                routing { meProgressRoute(StubProgressRepo(state)) }
            }
            val resp =
                client.get("/v1/me/progress") {
                    cookie(SESSION_COOKIE_NAME, "valid-token")
                }
            assertThat(resp.status).isEqualTo(HttpStatusCode.OK)
            assertThat(resp.bodyAsText()).contains("\"itemsRated\":42")
            assertThat(resp.bodyAsText()).contains("\"calibrationAgreement\":0.75")
            assertThat(resp.bodyAsText()).contains("2026-05-25T10:30:00Z")
        }
}

private class EmptyProgressRepo : UserProgressRepository {
    override suspend fun incrementItemsRated(
        userId: UserId,
        at: Instant,
    ) {}

    override suspend fun updateCalibrationAgreement(
        userId: UserId,
        agreement: Double,
    ) {}

    override suspend fun get(userId: UserId): UserProgress? = null

    override suspend fun deleteByUser(userId: UserId) {}
}

private class StubProgressRepo(
    private val state: UserProgress,
) : UserProgressRepository {
    override suspend fun incrementItemsRated(
        userId: UserId,
        at: Instant,
    ) {}

    override suspend fun updateCalibrationAgreement(
        userId: UserId,
        agreement: Double,
    ) {}

    override suspend fun get(userId: UserId): UserProgress? = if (userId == state.userId) state else null

    override suspend fun deleteByUser(userId: UserId) {}
}
