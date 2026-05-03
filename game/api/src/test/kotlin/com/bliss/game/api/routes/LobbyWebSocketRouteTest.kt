package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.game.api.LobbyUseCases
import com.bliss.game.api.PresencePosition
import com.bliss.game.api.SessionManager
import com.bliss.game.api.SystemClock
import com.bliss.game.application.ports.Clock
import com.bliss.game.application.ports.PuzzleProvider
import com.bliss.game.application.usecases.CreateLobbyUseCase
import com.bliss.game.application.usecases.JoinLobbyUseCase
import com.bliss.game.application.usecases.LeaveLobbyUseCase
import com.bliss.game.application.usecases.RenameSelfUseCase
import com.bliss.game.application.usecases.SetGridConfigUseCase
import com.bliss.game.application.usecases.StartGameUseCase
import com.bliss.game.application.usecases.UpdateCellUseCase
import com.bliss.game.application.usecases.UseCaseOutcome
import com.bliss.game.domain.GamePuzzle
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LobbyId
import com.bliss.game.domain.Position
import com.bliss.game.domain.Pseudonym
import com.bliss.game.domain.SessionId
import com.bliss.game.infrastructure.InMemoryLobbyRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * End-to-end tests for `/v1/lobbies/{lobbyId}/ws`. Drives the route with the
 * Ktor `testApplication` HTTP+WS test client and asserts frame shapes.
 */
class LobbyWebSocketRouteTest {
    private val sessionA = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val sessionB = "0190e3b2-1c45-7d2e-9a3f-b0c1d2e3f4a5"
    private val pseudoA = "Alice"
    private val pseudoB = "Bob"

