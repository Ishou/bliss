package com.bliss.grid.infrastructure.events

import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.Options
import java.time.Duration

/** Synchronous grid-api → NATS connection (ADR-0049); mirrors game-api's helper. */
class NatsConnectionFactory(
    private val natsUrl: String,
) {
    fun connect(): Pair<Connection, JetStream> {
        val options =
            Options
                .Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(5))
                .reconnectWait(Duration.ofSeconds(1))
                .maxReconnects(-1)
                .build()
        val connection = Nats.connect(options)
        return connection to connection.jetStream()
    }
}
