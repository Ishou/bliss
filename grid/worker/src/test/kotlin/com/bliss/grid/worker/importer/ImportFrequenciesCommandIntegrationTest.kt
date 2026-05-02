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
 * End-to-end test for `import-frequencies` against a real Postgres (ADR-0013 §6).
 * Verifies that `applyFrequencies` + `recomputeDifficulty` write to the DB correctly.
 */
class ImportFrequenciesCommandIntegrationTest {
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
    fun `import-frequencies updates frequency and recomputes difficulty to a non-zero value at most 1`() {
        seedWords(requireNotNull(Database.dataSource()))

        val freqFile = tempDir.resolve("freq.txt")
        Files.write(freqFile, "chat 5000\nchien 4000\n".toByteArray(StandardCharsets.UTF_8))

        ImportFrequenciesCommand().parse(arrayOf("--input", freqFile.toString()))

        val rows = allRows(requireNotNull(Database.dataSource()))
        assertThat(rows.size).isEqualTo(2)

        val chat = rows.first { it["word"] == "chat" }
        assertThat((chat["frequency"] as Number).toLong()).isEqualTo(5000L)
        val chatDifficulty = chat["difficulty"] as Float
        assertThat(chatDifficulty).isNotNull()
        assertThat(chatDifficulty > 0.0f).isEqualTo(true)
        assertThat(chatDifficulty <= 1.0f).isEqualTo(true)

        val chien = rows.first { it["word"] == "chien" }
        assertThat((chien["frequency"] as Number).toLong()).isEqualTo(4000L)
        val chienDifficulty = chien["difficulty"] as Float
        assertThat(chienDifficulty).isNotNull()
        assertThat(chienDifficulty > 0.0f).isEqualTo(true)
        assertThat(chienDifficulty <= 1.0f).isEqualTo(true)
    }

    @Test
    fun `import-frequencies is idempotent - re-running with same data yields same result`() {
        seedWords(requireNotNull(Database.dataSource()))

        val freqFile = tempDir.resolve("freq.txt")
        Files.write(freqFile, "chat 5000\n".toByteArray(StandardCharsets.UTF_8))

        ImportFrequenciesCommand().parse(arrayOf("--input", freqFile.toString()))
        ImportFrequenciesCommand().parse(arrayOf("--input", freqFile.toString()))

        val rows = allRows(requireNotNull(Database.dataSource()))
        assertThat(rows.filter { it["word"] == "chat" }.size).isEqualTo(1)
        assertThat((rows.first { it["word"] == "chat" }["frequency"] as Number).toLong()).isEqualTo(5000L)
    }

    private fun seedWords(ds: DataSource) {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "INSERT INTO words (word, language, source, source_license) VALUES (?, 'fr', 'test', 'test')",
                ).use { stmt ->
                    for (word in listOf("chat", "chien")) {
                        stmt.setString(1, word)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
        }
    }

    private fun allRows(ds: DataSource): List<Map<String, Any?>> =
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT word, frequency, difficulty FROM words ORDER BY word").use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                mapOf(
                                    "word" to rs.getString("word"),
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
