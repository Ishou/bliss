package com.bliss.identity.infrastructure.events

import com.bliss.identity.application.ports.UserRoleChangedBroadcaster
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import io.nats.client.JetStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant

@Serializable
private data class UserRoleChangedPayload(
    val userId: String,
    val role: String,
    val changedAt: String,
)

/** Publishes user.role-changed fire-and-forget; transport errors logged and swallowed (ADR-0049). */
class NatsUserRoleChangedBroadcaster(
    private val jetStream: JetStream,
    private val json: Json = Json,
) : UserRoleChangedBroadcaster {
    override suspend fun broadcast(
        userId: UserId,
        role: Role,
        changedAt: Instant,
    ) {
        val payload =
            json.encodeToString(
                UserRoleChangedPayload.serializer(),
                UserRoleChangedPayload(
                    userId = userId.value.toString(),
                    role = role.wire,
                    changedAt = changedAt.toString(),
                ),
            )
        try {
            jetStream.publishAsync(SUBJECT, payload.toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            log.warn("user.role-changed publish failed for {}", userId.value, e)
        }
    }

    companion object {
        const val SUBJECT: String = "wordsparrow.user.role-changed"
        private val log = LoggerFactory.getLogger(NatsUserRoleChangedBroadcaster::class.java)
    }
}
