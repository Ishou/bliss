package com.bliss.grid.worker.clues

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNull
import com.bliss.grid.application.clue.GenerateCluesUseCase
import com.bliss.grid.domain.clue.ClueClient
import com.bliss.grid.domain.clue.ClueResult
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Unit tests for `generate-clues` over a real Postgres + a fake [ClueClient].
 * The fake covers the four ADR-0013 §5 outcomes: accepted / retried / skipped_too_long /
 * api_error. A real Postgres is the simplest way to test the selector flags
 * (`--all-lengths`, `--force`, `--limit`, `--dry-run`) without mocking JDBC.
 */
class GenerateCluesCommandTest {
    private lateinit var pg: PostgreSQLContainer<*>

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
    }

    @AfterEach
    fun tearDown() {
        Database.stopForTesting()
        System.clearProperty("DATABASE_URL")
        if (::pg.isInitialized) pg.stop()
    }

    @Test
    fun `first attempt accepted writes the clue to the row`() {
        seedRows(listOf("chat" to null, "arbre" to null))
        val fake = FakeClueClient { _, _ -> ClueResult.Accepted("Animal") }

        run("--language", "fr", "--concurrency", "1") { fake }

        assertThat(clueOf("chat")).isEqualTo("Animal")
        assertThat(clueOf("arbre")).isEqualTo("Animal")
        assertThat(fake.attemptsFor("chat")).isEqualTo(1)
    }

    @Test
    fun `first attempt too long, second accepted writes and counts as retry`() {
        seedRows(listOf("chat" to null))
        val fake =
            FakeClueClient { _, retry ->
                if (retry) ClueResult.Accepted("Court") else ClueResult.TooLong("X".repeat(MAX_CLUE_CHARS + 5))
            }

        run("--concurrency", "1") { fake }

        assertThat(clueOf("chat")).isEqualTo("Court")
        // 1 initial too-long + 1 retry accepted.
        assertThat(fake.attemptsFor("chat")).isEqualTo(2)
    }

    @Test
    fun `all attempts too long leaves clue NULL and does not write`() {
        seedRows(listOf("chat" to null))
        val tooLong = "X".repeat(MAX_CLUE_CHARS + 1)
        val fake = FakeClueClient { _, _ -> ClueResult.TooLong(tooLong) }

        run("--concurrency", "1") { fake }

        assertThat(clueOf("chat")).isNull()
        assertThat(fake.attemptsFor("chat")).isEqualTo(GenerateCluesUseCase.MAX_ATTEMPTS)
    }

    @Test
    fun `api error on attempt leaves clue NULL and run continues to next row`() {
        seedRows(listOf("chat" to null, "arbre" to null))
        val fake =
            FakeClueClient { word, _ ->
                if (word == "chat") ClueResult.ApiError(RuntimeException("boom")) else ClueResult.Accepted("OK")
            }

        run("--concurrency", "1") { fake }

        assertThat(clueOf("chat")).isNull()
        assertThat(clueOf("arbre")).isEqualTo("OK")
    }

    @Test
    fun `--limit caps the number of selected rows`() {
        seedRows(listOf("chat" to null, "arbre" to null, "pluie" to null))
        val fake = FakeClueClient { _, _ -> ClueResult.Accepted("OK") }

        run("--concurrency", "1", "--limit", "2") { fake }

        // Stable ORDER BY word_id makes the picked subset deterministic for a given seed,
        // but for the assertion we only care that exactly 2 rows got cluefied.
        val populated = listOf("chat", "arbre", "pluie").count { clueOf(it) != null }
        assertThat(populated).isEqualTo(2)
    }

    @Test
    fun `--all-lengths includes a length-1 row that the default selector excludes`() {
        seedRows(listOf("a" to null, "chat" to null))
        val fake = FakeClueClient { _, _ -> ClueResult.Accepted("OK") }

        // Default run skips length=1.
        run("--concurrency", "1") { fake }
        assertThat(clueOf("a")).isNull()
        assertThat(clueOf("chat")).isEqualTo("OK")

        // --all-lengths picks it up.
        run("--concurrency", "1", "--all-lengths") { fake }
        assertThat(clueOf("a")).isEqualTo("OK")
    }

    @Test
    fun `--force re-clues a row whose clue is already populated`() {
        seedRows(listOf("chat" to "Old"))
        val fake = FakeClueClient { _, _ -> ClueResult.Accepted("New") }

        // Without --force, the selector skips populated rows.
        run("--concurrency", "1") { fake }
        assertThat(clueOf("chat")).isEqualTo("Old")

        // With --force, we re-clue.
        run("--concurrency", "1", "--force") { fake }
        assertThat(clueOf("chat")).isEqualTo("New")
    }

    @Test
    fun `--dry-run makes API calls but writes nothing`() {
        seedRows(listOf("chat" to null, "arbre" to null))
        val fake = FakeClueClient { _, _ -> ClueResult.Accepted("OK") }

        run("--concurrency", "1", "--dry-run") { fake }

        assertThat(clueOf("chat")).isNull()
        assertThat(clueOf("arbre")).isNull()
        assertThat(fake.totalCalls()).isGreaterThanOrEqualTo(2)
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun run(
        vararg args: String,
        clientFactory: () -> ClueClient,
    ) {
        GenerateCluesCommand(clientFactory).parse(args.toList().toTypedArray())
    }

    private fun ds(): DataSource = requireNotNull(Database.dataSource())

    /** Insert (word, clue?) pairs with default provenance. */
    private fun seedRows(rows: List<Pair<String, String?>>) {
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    // lemma = word so the default `WHERE word = lemma` selector matches; the
                    // post-Grammalecte default is "clue lemmas only" (ADR-0013 §5 amendment).
                    "INSERT INTO words (word, language, lemma, clue, source, source_license) VALUES (?, 'fr', ?, ?, 'test', 'test')",
                ).use { stmt ->
                    for ((word, clue) in rows) {
                        stmt.setString(1, word)
                        stmt.setString(2, word)
                        stmt.setString(3, clue)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
        }
    }

    private fun clueOf(word: String): String? =
        ds().connection.use { conn ->
            conn.prepareStatement("SELECT clue FROM words WHERE word = ? AND language = 'fr'").use { stmt ->
                stmt.setString(1, word)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else error("no row found for word=$word")
                }
            }
        }
}

/**
 * Test fake. `behavior` receives the word and a `retry` flag mirroring [ClueClient.generateClue]
 * so tests can branch on first-vs-subsequent attempt without reaching for `mockk`.
 * Tracks per-word and total call counts for assertions.
 */
private class FakeClueClient(
    private val behavior: (word: String, retry: Boolean) -> ClueResult,
) : ClueClient {
    private val attempts: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    private val total = AtomicInteger(0)

    override suspend fun generateClue(
        word: String,
        retry: Boolean,
    ): ClueResult {
        attempts.computeIfAbsent(word) { AtomicInteger(0) }.incrementAndGet()
        total.incrementAndGet()
        return behavior(word, retry)
    }

    fun attemptsFor(word: String): Int = attempts[word]?.get() ?: 0

    fun totalCalls(): Int = total.get()
}
