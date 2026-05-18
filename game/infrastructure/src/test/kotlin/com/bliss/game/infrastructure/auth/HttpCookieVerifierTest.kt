package com.bliss.game.infrastructure.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class HttpCookieVerifierTest {
    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val baseUrl = "http://identity.test"
    private val sampleUserId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val okBody = """{"userId":"$sampleUserId","displayName":"Marmotte 900"}"""

    private fun client(
        counter: AtomicInteger,
        handle: (Int) -> io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData,
    ): HttpClient =
        HttpClient(
            MockEngine { _ ->
                val n = counter.incrementAndGet()
                handle(n)()
            },
        )

    @Test
    fun `200 response is parsed and cached`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond(okBody, HttpStatusCode.OK, jsonHeaders) } }
            val verifier = HttpCookieVerifier(http, baseUrl)

            val first = verifier.verify("cookie-value")
            val second = verifier.verify("cookie-value")

            assertThat(first).isNotNull()
            assertThat(first!!.userId).isEqualTo(UserId(sampleUserId))
            assertThat(first.displayName).isEqualTo(Pseudonym.of("Marmotte 900"))
            assertThat(second).isEqualTo(first)
            // Second call must hit the cache, not the wire.
            assertThat(calls.get()).isEqualTo(1)
        }

    @Test
    fun `401 response is cached as anon (null) without re-hitting the wire`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond("", HttpStatusCode.Unauthorized, jsonHeaders) } }
            val verifier = HttpCookieVerifier(http, baseUrl)

            val first = verifier.verify("nope")
            val second = verifier.verify("nope")

            assertThat(first).isNull()
            assertThat(second).isNull()
            assertThat(calls.get()).isEqualTo(1)
        }

    @Test
    fun `5xx fails closed and is NOT cached so the next call retries`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond("", HttpStatusCode.BadGateway, jsonHeaders) } }
            val verifier = HttpCookieVerifier(http, baseUrl)

            val first = verifier.verify("flaky")
            val second = verifier.verify("flaky")

            assertThat(first).isNull()
            assertThat(second).isNull()
            // No caching on 5xx — both calls must reach the wire.
            assertThat(calls.get()).isEqualTo(2)
        }

    @Test
    fun `cache TTL expires and triggers a second HTTP call`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond(okBody, HttpStatusCode.OK, jsonHeaders) } }
            val nowRef =
                java.util.concurrent.atomic
                    .AtomicReference(Instant.parse("2026-05-18T12:00:00Z"))
            val verifier =
                HttpCookieVerifier(
                    http = http,
                    identityApiBaseUrl = baseUrl,
                    cacheTtl = Duration.ofSeconds(30),
                    now = { nowRef.get() },
                )

            verifier.verify("cookie")
            nowRef.set(nowRef.get().plusSeconds(31))
            verifier.verify("cookie")

            assertThat(calls.get()).isEqualTo(2)
        }

    @Test
    fun `null or blank cookie short-circuits without hitting the wire`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond(okBody, HttpStatusCode.OK, jsonHeaders) } }
            val verifier = HttpCookieVerifier(http, baseUrl)

            assertThat(verifier.verify(null)).isNull()
            assertThat(verifier.verify("")).isNull()
            assertThat(verifier.verify("   ")).isNull()
            assertThat(calls.get()).isEqualTo(0)
        }
}
