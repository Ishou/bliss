package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.domain.generation.GridConstraints
import com.bliss.grid.domain.generation.GridGenerator
import com.bliss.grid.domain.generation.WordRepository
import com.bliss.grid.domain.model.Grid
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
                status = HttpStatusCode.UnprocessableEntity,
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
 * Tuned for a ~20ms median on a 10×10 grid backed by the bundled
 * `words-fr.csv` (ADR-0013 §8).
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
        minWordLength = 2,
        targetDensity = 0.5,
        maxAttempts = 20_000,
    )

internal const val PUZZLE_WIDTH: Int = 8
internal const val PUZZLE_HEIGHT: Int = 8
internal const val MAX_OUTER_RETRIES: Int = 5

/**
 * Calls the generator up to [MAX_OUTER_RETRIES] times. Each call uses a
 * fresh per-attempt [Random] (so refreshes vary).
 *
 * The generator itself enforces density and interlocking constraints —
 * it only returns a grid when both are satisfied. A `null` return means
 * the backtracker exhausted its attempt budget.
 *
 * Returns the first generated grid, or `null` if every retry fails.
 * The route then surfaces 503.
 */
private fun generateWithRetry(
    generator: GridGenerator,
    constraints: GridConstraints,
    log: org.slf4j.Logger,
): Grid? {
    repeat(MAX_OUTER_RETRIES) { attempt ->
        val random = Random(System.nanoTime() + attempt)
        val grid = generator.generate(constraints, random)
        if (grid != null) return grid
        log.warn(
            "puzzle_generation_retry attempt={} width={} height={}",
            attempt + 1,
            constraints.width,
            constraints.height,
        )
    }
    return null
}

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
