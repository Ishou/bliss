package com.bliss.game.api

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Entrypoint. PORT defaults to 8081 — grid/api owns 8080 in the cluster
 * (ADR-0007 §3, ADR-0009), so the two services can co-exist on a single
 * node without a port collision. Env override for local dev / tests.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

internal const val DEFAULT_PORT: Int = 8081
