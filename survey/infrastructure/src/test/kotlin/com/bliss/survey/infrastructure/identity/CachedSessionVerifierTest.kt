package com.bliss.survey.infrastructure.identity

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CachedSessionVerifierTest {
    private val userId = UUID.fromString("00000000-0000-7000-8000-000000000001")

    @Test
    fun `cache hit returns same result without calling the client`() =
        runTest {
            val calls = AtomicInteger()
            val client = clientThatCountsAndReturns(calls, userId)
            val now = Instant.parse("2026-05-25T12:00:00Z")
            val clock = MutableClock(now)
            val verifier = CachedSessionVerifier(client, ttl = Duration.ofSeconds(30), clock = clock::now)

            assertThat(verifier.verify("c1")).isEqualTo(userId)
            assertThat(verifier.verify("c1")).isEqualTo(userId)
            assertThat(verifier.verify("c1")).isEqualTo(userId)
            assertThat(calls.get()).isEqualTo(1)
            client.close()
        }

    @Test
    fun `cache miss triggers an HTTP call`() =
        runTest {
            val calls = AtomicInteger()
            val client = clientThatCountsAndReturns(calls, userId)
            val now = Instant.parse("2026-05-25T12:00:00Z")
            val verifier = CachedSessionVerifier(client, ttl = Duration.ofSeconds(30)) { now }
            assertThat(verifier.verify("c1")).isEqualTo(userId)
            assertThat(verifier.verify("c2")).isEqualTo(userId)
            assertThat(calls.get()).isEqualTo(2)
            client.close()
        }

    @Test
    fun `entry expires after TTL`() =
        runTest {
            val calls = AtomicInteger()
            val client = clientThatCountsAndReturns(calls, userId)
            val start = Instant.parse("2026-05-25T12:00:00Z")
            val clock = MutableClock(start)
            val verifier = CachedSessionVerifier(client, ttl = Duration.ofSeconds(30), clock = clock::now)

            assertThat(verifier.verify("c1")).isEqualTo(userId)
            clock.advance(Duration.ofSeconds(15))
            assertThat(verifier.verify("c1")).isEqualTo(userId)
            assertThat(calls.get()).isEqualTo(1)
            // beyond TTL, the entry expires; another HTTP call is made
            clock.advance(Duration.ofSeconds(20))
            assertThat(verifier.verify("c1")).isEqualTo(userId)
            assertThat(calls.get()).isEqualTo(2)
            client.close()
        }

    @Test
    fun `blank cookie returns null without touching the cache`() =
        runTest {
            val calls = AtomicInteger()
            val client = clientThatCountsAndReturns(calls, userId)
            val verifier = CachedSessionVerifier(client)
            assertThat(verifier.verify(null)).isNull()
            assertThat(verifier.verify("")).isNull()
            assertThat(calls.get()).isEqualTo(0)
            client.close()
        }

    @Test
    fun `unauthenticated response is cached as null for the TTL`() =
        runTest {
            val calls = AtomicInteger()
            val client =
                IdentityClient(
                    baseUrl = "https://identity.example",
                    engine =
                        MockEngine { _ ->
                            calls.incrementAndGet()
                            respond("", HttpStatusCode.Unauthorized)
                        },
                )
            val verifier = CachedSessionVerifier(client, ttl = Duration.ofSeconds(30)) { Instant.parse("2026-05-25T12:00:00Z") }
            assertThat(verifier.verify("bad")).isNull()
            assertThat(verifier.verify("bad")).isNull()
            assertThat(calls.get()).isEqualTo(1)
            client.close()
        }

    private fun clientThatCountsAndReturns(
        counter: AtomicInteger,
        responseUserId: UUID,
    ): IdentityClient =
        IdentityClient(
            baseUrl = "https://identity.example",
            engine =
                MockEngine { _ ->
                    counter.incrementAndGet()
                    respond(
                        content = """{"userId":"$responseUserId","displayName":"Alice"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                },
        )

    private class MutableClock(
        @Volatile private var current: Instant,
    ) {
        fun now(): Instant = current

        fun advance(d: Duration) {
            current = current.plus(d)
        }
    }
}
