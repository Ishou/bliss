package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.StoredPuzzle
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import com.bliss.grid.infrastructure.persistence.InMemoryHintUsageRepository
import com.bliss.grid.infrastructure.persistence.InMemoryPuzzleRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression test guarding /v1/puzzles/daily against the blocking-JDBC-on-event-loop
 * bug that made the endpoint slow in production despite the
 * `LoadOrGeneratePuzzleUseCase` cache-hit short-circuit. The PostgresPuzzleRepository
 * `get(...)` adapter performs synchronous JDBC I/O, so the route must dispatch the
 * call onto Dispatchers.IO rather than the Netty call dispatcher (which is backed by
 * the event-loop group and starves under any blocking I/O).
 *
 * `testApplication` cannot detect this because its in-process engine uses the same
 * kotlinx-coroutines worker pool regardless of `withContext(Dispatchers.IO)`. This
 * test stands up a real Netty server on an ephemeral port; Netty names its event-loop
 * threads `eventLoopGroupProxy-*` / `nioEventLoopGroup-*`, distinct from
 * `DefaultDispatcher-worker-*`. With the route's `withContext(Dispatchers.IO)` in
 * place, the spy repo observes a `DefaultDispatcher-worker-*` thread. Without it,
 * the spy observes an `eventLoopGroupProxy-*` thread (the bug).
 */
class PuzzleRouteDispatcherTest {
    @Test
    fun `daily endpoint dispatches the blocking repository read off the Netty event loop`() {
        val repoThread = AtomicReference<String>()
        val delegate = InMemoryPuzzleRepository()
        val recordingRepo =
            object : PuzzleRepository {
                override fun get(puzzleId: UUID): StoredPuzzle? {
                    repoThread.compareAndSet(null, Thread.currentThread().name)
                    return delegate.get(puzzleId)
                }

                override fun getOrCompute(
                    puzzleId: UUID,
                    factory: () -> StoredPuzzle?,
                ): StoredPuzzle? {
                    repoThread.compareAndSet(null, Thread.currentThread().name)
                    return delegate.getOrCompute(puzzleId, factory)
                }
            }

        val port = ServerSocket(0).use { it.localPort }
        val server =
            embeddedServer(Netty, port = port, host = "127.0.0.1") {
                install(ContentNegotiation) { json() }
                val gen = GeneratePuzzleUseCase(EmptyWordRepository, defaultPuzzleConstraints())
                val hintUsageRepo = InMemoryHintUsageRepository()
                routing {
                    puzzles(
                        loadOrGenerate = LoadOrGeneratePuzzleUseCase(recordingRepo, gen),
                        revealCellHint = RevealCellHintUseCase(recordingRepo, hintUsageRepo),
                        validatePuzzle = ValidatePuzzleUseCase(recordingRepo),
                    )
                }
            }
        server.start(wait = false)
        try {
            HttpClient(CIO).use { client ->
                kotlinx.coroutines.runBlocking {
                    // Generation will fail and yield 422 — fine. We only need the
                    // route to have invoked the spy `get(...)` so we can inspect
                    // the executing thread.
                    client.get("http://127.0.0.1:$port/v1/puzzles/daily?date=2026-05-09")
                }
            }

            val observed = repoThread.get()
            assertThat(observed).isNotNull()
            // The bug surfaces as Netty's event-loop thread executing the JDBC
            // call. With the fix, execution moves to a kotlinx-coroutines IO
            // worker.
            assertThat(observed!!.contains("eventLoop") || observed.contains("nioEventLoop")).isFalse()
            assertThat(observed).contains("DefaultDispatcher-worker")
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }

    private object EmptyWordRepository : WordRepository {
        override fun findByLength(length: Int): List<Word> = emptyList()

        override fun findByLengthAndPattern(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> = emptyList()

        override fun containsLemma(text: String): Boolean = false
    }
}
