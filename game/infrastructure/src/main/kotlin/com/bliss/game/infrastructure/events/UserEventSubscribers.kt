package com.bliss.game.infrastructure.events

import com.bliss.game.application.lobby.LobbyWriteCoordinator
import com.bliss.game.application.ports.LobbyRepository
import com.bliss.game.application.ports.LobbyRosterBroadcaster
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.UserId
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

/** ADR-0049 durable JetStream push consumers for wordsparrow.user.{deleted,renamed}. Explicit-ack, maxDeliver=5; handler throws naks for redelivery. */
class UserEventSubscribers(
    private val jetStream: JetStream,
    private val lobbies: LobbyRepository,
    private val rosterBroadcaster: LobbyRosterBroadcaster,
    private val coordinator: LobbyWriteCoordinator,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    internal data class UserDeletedPayload(
        val userId: String,
        val deletedAt: String,
    )

    @Serializable
    internal data class UserRenamedPayload(
        val userId: String,
        val newDisplayName: String,
        val renamedAt: String,
    )

    private val log = LoggerFactory.getLogger(UserEventSubscribers::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subs = mutableListOf<JetStreamSubscription>()

    fun start() {
        subs +=
            subscribe(SUBJECT_DELETED, DURABLE_DELETED) { msg ->
                val event = json.decodeFromString(UserDeletedPayload.serializer(), String(msg.data, Charsets.UTF_8))
                val userId = UserId(event.userId)
                val touched =
                    coordinator.withUserLock(userId) { conn ->
                        lobbies.anonymizeUserSeats(conn, userId, REPLACEMENT_PSEUDONYM)
                    }
                touched.forEach { rosterBroadcaster.notifyRosterChanged(it) }
                log.info("user.deleted processed: userId={} touched={} lobbies", event.userId, touched.size)
            }
        subs +=
            subscribe(SUBJECT_RENAMED, DURABLE_RENAMED) { msg ->
                val event = json.decodeFromString(UserRenamedPayload.serializer(), String(msg.data, Charsets.UTF_8))
                val userId = UserId(event.userId)
                val newPseudonym = Pseudonym.of(event.newDisplayName)
                val touched =
                    coordinator.withUserLock(userId) { conn ->
                        lobbies.refreshUserPseudonym(conn, userId, newPseudonym)
                    }
                touched.forEach { rosterBroadcaster.notifyRosterChanged(it) }
                log.info("user.renamed processed: userId={} touched={} lobbies", event.userId, touched.size)
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
        const val SUBJECT_RENAMED: String = "wordsparrow.user.renamed"
        private const val DURABLE_DELETED: String = "game-api-user-deleted"
        private const val DURABLE_RENAMED: String = "game-api-user-renamed"
        private const val QUEUE_GROUP: String = "game-api-user-events"

        /** Fixed replacement pseudonym for seats whose owner deleted their account (ADR-0049). */
        internal val REPLACEMENT_PSEUDONYM: Pseudonym = Pseudonym.of("Joueur supprimé")
    }
}
