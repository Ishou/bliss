package com.bliss.game.api

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Entrypoint. Local-dev default 7778 (paired with grid/api's 7777 so
 * `make dev` can run both services on the host without a port clash).
 * Prod chart pins PORT=8081 explicitly via the deployment env block, so
 * the cluster routing in ADR-0007 §3 / ADR-0009 stays unchanged
 * regardless of the in-app default.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

internal const val DEFAULT_PORT: Int = 7778
