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

/** Binds to the pre-created durable (ADR-0049); lifecycle owned by chart's pre-install Job. */
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
            // bind(true) lets the server supply the deliverSubject; api stays up if consumer is absent.
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
