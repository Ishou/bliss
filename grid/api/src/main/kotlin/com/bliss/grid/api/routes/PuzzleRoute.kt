package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * `GET /v1/puzzles/{puzzleId}` — generates a fresh 10x10 mots-fléchés puzzle
 * on every request.
 *
 * v1 is stateless: the path id is informational and echoes back into
 * `Puzzle.id`. Persistence (so a client can re-fetch the same puzzle by id)
 * lands in a later workstream.
 *
 * Failure modes:
 * - puzzleId is not a UUID → 400 ProblemDetails
 * - generator can't satisfy the constraints → 503 ProblemDetails
 */
fun Route.puzzles(
    wordRepository: WordRepository,
    mapper: GridToPuzzleMapper = GridToPuzzleMapper(),
    constraints: GridConstraints = defaultConstraints(),
) {
    val log = LoggerFactory.getLogger("com.bliss.grid.api.routes.PuzzleRoute")
    val generator = GridGenerator(wordRepository)

    get("/v1/puzzles/{puzzleId}") {
        val rawId = call.parameters["puzzleId"].orEmpty()
        val puzzleId = parseUuid(rawId)
        if (puzzleId == null) {
            call.respondProblem(
                status = HttpStatusCode.BadRequest,
                title = "Invalid puzzle id",
                type = "https://bliss.example/errors/invalid-puzzle-id",
                detail = "Path parameter puzzleId must be a UUID, was '$rawId'.",
            )
            return@get
        }

        val grid = generateWithRetry(generator, constraints, log)
        if (grid == null) {
            log.warn(
                "puzzle_generation_failed puzzle_id={} width={} height={} target_density={} max_attempts={} retries={}",
                puzzleId,
                constraints.width,
                constraints.height,
                constraints.targetDensity,
                constraints.maxAttempts,
                MAX_OUTER_RETRIES,
            )
            call.respondProblem(
                status = HttpStatusCode.ServiceUnavailable,
                title = "Puzzle generation failed",
                type = "https://bliss.example/errors/puzzle-generation-failed",
                detail = "The generator could not satisfy the requested constraints.",
            )
            return@get
        }

        call.respond(mapper.toApi(grid = grid, puzzleId = puzzleId, createdAt = Instant.now()))
    }
}

/**
 * Tuned for a ~20ms median on a 10×10 grid backed by the bundled fr.json.
 *
 * Density of 0.5 (up from 0.4) and a 20_000-attempt budget (up from
 * 10_000) keep the bottom rows from filling with blocks; the previous
 * defaults let the backtracker quit early and leave the bottom 4 rows
 * >90% blocks against a ~120-word curated list.
 */
internal fun defaultConstraints(): GridConstraints =
    GridConstraints(
        width = PUZZLE_WIDTH,
        height = PUZZLE_HEIGHT,
        minWordLength = 3,
        targetDensity = 0.5,
        maxAttempts = 20_000,
    )

internal const val PUZZLE_WIDTH: Int = 10
internal const val PUZZLE_HEIGHT: Int = 10
internal const val MAX_OUTER_RETRIES: Int = 3

/**
 * Calls the generator up to [MAX_OUTER_RETRIES] times. Each call uses a
 * fresh per-attempt [Random] (so refreshes vary). The result is rejected
 * if any half of the grid (top vs bottom, or left vs right) holds fewer
 * letter cells than [MIN_HALF_LETTER_RATIO] of its area — that's the
 * shape the sparseness regression takes on the wire (bottom four rows
 * >90% blocks).
 *
 * Returns the first acceptable grid, or `null` if every retry produces
 * a grid that fails the post-condition. The route then surfaces 503.
 */
private fun generateWithRetry(
    generator: GridGenerator,
    constraints: GridConstraints,
    log: org.slf4j.Logger,
): Grid? {
    repeat(MAX_OUTER_RETRIES) { attempt ->
        val random = Random(System.nanoTime() + attempt)
        val grid = generator.generate(constraints, random) ?: return@repeat
        if (hasSparseHalf(grid)) {
            log.warn(
                "puzzle_generation_sparse_retry attempt={} width={} height={}",
                attempt + 1,
                grid.width,
                grid.height,
            )
            return@repeat
        }
        return grid
    }
    return null
}

/**
 * Each half (top, bottom, left, right) must hold at least
 * [MIN_HALF_LETTER_RATIO] of its area in [LetterCell]s. Catches the
 * "blocks dumped across the bottom half" failure mode without rejecting
 * normal grids — empirically every sample at density 0.5 against the
 * bundled 122-word fr.json clears 30%.
 */
private fun hasSparseHalf(grid: Grid): Boolean {
    val midRow = grid.height / 2
    val midCol = grid.width / 2
    val halves =
        listOf(
            HalfArea(0 until midRow, 0 until grid.width),
            HalfArea(midRow until grid.height, 0 until grid.width),
            HalfArea(0 until grid.height, 0 until midCol),
            HalfArea(0 until grid.height, midCol until grid.width),
        )
    return halves.any { half ->
        val total = (half.rows.last - half.rows.first + 1) * (half.cols.last - half.cols.first + 1)
        if (total == 0) return@any false
        val letters = countLetters(grid, half)
        letters.toDouble() / total.toDouble() < MIN_HALF_LETTER_RATIO
    }
}

private fun countLetters(
    grid: Grid,
    half: HalfArea,
): Int {
    var count = 0
    for (r in half.rows) {
        for (c in half.cols) {
            if (grid.cells[Position(Row(r), Column(c))] is LetterCell) count++
        }
    }
    return count
}

private data class HalfArea(
    val rows: IntRange,
    val cols: IntRange,
)

private const val MIN_HALF_LETTER_RATIO: Double = 0.3

private fun parseUuid(raw: String): UUID? =
    try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    type: String,
    detail: String?,
) {
    val problem =
        ProblemDetails(
            type = type,
            title = title,
            status = status.value,
            detail = detail,
            instance = request.local.uri,
        )
    respondText(
        text = Json.encodeToString(ProblemDetails.serializer(), problem),
        contentType = ContentType.parse("application/problem+json"),
        status = status,
    )
}
