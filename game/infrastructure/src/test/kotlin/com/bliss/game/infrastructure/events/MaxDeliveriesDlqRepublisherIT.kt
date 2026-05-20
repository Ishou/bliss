package com.bliss.game.infrastructure.events

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PushSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaxDeliveriesDlqRepublisherIT {
    private lateinit var nats: GenericContainer<*>
    private lateinit var connection: Connection

    @BeforeAll
    fun startContainer() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        nats =
            GenericContainer(DockerImageName.parse("nats:2.10-alpine"))
                .withCommand("-js")
                .withExposedPorts(NATS_PORT)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))
        nats.start()
        val url = "nats://${nats.host}:${nats.getMappedPort(NATS_PORT)}"
        connection =
            Nats.connect(
                Options
                    .Builder()
                    .server(url)
                    .connectionTimeout(Duration.ofSeconds(5))
                    .build(),
            )
        val mgmt = connection.jetStreamManagement()
        mgmt.addStream(
            StreamConfiguration
                .builder()
                .name(STREAM)
                .subjects("wordsparrow.user.>")
                .storageType(StorageType.File)
                .build(),
        )
        mgmt.addStream(
            StreamConfiguration
                .builder()
                .name(DLQ_STREAM)
                .subjects("wordsparrow.dlq.>")
                .storageType(StorageType.File)
                .build(),
        )
    }

    @AfterAll
    fun stop() {
        if (::connection.isInitialized) connection.close()
        if (::nats.isInitialized) nats.stop()
    }

    @Test
    fun `republishes original payload to dlq subject after max-deliveries exhausted`() {
        val durable = "game-api-user-renamed-it-${UUID.randomUUID().toString().take(6)}"
        val js = connection.jetStream()
        val mgmt = connection.jetStreamManagement()

        val attempts = CountDownLatch(MAX_DELIVER)
        val opts =
            PushSubscribeOptions
                .builder()
                .durable(durable)
                .configuration(
                    ConsumerConfiguration
                        .builder()
                        .durable(durable)
                        .ackPolicy(AckPolicy.Explicit)
                        .maxDeliver(MAX_DELIVER.toLong())
                        .ackWait(Duration.ofMillis(500))
                        .build(),
                ).build()
        val sub = js.subscribe("wordsparrow.user.renamed", opts)
        val pumpThread =
            Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val msg = sub.nextMessage(Duration.ofMillis(200)) ?: continue
                        attempts.countDown()
                        msg.nak()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        pumpThread.isDaemon = true
        pumpThread.start()

        val republisher =
            MaxDeliveriesDlqRepublisher(
                connection = connection,
                jetStreamManagement = mgmt,
                streamName = STREAM,
                consumerNames = listOf(durable),
            )
        republisher.start()

        // Republisher uses core Connection.publish; a JetStream consumer would never receive it.
        val dlqStreamSub = connection.subscribe("wordsparrow.dlq.>")
        connection.flush(Duration.ofSeconds(5))

        val userId = UUID.randomUUID().toString()
        val payload = """{"userId":"$userId","newDisplayName":"Renomé","renamedAt":"2026-05-19T12:00:00Z"}"""
        js.publish("wordsparrow.user.renamed", payload.toByteArray(Charsets.UTF_8))

        try {
            assumeTrue(attempts.await(60, TimeUnit.SECONDS)) { "max-deliver redeliveries never fired" }
            val dlqMsg = dlqStreamSub.nextMessage(Duration.ofSeconds(60))
            assertThat(dlqMsg).isNotNull()
            assertThat(dlqMsg.subject).isEqualTo("wordsparrow.dlq.wordsparrow.user.renamed")
            assertThat(String(dlqMsg.data, Charsets.UTF_8)).isEqualTo(payload)
            val headers = dlqMsg.headers
            assertThat(headers).isNotNull()
            assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_ORIGINAL_STREAM)).isEqualTo(STREAM)
            assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_CONSUMER)).isEqualTo(durable)
            assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_ORIGINAL_SEQ)).isEqualTo("1")
            assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_FAILED_AT)).isNotNull()
        } finally {
            pumpThread.interrupt()
            republisher.close()
            runCatching { sub.unsubscribe() }
            runCatching { dlqStreamSub.unsubscribe() }
            runCatching { mgmt.deleteConsumer(STREAM, durable) }
            runCatching { mgmt.purgeStream(STREAM) }
            runCatching { mgmt.purgeStream(DLQ_STREAM) }
        }
    }

    companion object {
        private const val NATS_PORT = 4222
        private const val STREAM = "WORDSPARROW_USER_EVENTS"
        private const val DLQ_STREAM = "WORDSPARROW_USER_EVENTS_DLQ"
        private const val MAX_DELIVER = 2
    }
}
