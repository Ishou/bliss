package com.bliss.grid.infrastructure.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
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
            assertThat(first!!.userId).isEqualTo(UUID.fromString(sampleUserId))
            assertThat(first.displayName).isEqualTo("Marmotte 900")
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
            assertThat(verifier.verifyFresh(null)).isNull()
            assertThat(verifier.verifyFresh("")).isNull()
            assertThat(verifier.verifyFresh("   ")).isNull()
            assertThat(calls.get()).isEqualTo(0)
        }

    @Test
    fun `200 with malformed body fails closed without throwing`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond("{}", HttpStatusCode.OK, jsonHeaders) } }
            val verifier = HttpCookieVerifier(http, baseUrl)

            val result = verifier.verify("cookie-value")

            assertThat(result).isNull()
            assertThat(calls.get()).isEqualTo(1)
        }

    @Test
    fun `verifyFresh bypasses the cache even on a hot key`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond(okBody, HttpStatusCode.OK, jsonHeaders) } }
            val verifier = HttpCookieVerifier(http, baseUrl)

            // Warm the cache via the read path.
            val cached = verifier.verify("cookie-value")
            assertThat(cached).isNotNull()
            assertThat(calls.get()).isEqualTo(1)

            // verifyFresh must bypass the cache → second wire hit.
            val fresh = verifier.verifyFresh("cookie-value")
            assertThat(fresh).isNotNull()
            assertThat(fresh!!.userId).isEqualTo(UUID.fromString(sampleUserId))
            assertThat(calls.get()).isEqualTo(2)
        }

    @Test
    fun `verifyFresh returns null when identity-api returns 401 and invalidates the cached positive`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { n ->
                    {
                        if (n == 1) {
                            respond(okBody, HttpStatusCode.OK, jsonHeaders)
                        } else {
                            respond("", HttpStatusCode.Unauthorized, jsonHeaders)
                        }
                    }
                }
            val verifier = HttpCookieVerifier(http, baseUrl)

            // Cache a positive verify.
            val initial = verifier.verify("cookie-value")
            assertThat(initial).isNotNull()

            // 401 on fresh re-verify (session revoked between read and write).
            val fresh = verifier.verifyFresh("cookie-value")
            assertThat(fresh).isNull()

            // Subsequent cached verify must NOT return the stale positive —
            // verifyFresh's 401 invalidates the cache entry (replaces it with
            // a null entry so future cached reads also fail closed).
            val afterRevoke = verifier.verify("cookie-value")
            assertThat(afterRevoke).isNull()
            // Only 2 wire calls total: the initial 200 and the 401.
            assertThat(calls.get()).isEqualTo(2)
        }

    @Test
    fun `verifyFresh fails closed on 5xx without caching`() =
        runTest {
            val calls = AtomicInteger()
            val http =
                client(calls) { _ -> { respond("", HttpStatusCode.BadGateway, jsonHeaders) } }
            val verifier = HttpCookieVerifier(http, baseUrl)

            val first = verifier.verifyFresh("flaky")
            val second = verifier.verifyFresh("flaky")

            assertThat(first).isNull()
            assertThat(second).isNull()
            // No caching on 5xx for verifyFresh either — both calls hit the wire.
            assertThat(calls.get()).isEqualTo(2)
        }
}
