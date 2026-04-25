package com.bliss.grid.api

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Entrypoint. PORT defaults to 8080 (ADR-0007 §3); env override for local dev. */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

internal const val DEFAULT_PORT: Int = 8080
