package com.bliss.grid.api

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Entrypoint. Local-dev default 7777; prod chart pins PORT=8080 explicitly
 *  (grid/api/deploy/chart/values.yaml). */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

internal const val DEFAULT_PORT: Int = 7777
