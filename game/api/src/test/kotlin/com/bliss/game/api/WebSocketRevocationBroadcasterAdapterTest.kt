package com.bliss.game.api

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.UserId
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket

class WebSocketRevocationBroadcasterAdapterTest {
    private val lobbyId = LobbyId("7gQ2xK9p")
    private val userId = UserId("0190e3a4-7a2c-4c9e-8f1a-9b2d3e4f5a6b")

    @Test
    fun `disconnectAllForUser routes through SessionManager closeAllForUser`() =
        testApplication {
            val manager = SessionManager()
            val adapter = WebSocketRevocationBroadcasterAdapter(manager)
            val sessionLatch = CompletableDeferred<DefaultWebSocketServerSession>()
            val serverCloseSignal = CompletableDeferred<Unit>()

            application {
                install(WebSockets)
                routing {
                    webSocket("/ws") {
                        manager.register(lobbyId, this)
                        manager.bindUserId(lobbyId, this, userId)
                        sessionLatch.complete(this)
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) frame.readText()
                            }
                        } finally {
                            manager.unregister(lobbyId, this)
                            serverCloseSignal.complete(Unit)
                        }
                    }
                }
            }

            val client = createClient { install(ClientWebSockets) }

            coroutineScope {
                val clientJob =
                    async {
                        client.clientWebSocket("/ws") {
                            for (frame in incoming) {
                                if (frame is Frame.Text) frame.readText()
                            }
                        }
                    }

                withTimeout(5_000) { sessionLatch.await() }
                adapter.disconnectAllForUser(userId)
                withTimeout(5_000) { serverCloseSignal.await() }
                awaitAll(clientJob)
            }
            assertThat(manager.closeAllForUser(userId)).isEqualTo(0)
        }
}
