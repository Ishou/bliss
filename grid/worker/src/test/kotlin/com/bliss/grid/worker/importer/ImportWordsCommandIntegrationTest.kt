package com.bliss.grid.worker.importer

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * End-to-end test for `import-words` against a real Postgres (ADR-0013 §6, §7).
 * Docker-availability gate matches PR83's grid-api convention.
 */
class ImportWordsCommandIntegrationTest {
    @BeforeEach
    fun resetState() {
        System.clearProperty("DATABASE_URL")
        Database.stopForTesting()
    }

    @AfterEach
    fun teardown() = resetState()

    @Test
    fun `import-words against a real Postgres applies migration, filters, and is idempotent`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).use { pg ->
            pg.start()
            System.setProperty(
                "DATABASE_URL",
                "jdbc:postgresql://${pg.host}:${pg.firstMappedPort}/${pg.databaseName}" +
                    "?user=${pg.username}&password=${pg.password}",
            )
            Database.start()
            val ds = requireNotNull(Database.dataSource())

            val fixtureUrl = requireNotNull(javaClass.classLoader.getResource("fixtures/hunspell-fr-sample.txt"))
            val fixture = Path.of(fixtureUrl.toURI())
            val expected = filterAndSort(Files.newBufferedReader(fixture).use { it.readLines().asSequence() })

            ImportWordsCommand().parse(arrayOf("--input", fixture.toString()))
            assertThat(allWords(ds)).containsExactlyInAnyOrder(*expected.toTypedArray())
            assertProvenanceAndNullability(ds, expected.size)

            // Idempotency — re-run on the same input is a no-op (ON CONFLICT DO NOTHING).
            ImportWordsCommand().parse(arrayOf("--input", fixture.toString()))
            assertThat(allWords(ds)).containsExactlyInAnyOrder(*expected.toTypedArray())
        }
    }

    private fun allWords(ds: DataSource): List<String> =
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT word FROM words").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    private fun assertProvenanceAndNullability(
        ds: DataSource,
        expectedRows: Int,
    ) {
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                // One round-trip checks: source/source_license values, frequency-is-null on every row,
                // and that the generated `length` column matches `length(word)`.
                stmt
                    .executeQuery(
                        """
                        SELECT
                          count(*) AS total,
                          count(*) FILTER (WHERE source = 'hunspell-fr') AS src_ok,
                          count(*) FILTER (WHERE source_license = 'MPL-2.0') AS lic_ok,
                          count(*) FILTER (WHERE frequency IS NULL) AS freq_null,
                          count(*) FILTER (WHERE length = length(word)) AS len_ok
                        FROM words
                        """.trimIndent(),
                    ).use { rs ->
                        assertThat(rs.next()).isTrue()
                        assertThat(rs.getInt("total")).isEqualTo(expectedRows)
                        assertThat(rs.getInt("src_ok")).isEqualTo(expectedRows)
                        assertThat(rs.getInt("lic_ok")).isEqualTo(expectedRows)
                        assertThat(rs.getInt("freq_null")).isEqualTo(expectedRows)
                        assertThat(rs.getInt("len_ok")).isEqualTo(expectedRows)
                    }
            }
        }
    }
}
