package com.bliss.grid.worker.importer

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * End-to-end test for `import-grammalecte` against a real Postgres (ADR-0013 §6).
 * Docker-availability gate matches the existing importer integration test pattern.
 */
class ImportGrammalecteCommandIntegrationTest {
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
    fun `import-grammalecte inserts rows with lemma and frequency, deduplicates, and recomputes difficulty`() {
        val lexique =
            "id\tFlexion\tLemme\tTotal occurrences\n" +
                "1\tchat\tchat\t5000\n" +
                // Second POS row for "chat" — lower occurrence, should lose deduplication.
                "2\tchat\tchat adj\t100\n" +
                "3\tchien\tchien\t4000\n" +
                "4\triver\triver\t200\n"
        val file = tempDir.resolve("lexique.txt")
        Files.write(file, lexique.toByteArray(StandardCharsets.UTF_8))

        ImportGrammalecteCommand().parse(arrayOf("--input", file.toString()))

        val rows = allRows(requireNotNull(Database.dataSource()))
        assertThat(rows.size).isEqualTo(3)

        val chat = rows.first { it["word"] == "chat" }
        assertThat(chat["lemma"]).isEqualTo("chat")
        assertThat((chat["frequency"] as Number).toLong()).isEqualTo(5000L)
        assertThat(chat["difficulty"]).isNotNull()

        val chien = rows.first { it["word"] == "chien" }
        assertThat(chien["lemma"]).isEqualTo("chien")
        assertThat((chien["frequency"] as Number).toLong()).isEqualTo(4000L)
    }

    @Test
    fun `import-grammalecte is idempotent with ON CONFLICT DO NOTHING`() {
        val lexique =
            "id\tFlexion\tLemme\tTotal occurrences\n" +
                "1\tchat\tchat\t5000\n"
        val file = tempDir.resolve("lexique.txt")
        Files.write(file, lexique.toByteArray(StandardCharsets.UTF_8))

        ImportGrammalecteCommand().parse(arrayOf("--input", file.toString()))
        ImportGrammalecteCommand().parse(arrayOf("--input", file.toString()))

        assertThat(allRows(requireNotNull(Database.dataSource())).size).isEqualTo(1)
    }

    private fun allRows(ds: DataSource): List<Map<String, Any?>> =
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT word, lemma, frequency, difficulty FROM words").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                mapOf(
                                    "word" to rs.getString("word"),
                                    "lemma" to rs.getString("lemma"),
                                    "frequency" to rs.getObject("frequency"),
                                    "difficulty" to rs.getObject("difficulty"),
                                ),
                            )
                        }
                    }
                }
            }
        }
}
