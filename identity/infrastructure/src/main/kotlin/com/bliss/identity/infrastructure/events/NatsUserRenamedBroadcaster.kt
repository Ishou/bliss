package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserRenamedBroadcaster
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.UserId
import io.nats.client.JetStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant

@Serializable
private data class UserRenamedPayload(
    val userId: String,
    val newDisplayName: String,
    val renamedAt: String,
)

/**
 * Publishes `wordsparrow.user.renamed` fire-and-forget per ADR-0049. The
 * `CompletableFuture<PublishAck>` returned by `publishAsync` is discarded; we
 * do not block on the server ack. JetStream covers transient consumer downtime.
 * Transport failures are logged and swallowed — the port contract is "never throws".
 */
class NatsUserRenamedBroadcaster(
    private val jetStream: JetStream,
    private val json: Json = Json,
) : UserRenamedBroadcaster {
    override suspend fun broadcast(
        userId: UserId,
        newDisplayName: DisplayName,
        renamedAt: Instant,
    ) {
        val payload =
            json.encodeToString(
                UserRenamedPayload.serializer(),
                UserRenamedPayload(
                    userId = userId.value.toString(),
                    newDisplayName = newDisplayName.value,
                    renamedAt = renamedAt.toString(),
                ),
            )
        try {
            jetStream.publishAsync(SUBJECT, payload.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            log.warn("user.renamed publish failed for {}", userId.value, e)
        }
    }

    companion object {
        const val SUBJECT: String = "wordsparrow.user.renamed"
        private val log = LoggerFactory.getLogger(NatsUserRenamedBroadcaster::class.java)
    }
}
