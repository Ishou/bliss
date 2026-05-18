package com.bliss.identity.api

import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.infrastructure.persistence.IdentityDatabase
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

// Entrypoint for the identity-api service. Prod chart pins PORT explicitly via the
// deployment env block; the in-app default (7779) is for local dev.
fun main() {
    val config = IdentityApiConfig.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: config.port
    val db =
        IdentityDatabase(
            poolName = "identity-api",
            maxPoolSize = 10,
            requireUrl = true,
        ).apply { start() }
    val dataSource = db.dataSource() ?: error("IdentityDatabase did not produce a DataSource.")
    val natsUrl = System.getenv("NATS_URL") ?: "nats://bliss-nats.wordsparrow:4222"
    val wiring = Wiring.forProduction(config, dataSource, CIO.create(), natsUrl)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(wiring, config)
    }.start(wait = true)
}
