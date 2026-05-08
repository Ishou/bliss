package com.bliss.game.infrastructure.analytics

import com.bliss.game.application.ports.AnalyticsEvent
import com.bliss.game.application.ports.AnalyticsEventSink
import com.bliss.game.domain.SessionId
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * HTTP adapter that forwards [AnalyticsEvent]s to Matomo's Tracking API
 * (`POST /matomo.php`). Configured for the CNIL audience-measurement consent
 * exemption per ADR-0025 §2 — the server-side configuration in `infra/matomo/`
 * is the load-bearing half; this adapter's job is to send minimal-property
 * events with a daily-rotated visitor hash.
 *
 * **Fire-and-forget.** [record] launches the HTTP call into [scope] and returns
 * immediately. The caller (use case) never blocks, never sees a failure. A
 * non-2xx response or an exception is logged at WARN and dropped.
 *
 * **No persistent visitor identifier.** The `_id` field that Matomo expects is
 * computed as a SHA-256 hash of `sessionId | salt | UTC-day`, truncated to 16
 * hex chars. Same browser is consistent within a day (so funnels work); not
 * linkable across days (so the deployment stays consent-exempt). The raw
 * `SessionId` is never sent.
 *
 * @param httpClient Ktor client; vanilla instance is fine. Decoding is by hand
 *   (we don't decode response bodies), so no ContentNegotiation plugin needed.
 * @param baseUrl scheme + host of the Matomo server, no trailing slash
 *   (e.g. `https://analytics.wordsparrow.io`).
 * @param siteId Matomo site id (numeric string), set in Matomo admin once.
 * @param idSalt secret used for the visitor hash; rotated independently from
 *   `sessionId` rotation. Must be ≥16 chars. Bootstrapped via env var (see Module.kt).
 * @param scope supervised [CoroutineScope] used for fire-and-forget; cancelled
 *   when the application stops so in-flight calls don't outlive the JVM.
 * @param clock injected for testability; defaults to UTC system clock.
 */
class MatomoAnalyticsAdapter(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val siteId: String,
    private val idSalt: String,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) : AnalyticsEventSink {
    init {
        require(idSalt.length >= MIN_SALT_LEN) {
            "idSalt must be at least $MIN_SALT_LEN characters; got ${idSalt.length}"
        }
        require(baseUrl.startsWith("http")) { "baseUrl must include scheme (got: $baseUrl)" }
    }

    override suspend fun record(
        event: AnalyticsEvent,
        sessionId: SessionId?,
    ) {
        scope.launch {
            try {
                send(event, sessionId)
            } catch (cause: Throwable) {
                logger.warn("matomo event dropped: $event ({})", cause.javaClass.simpleName)
            }
        }
    }

    private suspend fun send(
        event: AnalyticsEvent,
        sessionId: SessionId?,
    ) {
        val wire = WireMapper.toWire(event)
        val response =
            httpClient.post("$baseUrl/matomo.php") {
                parameter("idsite", siteId)
                parameter("rec", "1")
                parameter("e_c", CONTEXT_CATEGORY)
                parameter("e_a", wire.action)
                wire.name?.let { parameter("e_n", it) }
                wire.value?.let { parameter("e_v", it.toString()) }
                sessionId?.let { parameter("_id", visitorHash(it)) }
                parameter(
                    "rand",
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .take(8),
                )
            }
        if (!response.status.isSuccess()) {
            logger.warn(
                "matomo non-2xx for {}: {} {}",
                wire.action,
                response.status.value,
                response.bodyAsText().take(MAX_BODY_LOG),
            )
        }
    }

    private fun visitorHash(sessionId: SessionId): String =
        VisitorHash.compute(
            sessionId = sessionId.value,
            idSalt = idSalt,
            day = LocalDate.now(clock.withZone(ZoneOffset.UTC)),
        )

    companion object {
        private val logger = LoggerFactory.getLogger(MatomoAnalyticsAdapter::class.java)
        private const val CONTEXT_CATEGORY = "game"
        private const val MIN_SALT_LEN = 16
        private const val MAX_BODY_LOG = 200
    }
}

/**
 * Daily-rotated salted visitor hash. Pulled out of the adapter for direct unit
 * testing (no HTTP, no coroutine timing). Output is exactly 16 hex chars per
 * Matomo's `_id` field spec.
 */
internal object VisitorHash {
    // Matomo's `_id` expects exactly 16 hex chars (8 bytes).
    private const val BYTES = 8

    fun compute(
        sessionId: String,
        idSalt: String,
        day: LocalDate,
    ): String {
        val input = "$sessionId|$idSalt|$day"
        val sha = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return sha.take(BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Maps sealed [AnalyticsEvent]s to the wire shape Matomo's Custom Events API expects.
 * Pulled out for table-driven testing; nothing here depends on Ktor.
 */
internal object WireMapper {
    data class Wire(
        val action: String,
        val name: String?,
        val value: Long? = null,
    )

    fun toWire(event: AnalyticsEvent): Wire =
        when (event) {
            is AnalyticsEvent.LobbyCreated ->
                Wire(action = "lobby_created:v1", name = "gridSize=${event.gridSize}")
            is AnalyticsEvent.LobbyJoined ->
                Wire(action = "lobby_joined:v1", name = null, value = event.playerCount.toLong())
            is AnalyticsEvent.GameStarted ->
                Wire(
                    action = "game_started:v1",
                    name = "gridSize=${event.gridSize};playerCount=${event.playerCount}",
                )
            is AnalyticsEvent.GameSolved ->
                Wire(
                    action = "game_solved:v1",
                    name = "gridSize=${event.gridSize};playerCount=${event.playerCount}",
                    value = event.durationMs,
                )
            is AnalyticsEvent.PlayerRenamed -> Wire(action = "player_renamed:v1", name = null)
            is AnalyticsEvent.LobbyLeft -> Wire(action = "lobby_left:v1", name = null)
        }
}
