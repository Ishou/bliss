package com.bliss.game.infrastructure.auth

import com.bliss.game.application.auth.CookieVerifier
import com.bliss.game.application.auth.WhoAmI
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class WhoAmIResponse(
    val userId: String,
    val displayName: String,
)

/**
 * Calls identity-api `GET /v1/auth/whoami` to resolve the `__Secure-ws_session`
 * cookie. Per the Phase 6c spec: 200 cached for [cacheTtl] (default 30s) as
 * authed, 401 cached for the same window as anon (avoids hammering on bot
 * traffic), 5xx and transport failures fail closed (null) without caching
 * — no retry inside this layer; the next call re-tries.
 */
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

        val response =
            try {
                http.get("$identityApiBaseUrl/v1/auth/whoami") {
                    header("Cookie", "__Secure-ws_session=$cookie")
                }
            } catch (cause: Throwable) {
                log.warn("identity-api whoami unreachable; failing closed", cause)
                return null
            }

        return when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.body<String>()
                val parsed = json.decodeFromString(WhoAmIResponse.serializer(), body)
                val result = WhoAmI(UserId(parsed.userId), Pseudonym.of(parsed.displayName))
                cache[cookie] = Entry(result, current.plus(cacheTtl))
                result
            }
            HttpStatusCode.Unauthorized -> {
                cache[cookie] = Entry(null, current.plus(cacheTtl))
                null
            }
            else -> {
                log.warn("identity-api whoami returned {}; failing closed", response.status.value)
                null
            }
        }
    }
}
