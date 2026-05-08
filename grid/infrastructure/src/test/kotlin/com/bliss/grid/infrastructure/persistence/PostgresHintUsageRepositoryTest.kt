package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.grid.application.puzzle.StoredPuzzle
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresHintUsageRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var puzzles: PostgresPuzzleRepository
    private lateinit var hintUsage: PostgresHintUsageRepository

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = pg.jdbcUrl
                    username = pg.username
                    password = pg.password
                },
            )
        val migrations =
            requireNotNull(System.getProperty("flyway.test.migrations")) {
                "flyway.test.migrations system property must be set by build.gradle.kts"
            }
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("filesystem:$migrations")
            .load()
            .migrate()
        puzzles = PostgresPuzzleRepository(dataSource)
        hintUsage = PostgresHintUsageRepository(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun cleanTables() {
        if (!::dataSource.isInitialized) return
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("TRUNCATE puzzles CASCADE") }
        }
    }

    @Test
    fun `trySpend below cap returns increasing hints_used`() {
        val (puzzleId, sessionId) = setup()
        assertThat(hintUsage.trySpend(puzzleId, sessionId, hintsAllowed = 3)).isEqualTo(1)
        assertThat(hintUsage.trySpend(puzzleId, sessionId, hintsAllowed = 3)).isEqualTo(2)
        assertThat(hintUsage.trySpend(puzzleId, sessionId, hintsAllowed = 3)).isEqualTo(3)
    }

    @Test
    fun `trySpend at cap returns null without changing the row`() {
        val (puzzleId, sessionId) = setup()
        repeat(3) { hintUsage.trySpend(puzzleId, sessionId, hintsAllowed = 3) }
        assertThat(hintUsage.trySpend(puzzleId, sessionId, hintsAllowed = 3)).isNull()
        // And again — still null, still no change.
        assertThat(hintUsage.trySpend(puzzleId, sessionId, hintsAllowed = 3)).isNull()
    }

    @Test
    fun `trySpend keeps separate counters per session`() {
        val (puzzleId, _) = setup()
        val sessionA = UUID.randomUUID()
        val sessionB = UUID.randomUUID()
        assertThat(hintUsage.trySpend(puzzleId, sessionA, hintsAllowed = 3)).isEqualTo(1)
        assertThat(hintUsage.trySpend(puzzleId, sessionA, hintsAllowed = 3)).isEqualTo(2)
        assertThat(hintUsage.trySpend(puzzleId, sessionB, hintsAllowed = 3)).isEqualTo(1)
    }

    @Test
    fun `trySpend with hintsAllowed=0 returns null without inserting`() {
        val (puzzleId, sessionId) = setup()
        assertThat(hintUsage.trySpend(puzzleId, sessionId, hintsAllowed = 0)).isNull()
    }

    private fun setup(): Pair<UUID, UUID> {
        val puzzleId = UUID.randomUUID()
        val grid =
            Grid.fromPlacements(
                width = 3,
                height = 3,
                placements =
                    listOf(
                        WordPlacement(
                            Word(text = "OR", definition = "metal"),
                            Position(Row(0), Column(0)),
                            Direction.RIGHT,
                        ),
                    ),
            )
        val stored = StoredPuzzle(grid, "T", "fr", 3, Instant.parse("2026-04-24T15:30:00Z"))
        puzzles.getOrCompute(puzzleId) { stored }
        return puzzleId to UUID.randomUUID()
    }
}
