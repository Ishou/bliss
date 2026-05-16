package com.bliss.grid.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import com.bliss.grid.api.dto.ListDailyPuzzlesResponseDto
import com.bliss.grid.application.puzzle.DailyPuzzleSelector
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.ListDailyPuzzlesUseCase
import com.bliss.grid.application.puzzle.LoadOrGeneratePuzzleUseCase
import com.bliss.grid.application.puzzle.RevealCellHintUseCase
import com.bliss.grid.application.puzzle.StoredPuzzle
import com.bliss.grid.application.puzzle.ValidatePuzzleUseCase
import com.bliss.grid.application.puzzle.defaultPuzzleConstraints
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import com.bliss.grid.infrastructure.persistence.InMemoryHintUsageRepository
import com.bliss.grid.infrastructure.persistence.InMemoryPuzzleRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PuzzleRouteListDailiesTest {
    private val today = LocalDate.parse("2026-05-16")
    private val fixedClock: Clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `returns items DESC by date with hasMore false on small ranges`() =
        testApplication {
            application { listDailyPuzzlesModule(seed = LocalDate.parse("2026-05-12")..today) }

            val response = client.get("/v1/puzzles/daily/list")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/json")
            val body: ListDailyPuzzlesResponseDto = json.decodeFromString(response.bodyAsText())
            // 5 days inclusive: 12, 13, 14, 15, 16. DESC by date.
            assertThat(body.items.map { it.date }).containsExactly(
                "2026-05-16",
                "2026-05-15",
                "2026-05-14",
                "2026-05-13",
                "2026-05-12",
            )
            assertThat(body.hasMore).isFalse()
        }

    @Test
    fun `respects from and to query parameters`() =
        testApplication {
            application { listDailyPuzzlesModule(seed = LocalDate.parse("2026-05-07")..today) }

            val response = client.get("/v1/puzzles/daily/list?from=2026-05-10&to=2026-05-12")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body: ListDailyPuzzlesResponseDto = json.decodeFromString(response.bodyAsText())
            assertThat(body.items).hasSize(3)
            assertThat(body.items.map { it.date }).containsExactly("2026-05-12", "2026-05-11", "2026-05-10")
            assertThat(body.hasMore).isFalse()
        }

    @Test
    fun `returns 400 invalid-puzzle-date for malformed from param`() =
        testApplication {
            application { listDailyPuzzlesModule(seed = LocalDate.parse("2026-05-12")..today) }

            val response = client.get("/v1/puzzles/daily/list?from=not-a-date")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.headers["Content-Type"]!!).startsWith("application/problem+json")
            assertThat(response.bodyAsText()).contains("invalid-puzzle-date")
        }

    @Test
    fun `returns 400 invalid-puzzle-date for malformed to param`() =
        testApplication {
            application { listDailyPuzzlesModule(seed = LocalDate.parse("2026-05-12")..today) }

            val response = client.get("/v1/puzzles/daily/list?to=garbage")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("invalid-puzzle-date")
        }

    @Test
    fun `returns empty items when range entirely before launch anchor`() =
        testApplication {
            application { listDailyPuzzlesModule(seed = LocalDate.parse("2026-05-12")..today) }

            val response = client.get("/v1/puzzles/daily/list?from=2024-01-01&to=2024-12-31")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body: ListDailyPuzzlesResponseDto = json.decodeFromString(response.bodyAsText())
            assertThat(body.items).isEmpty()
            assertThat(body.hasMore).isFalse()
        }

    @Test
    fun `returns hasMore true when range exceeds maxItems`() =
        testApplication {
            // 7 seeded days; ask the use case to cap at 3.
            application { listDailyPuzzlesModule(seed = LocalDate.parse("2026-05-10")..today, maxItems = 3) }

            val response = client.get("/v1/puzzles/daily/list?from=2026-05-10&to=2026-05-16")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body: ListDailyPuzzlesResponseDto = json.decodeFromString(response.bodyAsText())
            assertThat(body.items).hasSize(3)
            assertThat(body.hasMore).isTrue()
            // Newest three.
            assertThat(body.items.map { it.date }).containsExactly("2026-05-16", "2026-05-15", "2026-05-14")
        }

    @Test
    fun `items carry totalLetterCells from the persisted summary`() =
        testApplication {
            application { listDailyPuzzlesModule(seed = LocalDate.parse("2026-05-15")..today) }

            val body: ListDailyPuzzlesResponseDto =
                json.decodeFromString(client.get("/v1/puzzles/daily/list").bodyAsText())

            // Stub puzzle has a single 5-letter word; totalLetterCells == 5.
            assertThat(body.items.all { it.totalLetterCells == 5 }).isTrue()
        }

    private fun Application.listDailyPuzzlesModule(
        seed: ClosedRange<LocalDate>,
        maxItems: Int = ListDailyPuzzlesUseCase.DEFAULT_MAX_ITEMS,
    ) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
        val puzzleRepo = InMemoryPuzzleRepository()
        val hintRepo = InMemoryHintUsageRepository()
        val selector = DailyPuzzleSelector()

        // Walk the date range; persist a stub StoredPuzzle for each.
        var d = seed.start
        while (!d.isAfter(seed.endInclusive)) {
            val id = selector.puzzleIdForDate(d)
            puzzleRepo.getOrCompute(id) { stubStoredPuzzle() }
            d = d.plusDays(1)
        }

        val gen = GeneratePuzzleUseCase(EmptyWordRepository, defaultPuzzleConstraints())
        routing {
            puzzles(
                loadOrGenerate = LoadOrGeneratePuzzleUseCase(puzzleRepo, gen),
                revealCellHint = RevealCellHintUseCase(puzzleRepo, hintRepo),
                validatePuzzle = ValidatePuzzleUseCase(puzzleRepo),
                listDailyPuzzles =
                    ListDailyPuzzlesUseCase(
                        puzzleRepository = puzzleRepo,
                        dailyPuzzleSelector = selector,
                        maxItems = maxItems,
                    ),
                puzzleRepository = puzzleRepo,
                dailyPuzzleSelector = selector,
                clock = fixedClock,
            )
        }
    }

    private fun stubStoredPuzzle(): StoredPuzzle {
        val word = Word(text = "ABCDE", definition = "test")
        val placement =
            WordPlacement(
                word = word,
                cluePosition = Position(Row(0), Column(0)),
                direction = Direction.DOWN_RIGHT,
                chosenClue = word.clues.first(),
            )
        return StoredPuzzle(
            grid = Grid.fromPlacements(width = 6, height = 6, placements = listOf(placement)),
            title = "t",
            language = "fr",
            hintsAllowed = 3,
            createdAt = Instant.parse("2026-05-13T00:00:00Z"),
        )
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
