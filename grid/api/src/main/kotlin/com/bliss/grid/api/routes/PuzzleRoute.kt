package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.ProblemDetails
import com.bliss.grid.api.mapper.GridToPuzzleMapper
import com.bliss.grid.application.puzzle.GeneratePuzzleUseCase
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

/**
 * `GET /v1/puzzles/{puzzleId}` — generates a fresh mots-fléchés puzzle on every
 * request. v1 is stateless: the path id is informational and echoes back into
 * `Puzzle.id`. Persistence lands in a later workstream.
 *
 * Failure modes:
 * - puzzleId is not a UUID → 400 ProblemDetails
 * - generator can't satisfy the constraints → 422 ProblemDetails
 */
fun Route.puzzles(
    generatePuzzle: GeneratePuzzleUseCase,
    mapper: GridToPuzzleMapper = GridToPuzzleMapper(),
) {
    val log = LoggerFactory.getLogger("com.bliss.grid.api.routes.PuzzleRoute")

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

        val grid = generatePuzzle.execute()
        if (grid == null) {
            log.warn("puzzle_generation_failed puzzle_id={}", puzzleId)
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
