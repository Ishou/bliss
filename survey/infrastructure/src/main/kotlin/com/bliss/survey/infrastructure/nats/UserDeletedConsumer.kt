package com.bliss.survey.infrastructure.nats

import com.bliss.survey.application.usecases.AnonymizeUserRatingsUseCase
import com.bliss.survey.domain.model.UserId
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamSubscription
import io.nats.client.PushSubscribeOptions
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

/** Binds to the pre-created durable consumer survey-api-user-deleted (ADR-0049).
 * The consumer is created by the chart's pre-install/pre-upgrade Job
 * (`survey-worker --bootstrap-consumer`) per CLAUDE.md "Configure-in-cluster".
 * This split fixes the "[10013] consumer name already in use" failure that
 * blocked every rolling deploy: the api pod no longer tries to addOrUpdate
 * the consumer with a fresh `nats.createInbox()` deliverSubject on each boot.
 */
class UserDeletedConsumer(
    private val nats: Connection,
    private val anonymise: AnonymizeUserRatingsUseCase,
    private val scope: CoroutineScope,
    private val streamName: String = UserDeletedConsumerConfig.STREAM_NAME,
    private val durableName: String = UserDeletedConsumerConfig.DURABLE_NAME,
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

    fun start(): Job? =
        synchronized(this) {
            val existing = job
            if (existing != null && existing.isActive) return existing
            // bind(true) fetches the existing consumer's deliverSubject from the
            // server, so the api pod never picks one. If the consumer doesn't
            // exist (e.g. local k3d where the bootstrap Job is disabled), log
            // and stay up — anonymisation won't fire, but the rest of the api
            // is independent. In prod the chart's pre-install Job creates the
            // consumer before the Deployment is applied.
            val sub =
                try {
                    nats.jetStream().subscribe(
                        UserDeletedConsumerConfig.SUBJECT,
                        PushSubscribeOptions
                            .builder()
                            .bind(true)
                            .stream(streamName)
                            .durable(durableName)
                            .build(),
                    )
                } catch (e: JetStreamApiException) {
                    log.warn(
                        "survey_user_deleted_consumer_bind_failed stream={} durable={} error={}",
                        streamName,
                        durableName,
                        e.toString(),
                    )
                    return null
                } catch (e: IllegalArgumentException) {
                    // jnats raises IllegalArgumentException("Consumer not found.") when
                    // bind(true) targets a consumer that doesn't exist (local k3d without
                    // the bootstrap Job). Same graceful-degrade path as the API error case.
                    log.warn(
                        "survey_user_deleted_consumer_bind_failed stream={} durable={} error={}",
                        streamName,
                        durableName,
                        e.toString(),
                    )
                    return null
                }
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
            newJob
        }

    fun stop() {
        subscription?.let { runCatching { it.unsubscribe() } }
        subscription = null
        job?.cancel()
        job = null
    }

    companion object {
        // Kept for backwards-compat with callers that referenced these via
        // UserDeletedConsumer.SUBJECT / STREAM_NAME / DURABLE_NAME. New code
        // should reference UserDeletedConsumerConfig directly.
        const val SUBJECT: String = UserDeletedConsumerConfig.SUBJECT
        const val STREAM_NAME: String = UserDeletedConsumerConfig.STREAM_NAME
        const val DURABLE_NAME: String = UserDeletedConsumerConfig.DURABLE_NAME
    }
}

@Serializable
internal data class UserDeletedPayload(
    val userId: String,
    val deletedAt: String,
)
