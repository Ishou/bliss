package com.bliss.grid.infrastructure.analytics

import com.bliss.grid.application.analytics.AnalyticsEventSink
import com.bliss.grid.domain.analytics.AnalyticsEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * HTTP adapter that forwards [AnalyticsEvent]s to Matomo's Tracking API
 * (`POST /matomo.php`). Configured for the CNIL audience-measurement consent
 * exemption per ADR-0025 §2.
 *
 * **Fire-and-forget.** [record] launches the HTTP call into [scope] and returns
 * immediately. A non-2xx response or an exception is logged at WARN and dropped.
 *
 * **No persistent visitor identifier.** The `_id` field is computed as a
 * SHA-256 hash of `sessionId | salt | UTC-day`, truncated to 16 hex chars.
 * Same browser is consistent within a day; not linkable across days.
 *
 * Mirrors the equivalent adapter in `:game:infrastructure`. The two are
 * deliberately duplicated rather than shared via a third module — Konsist
 * forbids cross-context imports and the duplication is small.
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

    override fun record(
        event: AnalyticsEvent,
        sessionId: UUID?,
    ) {
        scope.launch {
            try {
                send(event, sessionId)
            } catch (cause: Throwable) {
                if (cause is CancellationException) throw cause
                logger.warn("matomo event dropped: $event ({})", cause.javaClass.simpleName)
            }
        }
    }

    private suspend fun send(
        event: AnalyticsEvent,
        sessionId: UUID?,
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
                parameter("rand", UUID.randomUUID().toString().take(8))
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

    private fun visitorHash(sessionId: UUID): String =
        VisitorHash.compute(
            sessionId = sessionId.toString(),
            idSalt = idSalt,
            day = LocalDate.now(clock.withZone(ZoneOffset.UTC)),
        )

    companion object {
        private val logger = LoggerFactory.getLogger(MatomoAnalyticsAdapter::class.java)
        private const val CONTEXT_CATEGORY = "grid"
        private const val MIN_SALT_LEN = 16
        private const val MAX_BODY_LOG = 200
    }
}

/**
 * Daily-rotated salted visitor hash. Pulled out of the adapter for direct unit
 * testing. Output is exactly 16 hex chars per Matomo's `_id` field spec.
 */
internal object VisitorHash {
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
 * Sealed-event → Matomo wire-shape mapping. Pulled out for table-driven testing.
 */
internal object WireMapper {
    data class Wire(
        val action: String,
        val name: String?,
        val value: Long? = null,
    )

    fun toWire(event: AnalyticsEvent): Wire =
        when (event) {
            is AnalyticsEvent.PuzzleGenerated ->
                Wire(
                    action = "puzzle_generated:v1",
                    name = "gridSize=${event.gridSize};language=${event.language}",
                )
            is AnalyticsEvent.HintUsed ->
                Wire(
                    action = "hint_used:v1",
                    name = "gridSize=${event.gridSize}",
                    value = event.hintsUsedSoFar.toLong(),
                )
        }
}
