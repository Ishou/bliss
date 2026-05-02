package com.bliss.game.infrastructure

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.bliss.game.domain.BlockCell
import com.bliss.game.domain.DefinitionCell
import com.bliss.game.domain.GameArrow
import com.bliss.game.domain.Letter
import com.bliss.game.domain.LetterCell
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class HttpPuzzleProviderTest {
    private val baseUrl = "http://grid.test"
    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun jsonClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler))

    private fun providerOf(handler: MockRequestHandler): HttpPuzzleProvider = HttpPuzzleProvider(jsonClient(handler), baseUrl)

    @Test
    fun `fetch happy path maps the wire response to a GamePuzzle`() =
        runTest {
            val puzzle = providerOf { respond(canonical5x5Json, HttpStatusCode.OK, jsonHeaders) }.fetch(5, 5)

            assertThat(puzzle.title).isEqualTo("Petite grille")
            assertThat(puzzle.width).isEqualTo(5)
            assertThat(puzzle.height).isEqualTo(5)
            assertThat(puzzle.cells).hasSize(4)
            val letterCells = puzzle.cells.filterIsInstance<LetterCell>()
            assertThat(letterCells.map { it.answer }).containsExactly(Letter('P'), Letter('A'))
            val defCell = puzzle.cells.filterIsInstance<DefinitionCell>().single()
            assertThat(defCell.clues.single().text).isEqualTo("verticale")
            assertThat(defCell.clues.single().arrow).isEqualTo(GameArrow.DOWN)
            assertThat(puzzle.cells.filterIsInstance<BlockCell>()).hasSize(1)
            assertThat(puzzle.clues).hasSize(1)
        }

    @Test
    fun `fetch preserves stacked DefinitionCells (mots-fleches corner) into one cell with both clues`() =
        runTest {
            val puzzle =
                providerOf { respond(stacked5x5Json, HttpStatusCode.OK, jsonHeaders) }.fetch(5, 5)

            val defCells = puzzle.cells.filterIsInstance<DefinitionCell>()
            assertThat(defCells).hasSize(1)
            val cell = defCells.single()
            assertThat(cell.clues.map { it.text }).containsExactly("horizontal", "vertical")
            assertThat(cell.clues.map { it.arrow }).containsExactly(GameArrow.RIGHT, GameArrow.DOWN)
        }

    @Test
    fun `fetch sends width and height as query params`() =
        runTest {
            var capturedUrl: String? = null
            providerOf { request ->
                capturedUrl = request.url.toString()
                respond(canonical5x5Json, HttpStatusCode.OK, jsonHeaders)
            }.fetch(width = 7, height = 7)

            val url = checkNotNull(capturedUrl)
            assertThat(url).contains("width=7")
            assertThat(url).contains("height=7")
            assertThat(url).contains("/v1/puzzles/")
        }

    @Test
    fun `non 2xx response raises UpstreamError carrying status and body`() =
        runTest {
            val problem = """{"type":"https://bliss.example/errors/puzzle-generation-failed","status":422}"""
            val ex =
                assertThrows<PuzzleProviderException.UpstreamError> {
                    providerOf { respond(problem, HttpStatusCode.UnprocessableEntity, jsonHeaders) }.fetch(5, 5)
                }
            assertThat(ex.status).isEqualTo(422)
            assertThat(ex.body).contains("puzzle-generation-failed")
        }

    @Test
    fun `network failure raises UpstreamUnavailable`() =
        runTest {
            val ex =
                assertThrows<PuzzleProviderException.UpstreamUnavailable> {
                    providerOf { throw IOException("connection refused") }.fetch(5, 5)
                }
            assertThat(ex.cause).isNotNull().isInstanceOf(IOException::class)
        }

    @Test
    fun `malformed JSON raises UpstreamMalformed`() =
        runTest {
            assertThrows<PuzzleProviderException.UpstreamMalformed> {
                providerOf { respond("not-json-at-all", HttpStatusCode.OK, jsonHeaders) }.fetch(5, 5)
            }
        }

    // Domain init blocks (e.g. blank title) must not leak — the adapter repackages them.
    @Test
    fun `payload that violates a domain invariant also raises UpstreamMalformed`() =
        runTest {
            val brokenJson = canonical5x5Json.replaceFirst("\"Petite grille\"", "\"\"")
            assertThrows<PuzzleProviderException.UpstreamMalformed> {
                providerOf { respond(brokenJson, HttpStatusCode.OK, jsonHeaders) }.fetch(5, 5)
            }
        }

    // 1 block + 2 letter + 1 definition + 1 clue. Single-clue definition cell.
    private val canonical5x5Json =
        """
        {
          "id": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c",
          "title": "Petite grille", "language": "fr", "width": 5, "height": 5,
          "cells": [
            { "kind": "block", "position": { "row": 0, "column": 0 } },
            { "kind": "letter", "position": { "row": 0, "column": 3 }, "letter": "P" },
            { "kind": "letter", "position": { "row": 0, "column": 4 }, "letter": "A" },
            { "kind": "definition", "position": { "row": 1, "column": 0 },
              "clueId": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d", "text": "verticale", "arrow": "down" }
          ],
          "clues": [
            { "id": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d", "direction": "down",
              "start": { "row": 2, "column": 0 }, "length": 3, "text": "verticale" }
          ],
          "createdAt": "2026-04-30T06:46:14.109Z"
        }
        """.trimIndent()

    // Stacked DefinitionCell pair at (0,0): one across clue, one down clue at the same
    // position (the mots-fleches corner-cell idiom). The mapper groups them into one
    // domain DefinitionCell with two clues in wire order.
    private val stacked5x5Json =
        """
        {
          "id": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c",
          "title": "Coin", "language": "fr", "width": 5, "height": 5,
          "cells": [
            { "kind": "definition", "position": { "row": 0, "column": 0 },
              "clueId": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d", "text": "horizontal", "arrow": "right" },
            { "kind": "definition", "position": { "row": 0, "column": 0 },
              "clueId": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6e", "text": "vertical", "arrow": "down" }
          ],
          "clues": [
            { "id": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d", "direction": "across",
              "start": { "row": 0, "column": 1 }, "length": 4, "text": "horizontal" },
            { "id": "0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6e", "direction": "down",
              "start": { "row": 1, "column": 0 }, "length": 4, "text": "vertical" }
          ],
          "createdAt": "2026-04-30T06:46:14.109Z"
        }
        """.trimIndent()
}
