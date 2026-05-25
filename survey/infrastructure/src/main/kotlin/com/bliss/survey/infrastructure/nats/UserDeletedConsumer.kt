package com.bliss.survey.infrastructure.nats

import com.bliss.survey.application.usecases.AnonymizeUserRatingsUseCase
import com.bliss.survey.domain.model.UserId
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamSubscription
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/** Subscribes to wordsparrow.user.deleted (durable: survey-api-user-deleted) per ADR-0049. */
class UserDeletedConsumer(
    private val nats: Connection,
    private val anonymise: AnonymizeUserRatingsUseCase,
    private val scope: CoroutineScope,
    private val streamName: String = STREAM_NAME,
    private val pollWait: Duration = Duration.ofSeconds(1),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Volatile
    private var job: Job? = null

    @Volatile
    private var subscription: JetStreamSubscription? = null

    fun start(): Job {
        val existing = job
        if (existing != null && existing.isActive) return existing
        val deliverSubject = ensureConsumerConfigured()
        val sub =
            nats.jetStream().subscribe(
                SUBJECT,
                PushSubscribeOptions
                    .builder()
                    .stream(streamName)
                    .durable(DURABLE_NAME)
                    .deliverSubject(deliverSubject)
                    .build(),
            )
        subscription = sub
        val newJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val msg =
                        try {
                            sub.nextMessage(pollWait)
                        } catch (e: IllegalStateException) {
                            // Subscription became inactive (e.g. shutdown); exit the loop quietly.
                            return@launch
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@launch
                        } ?: continue
                    try {
                        val event = json.decodeFromString(UserDeletedPayload.serializer(), msg.data.decodeToString())
                        anonymise.execute(UserId(UUID.fromString(event.userId)))
                        msg.ack()
                    } catch (e: Exception) {
                        log.error("survey_user_deleted_consume_failed subject={} error={}", msg.subject, e.toString(), e)
                        msg.nak()
                    }
                }
            }
        job = newJob
        return newJob
    }

    fun stop() {
        subscription?.let { runCatching { it.unsubscribe() } }
        subscription = null
        job?.cancel()
        job = null
    }

    /** Configure the durable consumer with an explicit deliverSubject so it's a push consumer. */
    private fun ensureConsumerConfigured(): String {
        val deliverSubject = nats.createInbox()
        val cfg =
            ConsumerConfiguration
                .builder()
                .durable(DURABLE_NAME)
                .filterSubject(SUBJECT)
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(Duration.ofSeconds(30))
                .deliverSubject(deliverSubject)
                .build()
        try {
            nats.jetStreamManagement().addOrUpdateConsumer(streamName, cfg)
        } catch (e: JetStreamApiException) {
            log.warn(
                "survey_user_deleted_consumer_register_failed stream={} durable={} error={}",
                streamName,
                DURABLE_NAME,
                e.toString(),
            )
            throw e
        }
        return deliverSubject
    }

    companion object {
        const val SUBJECT: String = "wordsparrow.user.deleted"
        const val STREAM_NAME: String = "WORDSPARROW_USER_EVENTS"
        const val DURABLE_NAME: String = "survey-api-user-deleted"
    }
}

@Serializable
internal data class UserDeletedPayload(
    val userId: String,
    val deletedAt: String,
)
