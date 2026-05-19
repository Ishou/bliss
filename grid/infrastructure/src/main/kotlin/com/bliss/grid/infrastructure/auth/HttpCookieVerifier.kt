package com.bliss.grid.infrastructure.auth

import com.bliss.grid.application.auth.CookieVerifier
import com.bliss.grid.application.auth.WhoAmI
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class WhoAmIResponse(
    val userId: String,
    val displayName: String,
)

/** Calls identity-api's `/v1/auth/whoami`; caches authed and anon outcomes; fails closed on errors without caching. */
class HttpCookieVerifier(
    private val http: HttpClient,
    private val identityApiBaseUrl: String,
    private val cacheTtl: Duration = Duration.ofSeconds(30),
    private val now: () -> Instant = Instant::now,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CookieVerifier {
    private data class Entry(
        val value: WhoAmI?,
        val expiresAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    private val log = LoggerFactory.getLogger(HttpCookieVerifier::class.java)

    override suspend fun verify(rawCookieValue: String?): WhoAmI? {
        val cookie = rawCookieValue?.takeIf { it.isNotBlank() } ?: return null
        val current = now()
        val cached = cache[cookie]
        if (cached != null && cached.expiresAt.isAfter(current)) return cached.value

        val response = whoamiCall(cookie, fresh = false) ?: return null
        return interpret(cookie, current, response, fresh = false)
    }

    override suspend fun verifyFresh(rawCookieValue: String?): WhoAmI? {
        val cookie = rawCookieValue?.takeIf { it.isNotBlank() } ?: return null
        val current = now()
        val response = whoamiCall(cookie, fresh = true) ?: return null
        return interpret(cookie, current, response, fresh = true)
    }

    private suspend fun whoamiCall(
        cookie: String,
        fresh: Boolean,
    ): HttpResponse? =
        try {
            http.get("$identityApiBaseUrl/v1/auth/whoami") {
                header("Cookie", "__Secure-ws_session=$cookie")
            }
        } catch (cause: Throwable) {
            if (fresh) {
                log.warn("identity-api whoami unreachable (verifyFresh); failing closed", cause)
            } else {
                log.warn("identity-api whoami unreachable; failing closed", cause)
            }
            null
        }

    private suspend fun interpret(
        cookie: String,
        current: Instant,
        response: HttpResponse,
        fresh: Boolean,
    ): WhoAmI? =
        try {
            when (response.status) {
                HttpStatusCode.OK -> {
                    val body = response.body<String>()
                    val parsed = json.decodeFromString(WhoAmIResponse.serializer(), body)
                    val result = WhoAmI(UUID.fromString(parsed.userId), parsed.displayName)
                    // verifyFresh also populates the cache so a following verify() can hit it.
                    cache[cookie] = Entry(result, current.plus(cacheTtl))
                    result
                }
                HttpStatusCode.Unauthorized -> {
                    // 401 invalidates any positive cached entry so verify() also fails closed for revoked sessions.
                    cache[cookie] = Entry(null, current.plus(cacheTtl))
                    null
                }
                else -> {
                    if (fresh) {
                        log.warn(
                            "identity-api whoami returned {} (verifyFresh); failing closed",
                            response.status.value,
                        )
                    } else {
                        log.warn("identity-api whoami returned {}; failing closed", response.status.value)
                    }
                    null
                }
            }
        } catch (cause: Throwable) {
            if (fresh) {
                log.warn("identity-api whoami response unparseable (verifyFresh); failing closed", cause)
            } else {
                log.warn("identity-api whoami response unreadable/unparseable; failing closed", cause)
            }
            null
        }
}
