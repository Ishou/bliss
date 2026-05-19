package com.bliss.grid.infrastructure.events

import com.bliss.grid.application.puzzle.HintUsageRepository
import io.nats.client.JetStream
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/** ADR-0049 durable JetStream push consumer for wordsparrow.user.deleted; cascade-deletes the user's hint rows. */
class UserEventSubscribers(
    private val jetStream: JetStream,
    private val hintUsage: HintUsageRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    internal data class UserDeletedPayload(
        val userId: String,
        val deletedAt: String? = null,
        val occurredAt: String? = null,
    )

    private val log = LoggerFactory.getLogger(UserEventSubscribers::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subs = mutableListOf<JetStreamSubscription>()

    fun start() {
        subs +=
            subscribe(SUBJECT_DELETED, DURABLE_DELETED) { msg ->
                val event = json.decodeFromString(UserDeletedPayload.serializer(), String(msg.data, Charsets.UTF_8))
                val userId = UUID.fromString(event.userId)
                val deleted = hintUsage.deleteByUser(userId)
                log.info("user.deleted processed: userId={} hintRowsDeleted={}", event.userId, deleted)
            }
    }

    fun close() {
        subs.forEach { runCatching { it.unsubscribe() } }
        scope.cancel()
    }

    private fun subscribe(
        subject: String,
        durable: String,
        handle: suspend (Message) -> Unit,
    ): JetStreamSubscription {
        val opts =
            PushSubscribeOptions
                .builder()
                .durable(durable)
                .deliverGroup(QUEUE_GROUP)
                .configuration(
                    ConsumerConfiguration
                        .builder()
                        .durable(durable)
                        .ackPolicy(AckPolicy.Explicit)
                        .maxDeliver(5)
                        .deliverGroup(QUEUE_GROUP)
                        .build(),
                ).build()
        val sub = jetStream.subscribe(subject, QUEUE_GROUP, opts)
        scope.launch {
            while (isActive) {
                runCatching {
                    val msg = sub.nextMessage(Duration.ofSeconds(5))
                    if (msg != null) {
                        try {
                            handle(msg)
                            msg.ack()
                        } catch (e: Throwable) {
                            log.error("subscriber handler threw; nak-ing", e)
                            msg.nak()
                        }
                    }
                }.onFailure { e ->
                    log.warn("subscriber loop error on {}", subject, e)
                    delay(1_000)
                }
            }
        }
        return sub
    }

    companion object {
        const val SUBJECT_DELETED: String = "wordsparrow.user.deleted"
        private const val DURABLE_DELETED: String = "grid-user-deleted"
        private const val QUEUE_GROUP: String = "grid-user-events"
    }
}
