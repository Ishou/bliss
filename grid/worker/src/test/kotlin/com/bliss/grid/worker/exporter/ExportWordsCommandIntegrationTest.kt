package com.bliss.grid.worker.exporter

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import org.apache.commons.csv.CSVFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * End-to-end test for `export-words` against a real Postgres (ADR-0013 §7, §8).
 * Docker-availability gate matches PR83/PR84/PR85 worker integration tests.
 */
class ExportWordsCommandIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

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
    fun `export-words emits only fr rows with non-null clues, sorted by word, with header`() {
        seedFixtures()

        val output = tempDir.resolve("words-fr.csv")
        ExportWordsCommand().parse(arrayOf("--language", "fr", "--output", output.toString()))

        val lines = Files.readAllLines(output, StandardCharsets.UTF_8)
        // 1 header + 5 fr rows with clues (the 6th fr row has NULL clue and the en row is filtered by language).
        assertThat(lines.size).isEqualTo(6)
        assertThat(lines[0]).isEqualTo("word,language,length,frequency,difficulty,clue,source,source_license")

        val records = parseCsv(output)
        // Sorted by word ascending.
        assertThat(records.map { it.word }).containsExactly("aide", "ami", "chat", "rose", "soleil")
        // Round-trip: each non-null field round-trips intact.
        val chat = records.first { it.word == "chat" }
        assertThat(chat.language).isEqualTo("fr")
        assertThat(chat.length).isEqualTo(4)
        assertThat(chat.clue).isEqualTo("Felin domestique")
        assertThat(chat.source).isEqualTo("hand-curated")
        assertThat(chat.sourceLicense).isEqualTo("FSL-1.1-MIT")
        // Difficulty was NULL on insert → empty string in CSV.
        assertThat(chat.difficulty).isEqualTo("")
        // Difficulty was non-null on the "rose" fixture → round-trips to its string form.
        val rose = records.first { it.word == "rose" }
        assertThat(rose.difficulty).isEqualTo("0.42")
    }

    @Test
    fun `export-words is byte-identical across re-runs against the same DB state`() {
        seedFixtures()

        val first = tempDir.resolve("first.csv")
        val second = tempDir.resolve("second.csv")
        ExportWordsCommand().parse(arrayOf("--language", "fr", "--output", first.toString()))
        ExportWordsCommand().parse(arrayOf("--language", "fr", "--output", second.toString()))

        val firstBytes = Files.readAllBytes(first)
        val secondBytes = Files.readAllBytes(second)
        assertThat(firstBytes.contentEquals(secondBytes)).isTrue()
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private fun seedFixtures() {
        // 5 fr rows with clues (varied difficulty: 4 NULL, 1 non-null), 1 fr row with NULL clue
        // (filtered out), 1 en row with a clue (filtered by language).
        val fixtures =
            listOf(
                Fixture("chat", "fr", null, "Felin domestique"),
                Fixture("ami", "fr", null, "Compagnon proche"),
                Fixture("aide", "fr", null, "Soutien apporte"),
                Fixture("soleil", "fr", null, "Astre du jour"),
                Fixture("rose", "fr", 0.42f, "Fleur a epines"),
                Fixture("zzznoclue", "fr", null, null),
                Fixture("cat", "en", null, "Domestic feline"),
            )
        ds().connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO words (word, language, difficulty, clue, source, source_license)
                    VALUES (?, ?, ?, ?, 'hand-curated', 'FSL-1.1-MIT')
                    """.trimIndent(),
                ).use { stmt ->
                    for (f in fixtures) {
                        stmt.setString(1, f.word)
                        stmt.setString(2, f.language)
                        if (f.difficulty == null) stmt.setNull(3, java.sql.Types.REAL) else stmt.setFloat(3, f.difficulty)
                        if (f.clue == null) stmt.setNull(4, java.sql.Types.VARCHAR) else stmt.setString(4, f.clue)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
        }
    }

    private fun ds(): DataSource = requireNotNull(Database.dataSource())

    private fun parseCsv(path: Path): List<CsvRow> =
        Files.newInputStream(path).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                val format =
                    CSVFormat.RFC4180
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build()
                org.apache.commons.csv.CSVParser.parse(reader, format).use { parser ->
                    parser.records.map { rec ->
                        CsvRow(
                            word = rec.get("word"),
                            language = rec.get("language"),
                            length = rec.get("length").toInt(),
                            difficulty = rec.get("difficulty"),
                            clue = rec.get("clue"),
                            source = rec.get("source"),
                            sourceLicense = rec.get("source_license"),
                        )
                    }
                }
            }
        }

    private data class Fixture(
        val word: String,
        val language: String,
        val difficulty: Float?,
        val clue: String?,
    )

    private data class CsvRow(
        val word: String,
        val language: String,
        val length: Int,
        val difficulty: String,
        val clue: String,
        val source: String,
        val sourceLicense: String,
    )
}
