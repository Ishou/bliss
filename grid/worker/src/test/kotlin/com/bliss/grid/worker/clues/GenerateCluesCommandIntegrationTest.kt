package com.bliss.grid.worker.clues

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.bliss.grid.domain.clue.ClueClient
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder.newRequestPattern
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * End-to-end test for `generate-clues` against a real Postgres + a WireMock-stubbed
 * Anthropic `messages` endpoint. Exercises the SDK + HTTP path the unit test bypasses.
 */
class GenerateCluesCommandIntegrationTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var wireMock: WireMockServer

    @BeforeEach
    fun setUp() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        System.setProperty(
            "DATABASE_URL",
            "jdbc:postgresql://${pg.host}:${pg.firstMappedPort}/${pg.databaseName}" +
                "?user=${pg.username}&password=${pg.password}",
        )
        Database.start()
        wireMock = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }
    }

    @AfterEach
    fun tearDown() {
        if (::wireMock.isInitialized) wireMock.stop()
        Database.stopForTesting()
        System.clearProperty("DATABASE_URL")
        if (::pg.isInitialized) pg.stop()
    }

    @Test
    fun `generates clues end-to-end against stubbed Anthropic, enforces length cap, writes`() {
        // "maison" is chosen as the always-too-long word because it does NOT appear in
        // the few-shot examples embedded in the system prompt. Words like "oiseau" appear
        // in every request body via the system prompt, so containing("oiseau") would match
        // every WireMock call, not just the "oiseau" user-message call.
        seedWords(listOf("chat", "arbre", "pluie", "fer", "maison"))

        // Default stub: every call returns a short clue. WireMock evaluates more-specific
        // stubs (with body matchers) before this catch-all.
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/messages"))
                .willReturn(messagesResponse("Court")),
        )

        // "maison" always too long → row stays NULL after MAX_ATTEMPTS exhausts.
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/messages"))
                .withRequestBody(containing("maison"))
                .willReturn(messagesResponse("Y".repeat(MAX_CLUE_CHARS + 5))),
        )

        val factory: () -> ClueClient = { stubbedClient(wireMock.baseUrl()) }
        GenerateCluesCommand(factory).parse(arrayOf("--language", "fr", "--concurrency", "1"))

        val clueByWord = allClues()
        listOf("chat", "arbre", "pluie", "fer").forEach { word ->
            val clue = clueByWord[word]
            check(clue != null) { "expected '$word' to be cluefied, got NULL" }
            check(clue.length <= MAX_CLUE_CHARS) { "clue for '$word' exceeds cap: '$clue'" }
        }
        assertThat(clueByWord["maison"]).isEqualTo(null)

        val totalCalls =
            wireMock.findAll(newRequestPattern(RequestMethod.POST, urlPathEqualTo("/v1/messages"))).size
        check(totalCalls >= 5) { "expected at least 5 Anthropic calls (one per row); got $totalCalls" }
    }

    @Test
    fun `--dry-run makes API calls but writes no UPDATEs`() {
        seedWords(listOf("chat", "arbre"))
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/messages"))
                .willReturn(messagesResponse("Court")),
        )

        val factory: () -> ClueClient = { stubbedClient(wireMock.baseUrl()) }
        GenerateCluesCommand(factory).parse(arrayOf("--dry-run", "--concurrency", "1"))

        // No row got an UPDATE.
        val populated = allClues().values.count { it != null }
        check(populated == 0) { "dry-run wrote $populated rows; expected 0" }

        val totalCalls =
            wireMock.findAll(newRequestPattern(RequestMethod.POST, urlPathEqualTo("/v1/messages"))).size
        check(totalCalls >= 2) { "dry-run should still call Anthropic; got $totalCalls" }
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun seedWords(words: List<String>) {
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    // lemma = word so the default lemmas-only selector picks them up.
                    "INSERT INTO words (word, language, lemma, source, source_license) VALUES (?, 'fr', ?, 'test', 'test')",
                ).use { stmt ->
                    for (w in words) {
                        stmt.setString(1, w)
                        stmt.setString(2, w)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
        }
    }

    private fun ds(): DataSource = requireNotNull(Database.dataSource())

    private fun allClues(): Map<String, String?> =
        ds().connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT word, clue FROM words ORDER BY word").use { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString(1), rs.getString(2))
                    }
                }
            }
        }

    private fun stubbedClient(baseUrl: String): ClueClient =
        AnthropicClueClient(
            AnthropicOkHttpClient
                .builder()
                .apiKey("test-key")
                .baseUrl(baseUrl)
                .build(),
        )

    private fun messagesResponse(text: String) =
        text.replace("\\", "\\\\").replace("\"", "\\\"").let { escaped ->
            aResponse()
                .withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody(
                    """
                    {
                      "id": "msg_test",
                      "type": "message",
                      "role": "assistant",
                      "model": "claude-haiku-4-5-20251001",
                      "content": [{"type": "text", "text": "$escaped"}],
                      "stop_reason": "end_turn",
                      "stop_sequence": null,
                      "usage": {
                        "input_tokens": 100,
                        "output_tokens": 8,
                        "cache_creation_input_tokens": 0,
                        "cache_read_input_tokens": 0
                      }
                    }
                    """.trimIndent(),
                )
        }
}
