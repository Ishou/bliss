package com.bliss.game.infrastructure.events

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
import org.slf4j.LoggerFactory
import java.time.Duration

/** Durable JetStream push consumers for user.{deleted,renamed}; explicit-ack, maxDeliver=5 (ADR-0049, Phase 6c.1). */
class UserEventSubscribers(
    private val jetStream: JetStream,
) {
    private val log = LoggerFactory.getLogger(UserEventSubscribers::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subs = mutableListOf<JetStreamSubscription>()

    fun start() {
        subs +=
            subscribe(SUBJECT_DELETED, DURABLE_DELETED) { msg ->
                log.info("user.deleted received: {}", String(msg.data, Charsets.UTF_8))
            }
        subs +=
            subscribe(SUBJECT_RENAMED, DURABLE_RENAMED) { msg ->
                log.info("user.renamed received: {}", String(msg.data, Charsets.UTF_8))
            }
    }

    fun close() {
        subs.forEach { runCatching { it.unsubscribe() } }
        scope.cancel()
    }

    private fun subscribe(
        subject: String,
        durable: String,
        handle: (Message) -> Unit,
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
        const val SUBJECT_RENAMED: String = "wordsparrow.user.renamed"
        private const val DURABLE_DELETED: String = "game-api-user-deleted"
        private const val DURABLE_RENAMED: String = "game-api-user-renamed"
        private const val QUEUE_GROUP: String = "game-api-user-events"
    }
}
