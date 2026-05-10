package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.ClueCooldownRepository
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Word
import com.bliss.grid.infrastructure.persistence.InMemoryClueCooldownRepository
import com.bliss.grid.infrastructure.persistence.InMemoryHintUsageRepository
import com.bliss.grid.infrastructure.persistence.InMemoryPuzzleRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.UUID

/** Wire-path tests for `X-Session-Id` cooldown plumbing on the puzzle GET routes — see ADR-0031. */
class PuzzleRouteCooldownTest {
    private val validId = "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b"
    private val daily = ClueCooldownRepository.DAILY_SCOPE_ID
    private val playerSession = "019186c5-d702-7b3a-a3d4-b40dc2d6d871"

    private fun ApplicationBuilder.mountPuzzlesRoute(cooldownRepository: ClueCooldownRepository?) {
        application {
            install(ContentNegotiation) { json() }
            val gen =
                GeneratePuzzleUseCase(
                    wordRepository = SmallWordRepository,
                    defaults = defaultPuzzleConstraints(),
                )
            val puzzleRepo = InMemoryPuzzleRepository()
            val hintUsageRepo = InMemoryHintUsageRepository()
            routing {
                puzzles(
                    loadOrGenerate =
                        LoadOrGeneratePuzzleUseCase(
                            puzzleRepository = puzzleRepo,
                            generatePuzzle = gen,
                            cooldownRepository = cooldownRepository,
                        ),
                    revealCellHint = RevealCellHintUseCase(puzzleRepo, hintUsageRepo),
                    validatePuzzle = ValidatePuzzleUseCase(puzzleRepo),
                )
            }
        }
    }

    @Test
    fun `GET puzzle without X-Session-Id does not record a cooldown generation`() =
        testApplication {
            val cooldown = InMemoryClueCooldownRepository()
            mountPuzzlesRoute(cooldown)

            val response = client.get("/v1/puzzles/$validId")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(cooldown.snapshot(UUID.fromString(playerSession)).currentSeq).isEqualTo(0L)
        }

    @Test
    fun `GET puzzle with valid X-Session-Id records the generation under that bucket`() =
        testApplication {
            val cooldown = InMemoryClueCooldownRepository()
            mountPuzzlesRoute(cooldown)

            val response =
                client.get("/v1/puzzles/$validId") {
                    header("X-Session-Id", playerSession)
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(cooldown.snapshot(UUID.fromString(playerSession)).currentSeq).isEqualTo(1L)
        }

    @Test
    fun `GET puzzle with malformed X-Session-Id responds 400 invalid-session-id`() =
        testApplication {
            mountPuzzlesRoute(InMemoryClueCooldownRepository())

            val response =
                client.get("/v1/puzzles/$validId") {
                    header("X-Session-Id", "not-a-uuid")
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("invalid-session-id")
        }

    @Test
    fun `GET puzzle with X-Session-Id equal to DAILY_SCOPE_ID is rejected`() =
        testApplication {
            val cooldown = InMemoryClueCooldownRepository()
            mountPuzzlesRoute(cooldown)

            val response =
                client.get("/v1/puzzles/$validId") {
                    header("X-Session-Id", daily.toString())
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-session-id")
            // Daily bucket was not touched — the route refused before reaching the use case.
            assertThat(cooldown.snapshot(daily).currentSeq).isEqualTo(0L)
        }

    @Test
    fun `GET puzzles daily records the generation under DAILY_SCOPE_ID`() =
        testApplication {
            val cooldown = InMemoryClueCooldownRepository()
            mountPuzzlesRoute(cooldown)

            val response = client.get("/v1/puzzles/daily?date=2026-05-10")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(cooldown.snapshot(daily).currentSeq).isEqualTo(1L)
        }

    @Test
    fun `GET puzzles daily ignores X-Session-Id header`() =
        testApplication {
            val cooldown = InMemoryClueCooldownRepository()
            mountPuzzlesRoute(cooldown)

            val response =
                client.get("/v1/puzzles/daily?date=2026-05-11") {
                    header("X-Session-Id", playerSession)
                }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            // Daily bucket bumped, player session bucket untouched.
            assertThat(cooldown.snapshot(daily).currentSeq).isEqualTo(1L)
            assertThat(cooldown.snapshot(UUID.fromString(playerSession)).currentSeq).isEqualTo(0L)
        }

    @Test
    fun `null cooldown repository keeps GET puzzle behavior unchanged`() =
        testApplication {
            mountPuzzlesRoute(cooldownRepository = null)

            val noHeader = client.get("/v1/puzzles/$validId")
            assertThat(noHeader.status).isEqualTo(HttpStatusCode.OK)

            val withHeader =
                client.get("/v1/puzzles/$validId") {
                    header("X-Session-Id", playerSession)
                }
            assertThat(withHeader.status).isEqualTo(HttpStatusCode.OK)
        }

    private object SmallWordRepository : WordRepository {
        override fun findByLength(length: Int): List<Word> = candidates(length, emptyMap())

        override fun findByLengthAndPattern(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> = candidates(length, pattern)

        override fun containsLemma(text: String): Boolean = true

        private fun candidates(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> {
            val unconstrained = (0 until length).filter { it !in pattern }
            return (0..25)
                .map { n ->
                    val chars = CharArray(length) { i -> pattern[i] ?: 'A' }
                    unconstrained.forEachIndexed { idx, pos -> chars[pos] = 'A' + (n + idx * 7) % 26 }
                    Word(String(chars), "test definition")
                }.distinctBy { it.text }
        }
    }
}

private typealias ApplicationBuilder = io.ktor.server.testing.ApplicationTestBuilder
