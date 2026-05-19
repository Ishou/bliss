package com.bliss.game.infrastructure.events

import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.impl.Headers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Instant

// Subscribes to MAX_DELIVERIES advisories and republishes exhausted messages to the DLQ stream (ADR-0049).
class MaxDeliveriesDlqRepublisher(
    private val connection: Connection,
    private val jetStreamManagement: JetStreamManagement,
    private val streamName: String,
    private val consumerNames: List<String>,
    private val dlqSubjectPrefix: String = DEFAULT_DLQ_SUBJECT_PREFIX,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    internal data class MaxDeliverAdvisory(
        val stream: String,
        val consumer: String,
        val stream_seq: Long,
        val subject: String? = null,
        val deliveries: Long? = null,
    )

    @Serializable
    internal data class UserIdProbe(
        val userId: String? = null,
    )

    private val log = LoggerFactory.getLogger(MaxDeliveriesDlqRepublisher::class.java)
    private var dispatcher: Dispatcher? = null

    fun start() {
        if (dispatcher != null) return
        val d = connection.createDispatcher { msg -> handleAdvisory(msg.data) }
        consumerNames.forEach { consumer ->
            d.subscribe("\$JS.EVENT.ADVISORY.CONSUMER.MAX_DELIVERIES.$streamName.$consumer")
            log.info("DLQ republisher subscribed to MAX_DELIVERIES advisory for {}/{}", streamName, consumer)
        }
        dispatcher = d
    }

    fun close() {
        dispatcher?.let { runCatching { connection.closeDispatcher(it) } }
        dispatcher = null
    }

    internal fun handleAdvisory(payload: ByteArray) {
        val advisory =
            runCatching { json.decodeFromString(MaxDeliverAdvisory.serializer(), String(payload, Charsets.UTF_8)) }
                .getOrElse { e ->
                    log.warn("DLQ republisher: cannot parse MAX_DELIVERIES advisory payload", e)
                    return
                }
        val info =
            try {
                jetStreamManagement.getMessage(advisory.stream, advisory.stream_seq)
            } catch (e: JetStreamApiException) {
                // Likely purged by retention; emit a placeholder DLQ entry so the alert still fires.
                log.warn(
                    "DLQ republisher: original message {}@{} unavailable; publishing placeholder",
                    advisory.stream,
                    advisory.stream_seq,
                    e,
                )
                publishPlaceholder(advisory)
                return
            } catch (e: IOException) {
                log.warn(
                    "DLQ republisher: IO error fetching {}@{}",
                    advisory.stream,
                    advisory.stream_seq,
                    e,
                )
                return
            }
        val originalSubject = info.subject ?: advisory.subject ?: "unknown"
        val dlqSubject = "$dlqSubjectPrefix$originalSubject"
        val headers = buildHeaders(info.headers, advisory)
        connection.publish(dlqSubject, headers, info.data ?: ByteArray(0))
        val userId = probeUserId(info.data)
        log.info(
            "DLQ republisher: dead-lettered {}@{} consumer={} userId={} dlqSubject={}",
            advisory.stream,
            advisory.stream_seq,
            advisory.consumer,
            userId ?: "n/a",
            dlqSubject,
        )
    }

    private fun publishPlaceholder(advisory: MaxDeliverAdvisory) {
        val subject = advisory.subject ?: "unknown"
        val dlqSubject = "$dlqSubjectPrefix$subject"
        val headers = buildHeaders(null, advisory)
        headers.add(HEADER_PAYLOAD_MISSING, "true")
        connection.publish(dlqSubject, headers, ByteArray(0))
    }

    private fun buildHeaders(
        original: Headers?,
        advisory: MaxDeliverAdvisory,
    ): Headers {
        // Copy original headers to preserve correlation IDs / publisher metadata, then layer on DLQ context.
        val out = Headers()
        original?.forEach { key, values -> out.put(key, values) }
        out.put(HEADER_ORIGINAL_STREAM, advisory.stream)
        out.put(HEADER_ORIGINAL_SEQ, advisory.stream_seq.toString())
        out.put(HEADER_CONSUMER, advisory.consumer)
        out.put(HEADER_FAILED_AT, Instant.now().toString())
        advisory.deliveries?.let { out.put(HEADER_DELIVERIES, it.toString()) }
        return out
    }

    private fun probeUserId(data: ByteArray?): String? {
        if (data == null || data.isEmpty()) return null
        return runCatching { json.decodeFromString(UserIdProbe.serializer(), String(data, Charsets.UTF_8)).userId }
            .getOrNull()
    }

    companion object {
        const val DEFAULT_DLQ_SUBJECT_PREFIX: String = "wordsparrow.dlq."
        const val HEADER_ORIGINAL_STREAM: String = "X-Original-Stream"
        const val HEADER_ORIGINAL_SEQ: String = "X-Original-Seq"
        const val HEADER_CONSUMER: String = "X-Consumer"
        const val HEADER_FAILED_AT: String = "X-Failed-At"
        const val HEADER_DELIVERIES: String = "X-Deliveries"
        const val HEADER_PAYLOAD_MISSING: String = "X-Payload-Missing"
        const val USER_EVENTS_STREAM: String = "WORDSPARROW_USER_EVENTS"
    }
}
