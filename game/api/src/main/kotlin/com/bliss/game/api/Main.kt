package com.bliss.game.api

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

// Port 8081 — grid/api owns 8080 (ADR-0009); env PORT overrides for local dev.
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

internal const val DEFAULT_PORT: Int = 8081
