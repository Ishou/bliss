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

/** Publishes user.renamed fire-and-forget; discards ack future; transport errors logged and swallowed (ADR-0049). */
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
