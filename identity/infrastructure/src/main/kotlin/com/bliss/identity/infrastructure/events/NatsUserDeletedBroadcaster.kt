package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserDeletedBroadcaster
import com.bliss.identity.domain.user.UserId
import io.nats.client.JetStream
import io.nats.client.api.PublishAck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
private data class UserDeletedPayload(
    val userId: String,
    val deletedAt: String,
)

/**
 * Publishes `wordsparrow.user.deleted` with publish-ack required (3s timeout).
 * GDPR-critical: a missing ack or seqno == 0 throws so `DeleteUserUseCase` aborts
 * the local delete (ADR-0049).
 */
class NatsUserDeletedBroadcaster(
    private val jetStream: JetStream,
    private val publishTimeout: Duration = Duration.ofSeconds(3),
    private val json: Json = Json,
) : UserDeletedBroadcaster {
    override suspend fun broadcast(
        userId: UserId,
        deletedAt: Instant,
    ) {
        val payload =
            json.encodeToString(
                UserDeletedPayload.serializer(),
                UserDeletedPayload(
                    userId = userId.value.toString(),
                    deletedAt = deletedAt.toString(),
                ),
            )
        withContext(Dispatchers.IO) {
            val future = jetStream.publishAsync(SUBJECT, payload.toByteArray(Charsets.UTF_8))
            val ack: PublishAck = future.orTimeout(publishTimeout.toMillis(), TimeUnit.MILLISECONDS).get()
            check(ack.seqno > 0) { "user.deleted publish returned no sequence number; ack=$ack" }
        }
    }

    companion object {
        const val SUBJECT: String = "wordsparrow.user.deleted"
    }
}
