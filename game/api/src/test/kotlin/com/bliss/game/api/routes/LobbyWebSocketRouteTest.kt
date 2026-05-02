package com.bliss.game.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import com.bliss.game.api.LobbyUseCases
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
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
                    // Drop without leaveLobby — disconnect alone triggers the playerLeft broadcast.
                }
                val text = withTimeout(5_000) { ownerSawLeft.await() }
                assertThat(text).contains("\"type\":\"playerLeft\"")
                assertThat(text).contains(sessionB)
                ownerJob.cancel()
            }
        }

    // ---------- harness ----------

    private class Harness(
        val client: HttpClient,
        private val createLobby: CreateLobbyUseCase,
        private val startGameUseCase: StartGameUseCase,
        private val updateCellUseCase: UpdateCellUseCase,
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

    private fun runWith(block: suspend (Harness) -> Unit) =
        testApplication {
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
            application {
                install(ServerWebSockets)
                routing {
                    lobbyWebSocketRoute(sessionManager, useCases, repo)
                }
            }
            val client = createClient { install(WebSockets) }
            block(Harness(client, createLobby, startGameUseCase, updateCellUseCase))
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
