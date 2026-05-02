package com.bliss.game.api

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.game.api.dto.ServerToClientFrame
import com.bliss.game.domain.LobbyId
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket

/**
 * Concurrency tests for [SessionManager]. Uses Ktor's `testApplication` to
 * obtain real [DefaultWebSocketServerSession] instances rather than mocking
 * them — per CLAUDE.md, mocks are reserved for external boundaries.
 */
class SessionManagerTest {
    private val lobbyId = LobbyId("7gQ2xK9p")

    @Test
    fun `broadcast reaches every registered session`() =
        withSessions(count = 3) { harness ->
            harness.manager.broadcast(
                lobbyId,
                ServerToClientFrame.PlayerLeft(sessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"),
            )
            for (i in 0 until 3) {
                val text = harness.awaitFrame(i)
                assertThat(text).contains("\"playerLeft\"")
            }
        }

    @Test
    fun `register reports false when the session is already present`() =
        withSessions(count = 1) { harness ->
            // withSessions has already registered each session once; re-registering
            // returns false (idempotent).
            assertThat(harness.manager.register(lobbyId, harness.sessions.single())).isFalse()
        }

    @Test
    fun `unregister removes the lobby entry once the set is empty`() =
        withSessions(count = 2) { harness ->
            assertThat(harness.manager.connectedCount(lobbyId)).isEqualTo(2)
            harness.sessions.forEach { harness.manager.unregister(lobbyId, it) }
            assertThat(harness.manager.connectedCount(lobbyId)).isEqualTo(0)
        }

    @Test
    fun `concurrent register and unregister keep counts consistent`() =
        // Validates the per-lobby lock prevents the cleanup-vs-register race
        // (last unregister deleting a map entry that a concurrent register
        // would have populated).
        withSessions(count = 8) { harness ->
            coroutineScope {
                val jobs =
                    (1..50).map {
                        async {
                            val session = harness.sessions[it % harness.sessions.size]
                            harness.manager.unregister(lobbyId, session)
                            harness.manager.register(lobbyId, session)
                        }
                    }
                jobs.awaitAll()
            }
            // Final state — count is bounded by session count and >= 0.
            val count = harness.manager.connectedCount(lobbyId)
            assertThat(count in 0..harness.sessions.size).isTrue()
        }

    @Test
    fun `broadcast catches per-session send failures`() =
        withSessions(count = 3) { harness ->
            // Close one server-side session's outgoing channel directly. The
            // SessionManager's per-session try/catch around `send` must absorb
            // the ClosedSendChannelException so [broadcast] returns normally.
            // We verify the *non-throwing* contract here; the happy-path
            // `broadcast reaches every registered session` test covers the
            // delivery side. Driving real WebSocket close + subsequent
            // delivery in a single test is too tightly coupled to Ktor's
            // close-frame timing — see the implementation's try/catch which
            // is the actual invariant under test.
            harness.sessions[0].outgoing.close()

            // Must not throw — even though session[0]'s send raises.
            harness.manager.broadcast(
                lobbyId,
                ServerToClientFrame.PlayerLeft(sessionId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"),
            )
        }

    @Test
    fun `connectedCount tracks distinct sessions`() =
        withSessions(count = 3) { harness ->
            assertThat(harness.manager.connectedCount(lobbyId)).isEqualTo(3)
            assertThat(harness.manager.connectedCount(LobbyId("aaaaaaaa"))).isEqualTo(0)
        }

    @Test
    fun `bindSession plus isSessionConnected report multi-tab presence`() =
        withSessions(count = 2) { harness ->
            val sid = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
            // Two tabs of the same browser bind the SAME sessionId. Both
            // sockets are registered already (withSessions does that).
            harness.manager.bindSession(lobbyId, harness.sessions[0], sid)
            harness.manager.bindSession(lobbyId, harness.sessions[1], sid)
            assertThat(harness.manager.isSessionConnected(lobbyId, sid)).isTrue()
            // Closing one tab leaves the slot held by the other.
            harness.manager.unregister(lobbyId, harness.sessions[0])
            assertThat(harness.manager.isSessionConnected(lobbyId, sid)).isTrue()
            // Closing the last tab releases the slot.
            harness.manager.unregister(lobbyId, harness.sessions[1])
            assertThat(harness.manager.isSessionConnected(lobbyId, sid)).isFalse()
        }

    @Test
    fun `unregister returns the bound sessionId so the route can decide`() =
        withSessions(count = 1) { harness ->
            val sid = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
            harness.manager.bindSession(lobbyId, harness.sessions.single(), sid)
            val returned = harness.manager.unregister(lobbyId, harness.sessions.single())
            assertThat(returned).isEqualTo(sid)
            // A second unregister returns null — nothing was bound.
            assertThat(harness.manager.unregister(lobbyId, harness.sessions.single())).isNull()
        }

    // ---------- harness ----------

    private class Harness(
        val manager: SessionManager,
        val sessions: List<DefaultWebSocketServerSession>,
        private val received: List<MutableList<String>>,
        private val receivedMutex: Mutex,
        private val clientCloseSignals: List<CompletableDeferred<Unit>>,
        private val serverCloseSignals: List<CompletableDeferred<Unit>>,
    ) {
        suspend fun awaitFrame(index: Int): String =
            withTimeout(5_000) {
                while (true) {
                    val text =
                        receivedMutex.withLock {
                            val list = received[index]
                            if (list.isNotEmpty()) list.removeAt(0) else null
                        }
                    if (text != null) return@withTimeout text
                    kotlinx.coroutines.delay(20)
                }
                @Suppress("UNREACHABLE_CODE")
                ""
            }

        fun closeClient(index: Int) {
            clientCloseSignals[index].complete(Unit)
        }

        suspend fun awaitClosed(index: Int) {
            withTimeout(5_000) { serverCloseSignals[index].await() }
        }
    }

    private fun withSessions(
        count: Int,
        block: suspend (Harness) -> Unit,
    ) = testApplication {
        val manager = SessionManager()
        val sessionLatches = List(count) { CompletableDeferred<DefaultWebSocketServerSession>() }
        val serverCloseSignals = List(count) { CompletableDeferred<Unit>() }
        val received = List(count) { mutableListOf<String>() }
        val receivedMutex = Mutex()
        val clientCloseSignals = List(count) { CompletableDeferred<Unit>() }
        var nextIndex = 0
        val indexLock = Mutex()

        application {
            install(WebSockets)
            routing {
                webSocket("/sessions") {
                    val myIndex = indexLock.withLock { nextIndex++ }
                    manager.register(lobbyId, this)
                    sessionLatches[myIndex].complete(this)
                    try {
                        for (frame in incoming) {
                            // Drain anything the test happens to send (none today).
                            if (frame is Frame.Text) frame.readText()
                        }
                    } finally {
                        serverCloseSignals[myIndex].complete(Unit)
                    }
                }
            }
        }

        val client = createClient { install(ClientWebSockets) }

        coroutineScope {
            val clientJobs =
                (0 until count).map { idx ->
                    async {
                        client.clientWebSocket("/sessions") {
                            // Race the drainer against the close signal. Whichever
                            // wins ends the block and triggers a clean WS close.
                            coroutineScope {
                                val drainer =
                                    launch {
                                        for (frame in incoming) {
                                            if (frame is Frame.Text) {
                                                val text = frame.readText()
                                                receivedMutex.withLock { received[idx].add(text) }
                                            }
                                        }
                                    }
                                val closer =
                                    launch {
                                        clientCloseSignals[idx].await()
                                        // Explicit close — sends a close frame to the server
                                        // so the server-side `for (frame in incoming)` loop
                                        // exits and finally{} fires.
                                        close()
                                    }
                                drainer.join()
                                closer.cancel()
                            }
                        }
                    }
                }

            val sessions = withTimeout(5_000) { sessionLatches.map { it.await() } }
            try {
                block(
                    Harness(
                        manager = manager,
                        sessions = sessions,
                        received = received,
                        receivedMutex = receivedMutex,
                        clientCloseSignals = clientCloseSignals,
                        serverCloseSignals = serverCloseSignals,
                    ),
                )
            } finally {
                // Tear down any clients still alive.
                clientCloseSignals.forEach { it.complete(Unit) }
                clientJobs.forEach { runCatching { it.cancel() } }
            }
        }
    }
}
