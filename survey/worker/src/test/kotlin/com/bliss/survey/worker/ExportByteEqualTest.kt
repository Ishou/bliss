package com.bliss.survey.worker

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.infrastructure.persistence.SurveyDatabase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.util.Locale

// Locale pinned to ROOT so "%.2f" emits dots on any runner.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportByteEqualTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private var savedLocale: Locale = Locale.getDefault()

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ROOT)
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                    maximumPoolSize = 4
                    poolName = "survey-byteequal-test"
                },
            )
        SurveyDatabase.runMigrations(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `export is byte-equal to golden fixture`() {
        truncateAll(dataSource)
        runSeed(dataSource)

        val out = Files.createTempFile("export", ".csv")
        runExport(dataSource, out, minRatings = 1, since = null, authWeight = 1.0, anonWeight = 0.5)

        val produced = Files.readString(out)
        val expectedRaw =
            requireNotNull(javaClass.classLoader.getResourceAsStream("byteequal/expected.csv")) {
                "missing resource byteequal/expected.csv"
            }.bufferedReader(Charsets.UTF_8).readText()
        // removeSuffix("\n"): expected.csv may carry an editor-appended trailing newline; the export does not.
        val expected = expectedRaw.removeSuffix("\n")
        assertThat(produced).isEqualTo(expected)
    }

    private fun runSeed(ds: HikariDataSource) {
        val seed =
            requireNotNull(javaClass.classLoader.getResourceAsStream("byteequal/seed.sql")) {
                "missing resource byteequal/seed.sql"
            }.bufferedReader(Charsets.UTF_8).readText()
        ds.connection.use { conn ->
            conn.createStatement().use { stmt -> stmt.execute(seed) }
        }
    }

    private fun truncateAll(ds: HikariDataSource) {
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "TRUNCATE survey_items, ratings, proposed_by, user_progress RESTART IDENTITY CASCADE",
                ).use { it.executeUpdate() }
        }
    }
}