    @Test
    fun `connecting sends a lobbyState snapshot first`() =
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                val text = receiveText()
                assertThat(text).contains("\"type\":\"lobbyState\"")
                assertThat(text).contains("\"ownerSessionId\":\"$sessionA\"")
            }
        }

    @Test
    fun `cellUpdate from one client is broadcast to both`() =
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            harness.startGame(lobbyId)

            val aReady = CompletableDeferred<Unit>()
            val bReady = CompletableDeferred<Unit>()
            val aSawCell = CompletableDeferred<String>()
            val bSawCell = CompletableDeferred<String>()

            coroutineScope {
                val aJob =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            aReady.complete(Unit)
                            // Drain until we see a cellUpdated frame.
                            while (!aSawCell.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"cellUpdated\"")) aSawCell.complete(text)
                            }
                        }
                    }
                val bJob =
                    async {
                        aReady.await()
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                            bReady.complete(Unit)
                            // After the cellUpdate, both clients should observe cellUpdated.
                            sendText("""{"type":"cellUpdate","row":0,"column":3,"letter":"P"}""")
                            while (!bSawCell.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"cellUpdated\"")) bSawCell.complete(text)
                            }
                        }
                    }

                bReady.await()
                val aText = withTimeout(5_000) { aSawCell.await() }
                val bText = withTimeout(5_000) { bSawCell.await() }
                assertThat(aText.contains("\"type\":\"cellUpdated\"")).isTrue()
                assertThat(bText.contains("\"type\":\"cellUpdated\"")).isTrue()
                assertThat(aText.contains(sessionB)).isTrue()
                assertThat(bText.contains(sessionB)).isTrue()
                aJob.cancel()
                bJob.cancel()
            }
        }

    @Test
    fun `cellUpdate with null letter is broadcast as explicit null to both clients`() =
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            harness.startGame(lobbyId)

            val aReady = CompletableDeferred<Unit>()
            val bReady = CompletableDeferred<Unit>()
            val aSawCell = CompletableDeferred<String>()
            val bSawCell = CompletableDeferred<String>()

            coroutineScope {
                val aJob =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            aReady.complete(Unit)
                            while (!aSawCell.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"cellUpdated\"")) aSawCell.complete(text)
                            }
                        }
                    }
                val bJob =
                    async {
                        aReady.await()
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                            bReady.complete(Unit)
                            // Send a cell-clear (letter: null) — verifies explicitNulls=true is honoured.
                            sendText("""{"type":"cellUpdate","row":0,"column":3,"letter":null}""")
                            while (!bSawCell.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"cellUpdated\"")) bSawCell.complete(text)
                            }
                        }
                    }

                bReady.await()
                val aText = withTimeout(5_000) { aSawCell.await() }
                val bText = withTimeout(5_000) { bSawCell.await() }
                assertThat(aText).contains("\"letter\":null")
                assertThat(bText).contains("\"letter\":null")
                aJob.cancel()
                bJob.cancel()
            }
        }

    @Test
    fun `late joiner receives a fresh snapshot reflecting the current grid config`() =
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            // Owner sets a non-default grid config and waits to observe the
            // resulting snapshot rebroadcast.
            val ownerSawSnapshot = CompletableDeferred<Unit>()
            // Hold the owner socket open until the late joiner has read its
            // snapshot — otherwise the owner's disconnect would trigger the
            // reconnect-grace flow, drop sessionA from the lobby (the only
            // player), and delete the lobby before the late joiner connects.
            val lateJoinerDone = CompletableDeferred<Unit>()
            coroutineScope {
                val ownerJob =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            sendText("""{"type":"setGridConfig","width":9,"height":11}""")
                            while (!ownerSawSnapshot.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"width\":9") && text.contains("\"height\":11")) {
                                    ownerSawSnapshot.complete(Unit)
                                }
                            }
                            lateJoinerDone.await()
                        }
                    }
                ownerSawSnapshot.await()

                // Late joiner connects — initial snapshot must reflect 9x11.
                harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                    val text = receiveText()
                    assertThat(text).contains("\"type\":\"lobbyState\"")
                    assertThat(text).contains("\"width\":9")
                    assertThat(text).contains("\"height\":11")
                }
                lateJoinerDone.complete(Unit)
                ownerJob.cancel()
            }
        }

    @Test
    fun `lobbyState snapshot carries entries typed before the reconnecting client opened its socket`() =
        runWith { harness ->
            // Reproduces the refresh-wipes-letters bug: player A types
            // a letter mid-game; player B (or A on a fresh socket)
            // connects and the FIRST frame they see is `lobbyState`,
            // which must already carry the entry — otherwise a refresh
            // re-renders the grid empty.
            val lobbyId = harness.seedLobby()
            harness.startGame(lobbyId)
            harness.typeLetter(lobbyId, SessionId(sessionA), Position(0, 3), Letter('P'))

            harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                val text = receiveText()
                assertThat(text).contains("\"type\":\"lobbyState\"")
                assertThat(text).contains("\"entries\":[")
                assertThat(text).contains("\"row\":0")
                assertThat(text).contains("\"column\":3")
                assertThat(text).contains("\"letter\":\"P\"")
                assertThat(text).contains("\"sessionId\":\"$sessionA\"")
            }
        }

    @Test
    fun `invalid lobbyId is rejected before registration`() =
        runWith { harness ->
            harness.client.webSocket("/v1/lobbies/not-base58!/ws") {
                val text = receiveText()
                assertThat(text).contains("\"type\":\"error\"")
                assertThat(text).contains("\"errorType\"")
            }
        }

    @Test
    fun `renameSelf with over-length pseudonym sends an error frame to the sender only`() =
        // Regression for the silent-failure rename bug: the sender used to see
        // an unchanged pseudonym with no feedback because Pseudonym.of() threw
        // an IllegalArgumentException that propagated out of the incoming-frame
        // loop and closed the socket. The fix wraps the throw in a structured
        // 'invalid-pseudonym' error frame addressed to the sender; observers
        // receive nothing (the rename never happened), and the socket stays
        // open so the user can correct and retry without a reconnect.
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            val observerJoined = CompletableDeferred<Unit>()
            val observerSawPlayerRenamed = CompletableDeferred<Unit>()
            val observerHoldOpen = CompletableDeferred<Unit>()
            // 33 chars - one over the 32-char Pseudonym.MAX_LENGTH cap.
            val tooLong = "a".repeat(33)
            coroutineScope {
                val observer =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            observerJoined.complete(Unit)
                            // Drain frames until the test releases us; if a stray
                            // playerRenamed for sessionB lands, fail the test.
                            while (!observerHoldOpen.isCompleted) {
                                val text =
                                    withTimeoutOrNull(200) { receiveText() } ?: continue
                                if (text.contains("\"type\":\"playerRenamed\"")) {
                                    observerSawPlayerRenamed.complete(Unit)
                                    return@webSocket
                                }
                            }
                        }
                    }
                observerJoined.await()

                harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                    receiveText() // snapshot
                    sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                    sendText("""{"type":"renameSelf","newPseudonym":"$tooLong"}""")
                    // Read until the structured error frame addressed to the sender
                    // arrives. The previous behaviour was an unhandled exception
                    // server-side which closed the socket — the receive would
                    // throw with a cancelled-channel exception instead of yielding
                    // an error frame, hence the explicit timeout assertion below.
                    // Reaching this assertion at all proves the socket stayed open:
                    // a closed-from-server channel surfaces as a TimeoutCancellationException
                    // out of `receiveText`, which would fail the test before this point.
                    val errorText =
                        withTimeout(5_000) {
                            var seen: String? = null
                            while (seen == null) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"error\"") &&
                                    text.contains("invalid-pseudonym")
                                ) {
                                    seen = text
                                }
                            }
                            seen
                        }
                    assertThat(errorText).contains("\"type\":\"error\"")
                    assertThat(errorText).contains("invalid-pseudonym")
                    assertThat(errorText).contains("\"status\":400")
                }

                observerHoldOpen.complete(Unit)
                observer.await()
                if (observerSawPlayerRenamed.isCompleted) {
                    error("server broadcast playerRenamed for an over-length pseudonym")
                }
            }
        }

    @Test
    fun `disconnect emits a playerLeft frame to remaining members`() =
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            val ownerSawLeft = CompletableDeferred<String>()
            val ownerJoined = CompletableDeferred<Unit>()
            coroutineScope {
                val ownerJob =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            ownerJoined.complete(Unit)
                            while (!ownerSawLeft.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"playerLeft\"") && text.contains(sessionB)) {
                                    ownerSawLeft.complete(text)
                                }
                            }
                        }
                    }
                ownerJoined.await()
                harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                    receiveText() // snapshot
                    sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                    // Drop without leaveLobby — disconnect alone triggers the playerLeft broadcast
                    // once the reconnect-grace window elapses (zero in this test).
                }
                val text = withTimeout(5_000) { ownerSawLeft.await() }
                assertThat(text).contains("\"type\":\"playerLeft\"")
                assertThat(text).contains(sessionB)
                ownerJob.cancel()
            }
        }

    @Test
    fun `joining twice with the same sessionId broadcasts playerJoined exactly once`() =
        runWith { harness ->
            // Same-browser multi-tab repro: tab 2 reuses the localStorage sessionId.
            // The use case is idempotent; the route must NOT re-broadcast a
            // playerJoined frame when the second tab opens, otherwise observer
            // clients would briefly see a duplicate row (then a stale playerLeft).
            val lobbyId = harness.seedLobby()
            val observerSawJoinForB = CompletableDeferred<Unit>()
            val secondJoinSettled = CompletableDeferred<Unit>()
            val observerJoined = CompletableDeferred<Unit>()
            val tab1HoldOpen = CompletableDeferred<Unit>()
            val joinedCount = AtomicInteger(0)
            coroutineScope {
                val observer =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            observerJoined.complete(Unit)
                            // Drain frames; count playerJoined for sessionB.
                            while (!secondJoinSettled.isCompleted ||
                                joinedCount.get() == 0
                            ) {
                                val text =
                                    withTimeoutOrNull(500) { receiveText() } ?: break
                                if (text.contains("\"type\":\"playerJoined\"") && text.contains(sessionB)) {
                                    val seen = joinedCount.incrementAndGet()
                                    if (seen == 1) observerSawJoinForB.complete(Unit)
                                }
                            }
                        }
                    }
                observerJoined.await()

                // Tab 1 for sessionB joins and holds the socket open. The drain
                // loop must keep consuming inbound frames so the channel doesn't
                // back-pressure the server, and must not exit until the test
                // explicitly releases it — otherwise tab 1's close would trigger
                // the reconnect-grace flow and remove sessionB before tab 2 opens.
                val tab1 =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                            while (!tab1HoldOpen.isCompleted) {
                                withTimeoutOrNull(200) { receiveText() }
                            }
                        }
                    }
                observerSawJoinForB.await()

                // Tab 2 for the same sessionB opens and joins. Idempotent — the
                // route must observe the no-op outcome from JoinLobbyUseCase
                // and broadcast nothing.
                harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                    receiveText() // snapshot — should already show sessionB present
                    sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                    // Give the server a moment to process and broadcast (or not).
                    delay(200)
                }
                secondJoinSettled.complete(Unit)
                withTimeout(2_000) { observer.await() }
                assertThat(joinedCount.get()).isEqualTo(1)
                tab1HoldOpen.complete(Unit)
                tab1.await()
            }
        }

    @Test
    fun `closing one of two same-session sockets does not broadcast playerLeft`() =
        // Multi-tab close: same-browser tabs share a sessionId via localStorage.
        // Closing one tab MUST NOT broadcast playerLeft — the player still has
        // another live socket and is still occupying the slot. This is the
        // "mobile saw 1 joueur, web saw 3 joueurs" desync from the field report.
        // Use a non-zero grace so the reconnect window has the opportunity to
        // fire if the eager short-circuit ever regresses.
        runWith(reconnectGrace = 500.milliseconds) { harness ->
            val lobbyId = harness.seedLobby()
            val observerSawLeft = CompletableDeferred<Unit>()
            val observerJoined = CompletableDeferred<Unit>()
            val tab1Joined = CompletableDeferred<Unit>()
            val tab1HoldOpen = CompletableDeferred<Unit>()
            val tab2Done = CompletableDeferred<Unit>()
            coroutineScope {
                val observer =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            observerJoined.complete(Unit)
                            // Wait for tab 2's close + a slack window past the grace.
                            // If a stray playerLeft for sessionB lands, fail.
                            val deadline = System.currentTimeMillis() + 2_500
                            while (System.currentTimeMillis() < deadline) {
                                val text =
                                    withTimeoutOrNull(200) { receiveText() } ?: continue
                                if (text.contains("\"type\":\"playerLeft\"") && text.contains(sessionB)) {
                                    observerSawLeft.complete(Unit)
                                    return@webSocket
                                }
                            }
                        }
                    }
                observerJoined.await()

                // Tab 1 for sessionB joins and is held open by the test —
                // its drain must keep consuming inbound frames so the
                // close that triggers the bug is tab 2's, never tab 1's.
                val tab1 =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                            tab1Joined.complete(Unit)
                            while (!tab1HoldOpen.isCompleted) {
                                withTimeoutOrNull(200) { receiveText() }
                            }
                        }
                    }
                tab1Joined.await()

                // Tab 2 for the same sessionB opens, joins, then closes.
                harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                    receiveText() // snapshot
                    sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                    // close on block exit
                }
                tab2Done.complete(Unit)

                // Drain the observer for the full window — if it surfaces a
                // stray playerLeft, fail.
                observer.await()
                if (observerSawLeft.isCompleted) {
                    error("server broadcast a playerLeft for sessionB while another tab is still connected")
                }
                tab1HoldOpen.complete(Unit)
                tab1.await()
            }
        }

    @Test
    fun `reconnecting within the grace window suppresses the playerLeft broadcast`() =
        // Refresh / brief network blip: the same sessionId reattaches before
        // the 30s window elapses. The pending leave must not fire — the slot
        // is held the whole time from the survivors' point of view.
        runWith(reconnectGrace = 800.milliseconds) { harness ->
            val lobbyId = harness.seedLobby()
            val observerJoined = CompletableDeferred<Unit>()
            val firstJoined = CompletableDeferred<Unit>()
            val secondJoined = CompletableDeferred<Unit>()
            val observerSawLeft = CompletableDeferred<Unit>()
            coroutineScope {
                val observer =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            observerJoined.complete(Unit)
                            // Drain — fail fast if a playerLeft for sessionB ever lands.
                            while (true) {
                                val text =
                                    withTimeoutOrNull(800) { receiveText() } ?: continue
                                if (text.contains("\"type\":\"playerLeft\"") && text.contains(sessionB)) {
                                    observerSawLeft.complete(Unit)
                                    return@webSocket
                                }
                            }
                        }
                    }
                observerJoined.await()

                // Original socket: joins as sessionB, then drops immediately.
                harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                    receiveText() // snapshot
                    sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                    firstJoined.complete(Unit)
                }
                firstJoined.await()

                // Reconnect well inside the grace window with the same sessionId.
                val reconnectHoldOpen = CompletableDeferred<Unit>()
                val reconnect =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                            secondJoined.complete(Unit)
                            while (!reconnectHoldOpen.isCompleted) {
                                withTimeoutOrNull(200) { receiveText() }
                            }
                        }
                    }
                secondJoined.await()
                // Wait past the grace window plus slack.
                delay(1_500)
                val leaked = withTimeoutOrNull(200) { observerSawLeft.await() }
                if (leaked != null) {
                    error("server broadcast playerLeft despite same-sessionId reconnect inside grace window")
                }
                reconnectHoldOpen.complete(Unit)
                reconnect.await()
                observer.cancel()
            }
        }

    @Test
    fun `disconnect with no reconnect inside the grace window broadcasts playerLeft`() =
        // Companion to the above: with no reconnect, the grace must fire and
        // the slot must be freed in the lobby aggregate (so a new joiner sees
        // the right snapshot).
        runWith(reconnectGrace = 200.milliseconds) { harness ->
            val lobbyId = harness.seedLobby()
            val observerSawLeft = CompletableDeferred<Unit>()
            val observerJoined = CompletableDeferred<Unit>()
            val firstJoined = CompletableDeferred<Unit>()
            coroutineScope {
                val observer =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            observerJoined.complete(Unit)
                            while (!observerSawLeft.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"playerLeft\"") && text.contains(sessionB)) {
                                    observerSawLeft.complete(Unit)
                                }
                            }
                        }
                    }
                observerJoined.await()

                harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                    receiveText() // snapshot
                    sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                    firstJoined.complete(Unit)
                }
                firstJoined.await()

                try {
                    withTimeout(5_000) { observerSawLeft.await() }
                } catch (cause: TimeoutCancellationException) {
                    error("expected playerLeft after grace, none arrived: ${cause.message}")
                }
                // After the leave fires, the lobby aggregate must no longer
                // contain sessionB — otherwise a fresh joiner would still see it.
                val lobby = harness.repo.findById(lobbyId)
                assertThat(lobby).isNotNull()
                assertThat(lobby!!.players.containsKey(SessionId(sessionB))).isFalse()
                observer.cancel()
            }
        }

    @Test
    fun `cellFocus from one client broadcasts presenceUpdated to both`() =
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            harness.startGame(lobbyId)

            val aReady = CompletableDeferred<Unit>()
            val bReady = CompletableDeferred<Unit>()
            val aSawPresence = CompletableDeferred<String>()
            val bSawPresence = CompletableDeferred<String>()

            coroutineScope {
                val aJob =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            aReady.complete(Unit)
                            while (!aSawPresence.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"presenceUpdated\"")) aSawPresence.complete(text)
                            }
                        }
                    }
                val bJob =
                    async {
                        aReady.await()
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // initial snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionB","pseudonym":"$pseudoB"}""")
                            bReady.complete(Unit)
                            sendText("""{"type":"cellFocus","row":1,"column":2,"direction":"down"}""")
                            while (!bSawPresence.isCompleted) {
                                val text = receiveText()
                                if (text.contains("\"type\":\"presenceUpdated\"")) bSawPresence.complete(text)
                            }
                        }
                    }

                bReady.await()
                val aText = withTimeout(5_000) { aSawPresence.await() }
                val bText = withTimeout(5_000) { bSawPresence.await() }
                // Both clients (sender included — keeps the wire symmetric with cellUpdated)
                // see the broadcast carrying the sender's sessionId and the focused position.
                assertThat(aText).contains("\"sessionId\":\"$sessionB\"")
                assertThat(aText).contains("\"row\":1")
                assertThat(aText).contains("\"column\":2")
                assertThat(aText).contains("\"direction\":\"down\"")
                assertThat(bText).contains("\"sessionId\":\"$sessionB\"")
                aJob.cancel()
                bJob.cancel()
            }
        }

    @Test
    fun `cellFocus with all-null payload removes the player from presence`() =
        // Sender publishes a focus, then publishes an "all null" frame (the
        // wire signal for "no cell focused"). The next snapshot delivered to
        // a fresh joiner must NOT carry the sender's entry.
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            harness.startGame(lobbyId)

            val ownerJoined = CompletableDeferred<Unit>()
            val ownerSawClear = CompletableDeferred<Unit>()
            val ownerHoldOpen = CompletableDeferred<Unit>()
            coroutineScope {
                val owner =
                    async {
                        harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                            receiveText() // snapshot
                            sendText("""{"type":"joinLobby","sessionId":"$sessionA","pseudonym":"$pseudoA"}""")
                            ownerJoined.complete(Unit)
                            // First focus -> a presenceUpdated with row=0,column=3.
                            sendText("""{"type":"cellFocus","row":0,"column":3,"direction":"across"}""")
                            // Second focus -> all-null payload signals "clear".
                            sendText("""{"type":"cellFocus","row":null,"column":null,"direction":null}""")
                            var seenFocus = false
                            while (!ownerSawClear.isCompleted) {
                                val text = receiveText()
                                if (!text.contains("\"type\":\"presenceUpdated\"")) continue
                                if (!seenFocus) {
                                    seenFocus = true
                                    continue
                                }
                                // Second presenceUpdated must carry row/column/direction = null.
                                if (text.contains("\"row\":null") &&
                                    text.contains("\"column\":null") &&
                                    text.contains("\"direction\":null")
                                ) {
                                    ownerSawClear.complete(Unit)
                                }
                            }
                            ownerHoldOpen.await()
                        }
                    }
                ownerJoined.await()
                withTimeout(5_000) { ownerSawClear.await() }

                // Direct read of the presence map confirms the entry is gone —
                // a future snapshot to a late joiner would not carry sessionA.
                assertThat(harness.sessionManager.getPresence(lobbyId)[sessionA]).isNull()

                ownerHoldOpen.complete(Unit)
                owner.await()
            }
        }

    @Test
    fun `lobbyState snapshot on connect carries current presence`() =
        // A reconnecting client must see peer cursors in its first frame —
        // otherwise the cursor overlay flickers blank until the next focus
        // event arrives.
        runWith { harness ->
            val lobbyId = harness.seedLobby()
            harness.startGame(lobbyId)
            // Pre-populate presence as if another player had focused a cell
            // before this connection opens.
            harness.sessionManager.recordPresence(
                lobbyId,
                sessionB,
                PresencePosition(row = 2, column = 4, direction = "across"),
            )

            harness.client.webSocket("/v1/lobbies/${lobbyId.value}/ws") {
                val text = receiveText()
                assertThat(text).contains("\"type\":\"lobbyState\"")
                assertThat(text).contains("\"presence\":[")
                assertThat(text).contains("\"sessionId\":\"$sessionB\"")
                assertThat(text).contains("\"row\":2")
                assertThat(text).contains("\"column\":4")
                assertThat(text).contains("\"direction\":\"across\"")
            }
        }

    // ---------- harness ----------

    private class Harness(
        val client: HttpClient,
        private val createLobby: CreateLobbyUseCase,
        private val startGameUseCase: StartGameUseCase,
        private val updateCellUseCase: UpdateCellUseCase,
        val sessionManager: SessionManager,
        val repo: InMemoryLobbyRepository,
    ) {
        suspend fun seedLobby(): LobbyId {
            val outcome = createLobby(SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"), Pseudonym("Alice"))
            return outcome.value.id
        }

        suspend fun startGame(lobbyId: LobbyId) {
            val out = startGameUseCase(lobbyId, SessionId("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"))
            check(out is UseCaseOutcome.Success) { "startGame failed: $out" }
        }

        suspend fun typeLetter(
            lobbyId: LobbyId,
            sessionId: SessionId,
            position: Position,
            letter: Letter,
        ) {
            val out = updateCellUseCase(lobbyId, sessionId, position, letter)
            check(out is UseCaseOutcome.Success) { "updateCell failed: $out" }
        }
    }

    private fun runWith(
        reconnectGrace: Duration = Duration.ZERO,
        block: suspend (Harness) -> Unit,
    ) = testApplication {
        val clock: Clock = SystemClock
        val repo = InMemoryLobbyRepository()
        val puzzle = SamplePuzzles.tiny()
        val provider =
            object : PuzzleProvider {
                override suspend fun fetch(
                    width: Int,
                    height: Int,
                ): GamePuzzle = puzzle
            }
        val createLobby = CreateLobbyUseCase(repo, clock)
        val startGameUseCase = StartGameUseCase(repo, provider, clock)
        val updateCellUseCase = UpdateCellUseCase(repo, clock)
        val useCases =
            LobbyUseCases(
                createLobby = createLobby,
                joinLobby = JoinLobbyUseCase(repo, clock),
                renameSelf = RenameSelfUseCase(repo, clock),
                setGridConfig = SetGridConfigUseCase(repo, clock),
                startGame = startGameUseCase,
                updateCell = updateCellUseCase,
                leaveLobby = LeaveLobbyUseCase(repo, clock),
            )
        val sessionManager = SessionManager()
        // Background scope for the reconnect-grace timer. SupervisorJob so a
        // failure in one grace coroutine does not cancel the others. Cancelled
        // at the end of the test via the harness's `tearDown` to avoid leaking
        // coroutines into the next test.
        val backgroundJob = SupervisorJob()
        val backgroundScope = CoroutineScope(backgroundJob + Dispatchers.Default)
        application {
            install(ServerWebSockets)
            routing {
                lobbyWebSocketRoute(
                    sessionManager,
                    useCases,
                    repo,
                    backgroundScope = backgroundScope,
                    reconnectGrace = reconnectGrace,
                )
            }
        }
        val client = createClient { install(WebSockets) }
        try {
            block(Harness(client, createLobby, startGameUseCase, updateCellUseCase, sessionManager, repo))
        } finally {
            backgroundJob.cancel()
        }
    }

    private object SamplePuzzles {
        fun tiny(): GamePuzzle =
            GamePuzzle(
                id = UUID.fromString("0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c"),
                title = "Petite grille",
                language = "fr",
                width = 5,
                height = 5,
                cells = emptyList(),
                clues = emptyList(),
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
    }
}

private suspend fun DefaultClientWebSocketSession.receiveText(): String =
    withTimeout(5_000) {
        var text: String? = null
        while (text == null) {
            val frame = incoming.receive()
            if (frame is Frame.Text) text = frame.readText()
        }
        text
    }

private suspend fun DefaultClientWebSocketSession.sendText(text: String) {
    send(text)
}
