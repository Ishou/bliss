package com.bliss.grid.infrastructure.analytics

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasLength
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.grid.application.analytics.AnalyticsEvent
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
import java.util.UUID

class MatomoAnalyticsAdapterTest {
    private val sessionId: UUID = UUID.fromString("018ed5cf-0000-7000-8000-000000000001")
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
        val mapped =
            listOf<AnalyticsEvent>(
                AnalyticsEvent.PuzzleGenerated(gridSize = "11x11", language = "fr"),
                AnalyticsEvent.HintUsed(gridSize = "11x11", hintsUsedSoFar = 2),
            ).map { WireMapper.toWire(it).action }

        assertThat(mapped).isEqualTo(listOf("puzzle_generated:v1", "hint_used:v1"))
    }

    @Test
    fun `record posts to matomo with grid category and event params`() =
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

            adapter(scope, client).record(AnalyticsEvent.HintUsed("11x11", 3), sessionId)
            advanceUntilIdle()

            val path = captured.await()
            assertThat(path).contains("/matomo.php")
            assertThat(path).contains("e_c=grid")
            assertThat(path).contains("e_a=hint_used%3Av1")
            assertThat(path).contains("e_v=3")
            val idMatch = Regex("_id=([0-9a-f]+)").find(path)
            assertThat(idMatch).isNotNull()
            assertThat(idMatch!!.groupValues[1]).hasLength(16)
        }

    @Test
    fun `visitor hash is deterministic, 16 hex chars, and differs across days`() {
        val day1 = java.time.LocalDate.parse("2026-05-08")
        val day2 = java.time.LocalDate.parse("2026-05-09")
        val sid = sessionId.toString()

        val h1 = VisitorHash.compute(sid, salt, day1)
        val h2 = VisitorHash.compute(sid, salt, day1)
        val h3 = VisitorHash.compute(sid, salt, day2)

        assertThat(h1).hasLength(16)
        assertThat(h1).isEqualTo(h2) // deterministic
        assert(h1 != h3) { "hash must differ across days; got $h1 == $h3" }
    }

    @Test
    fun `record swallows non-2xx responses`() =
        runTest {
            val client = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) })
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            adapter(scope, client).record(AnalyticsEvent.PuzzleGenerated("9x9", "fr"), sessionId)
            advanceUntilIdle()
            // Implicit assertion: no exception propagated.
        }

    @Test
    fun `record swallows transport exceptions`() =
        runTest {
            val client = HttpClient(MockEngine { throw RuntimeException("network blip") })
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            adapter(scope, client).record(AnalyticsEvent.PuzzleGenerated("9x9", "fr"), sessionId)
            advanceUntilIdle()
        }

    @Test
    fun `init rejects too-short salt`() {
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
