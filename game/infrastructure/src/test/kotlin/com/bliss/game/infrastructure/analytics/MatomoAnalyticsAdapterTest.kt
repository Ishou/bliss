package com.bliss.game.infrastructure.analytics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.game.domain.SessionId
import com.bliss.game.domain.analytics.AnalyticsEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MatomoAnalyticsAdapterTest {
    private val sessionId = SessionId("018ed5cf-0000-7000-8000-000000000001")
    private val fixedClock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC)
    private val salt = "0123456789abcdef-test-salt"

    private fun adapter(
        scope: CoroutineScope,
        client: HttpClient,
    ): MatomoAnalyticsAdapter =
        MatomoAnalyticsAdapter(
            httpClient = client,
            baseUrl = "http://matomo.test",
            siteId = "1",
            idSalt = salt,
            scope = scope,
            clock = fixedClock,
        )

    @Test
    fun `wire mapper covers every AnalyticsEvent subtype`() {
        // Compiler-enforced via sealed when; this just sanity-checks the action strings.
        val mapped =
            listOf<AnalyticsEvent>(
                AnalyticsEvent.LobbyCreated(gridSize = "11x11"),
                AnalyticsEvent.LobbyJoined(playerCount = 2),
                AnalyticsEvent.GameStarted(gridSize = "11x11", playerCount = 3),
                AnalyticsEvent.GameSolved(gridSize = "11x11", playerCount = 3, durationMs = 12_345),
                AnalyticsEvent.PlayerRenamed,
                AnalyticsEvent.LobbyLeft,
                AnalyticsEvent.LobbyCodeRotated,
            ).map { WireMapper.toWire(it).action }

        assertThat(mapped).isEqualTo(
            listOf(
                "lobby_created:v1",
                "lobby_joined:v1",
                "game_started:v1",
                "game_solved:v1",
                "player_renamed:v1",
                "lobby_left:v1",
                "lobby_code_rotated:v1",
            ),
        )
    }

    @Test
    fun `record posts to matomo with hashed visitor id and event params`() =
        runTest {
            val captured = CompletableDeferred<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        captured.complete(request.url.fullPath)
                        respond("ok", HttpStatusCode.OK)
                    },
                )
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            adapter(scope, client).record(AnalyticsEvent.LobbyCreated("11x11"), sessionId)
            advanceUntilIdle()

            val path = captured.await()
            assertThat(path).contains("/matomo.php")
            assertThat(path).contains("idsite=1")
            assertThat(path).contains("rec=1")
            assertThat(path).contains("e_c=game")
            assertThat(path).contains("e_a=lobby_created%3Av1")
            assertThat(path).contains("gridSize%3D11x11")
            // _id must be exactly 16 hex chars per Matomo spec.
            val idMatch = Regex("_id=([0-9a-f]+)").find(path)
            assertThat(idMatch).isNotNull()
            assertThat(idMatch!!.groupValues[1]).hasLength(16)
        }

    @Test
    fun `record without sessionId omits the visitor id parameter`() =
        runTest {
            val captured = CompletableDeferred<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        captured.complete(request.url.fullPath)
                        respond("ok", HttpStatusCode.OK)
                    },
                )
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            adapter(scope, client).record(AnalyticsEvent.PlayerRenamed, sessionId = null)
            advanceUntilIdle()

            val path = captured.await()
            assertThat(path).contains("e_a=player_renamed%3Av1")
            assert(!path.contains("_id=")) { "expected no _id parameter, got: $path" }
        }

    @Test
    fun `visitor hash is deterministic, 16 hex chars, and differs across days`() {
        val day1 = java.time.LocalDate.parse("2026-05-08")
        val day2 = java.time.LocalDate.parse("2026-05-09")
        val sid = sessionId.value

        val h1 = VisitorHash.compute(sid, salt, day1)
        val h2 = VisitorHash.compute(sid, salt, day1)
        val h3 = VisitorHash.compute(sid, salt, day2)

        assertThat(h1).hasLength(16)
        assertThat(h1).isEqualTo(h2) // deterministic
        assert(h1 != h3) { "hash must differ across days; got $h1 == $h3" }
    }

    @Test
    fun `visitor hash differs across sessions on the same day`() {
        val day = java.time.LocalDate.parse("2026-05-08")
        val a = VisitorHash.compute("session-A", salt, day)
        val b = VisitorHash.compute("session-B", salt, day)
        assert(a != b) { "different sessions must hash differently; got $a" }
    }

    @Test
    fun `record swallows non-2xx responses (fire-and-forget)`() =
        runTest {
            val client =
                HttpClient(
                    MockEngine {
                        respondError(HttpStatusCode.InternalServerError)
                    },
                )
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            // No throw expected — fire-and-forget contract.
            adapter(scope, client).record(AnalyticsEvent.PlayerRenamed, sessionId)
            advanceUntilIdle()
        }

    @Test
    fun `record swallows transport exceptions`() =
        runTest {
            val client =
                HttpClient(
                    MockEngine {
                        throw RuntimeException("simulated network blip")
                    },
                )
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            // No throw expected — the launched coroutine catches and logs.
            adapter(scope, client).record(AnalyticsEvent.PlayerRenamed, sessionId)
            advanceUntilIdle()
        }

    @Test
    fun `init accepts a 16-char salt and rejects shorter`() {
        // 16 chars — accepted, no exception.
        MatomoAnalyticsAdapter(
            httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            baseUrl = "http://x",
            siteId = "1",
            idSalt = "0123456789abcdef",
            scope = CoroutineScope(Job()),
            clock = fixedClock,
        )

        val tooShort =
            assertThrows<IllegalArgumentException> {
                MatomoAnalyticsAdapter(
                    httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
                    baseUrl = "http://x",
                    siteId = "1",
                    idSalt = "tooshort",
                    scope = CoroutineScope(Job()),
                    clock = fixedClock,
                )
            }
        assertThat(tooShort.message ?: "").contains("idSalt")
    }
}
