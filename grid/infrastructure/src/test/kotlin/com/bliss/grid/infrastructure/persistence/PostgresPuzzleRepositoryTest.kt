package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
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
class PostgresPuzzleRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: PostgresPuzzleRepository

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
        repo = PostgresPuzzleRepository(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun cleanTables() {
        if (!::repo.isInitialized) return
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("TRUNCATE puzzles CASCADE") }
        }
    }

    @Test
    fun `get returns null for an unknown puzzleId`() {
        assertThat(repo.get(UUID.randomUUID())).isNull()
    }

    @Test
    fun `getOrCompute inserts on miss then reads back the same payload on hit`() {
        val id = UUID.randomUUID()
        val grid = sampleGrid()
        val stored =
            StoredPuzzle(
                grid = grid,
                title = "Test",
                language = "fr",
                hintsAllowed = 3,
                createdAt = Instant.parse("2026-04-24T15:30:00Z"),
            )

        val first = repo.getOrCompute(id) { stored }
        assertThat(first).isNotNull()
        assertThat(first!!.grid.width).isEqualTo(grid.width)
        assertThat(first.grid.placements).hasSize(grid.placements.size)
        assertThat(first.hintsAllowed).isEqualTo(3)

        // Second call must be a hit and return the same snapshot without
        // invoking the factory (verified by passing a factory that errors).
        val second = repo.getOrCompute(id) { error("factory should not run on hit") }
        assertThat(second).isNotNull()
        assertThat(
            second!!
                .grid.placements
                .first()
                .word.text,
        ).isEqualTo("OR")
        assertThat(second.title).isEqualTo("Test")
    }

    @Test
    fun `getOrCompute returns null when factory returns null - generation failure`() {
        val id = UUID.randomUUID()
        val result = repo.getOrCompute(id) { null }
        assertThat(result).isNull()
        // No row should have been inserted.
        assertThat(repo.get(id)).isNull()
    }

    @Test
    fun `payload round-trips placements and lemmas`() {
        val id = UUID.randomUUID()
        val grid =
            Grid.fromPlacements(
                width = 5,
                height = 5,
                placements =
                    listOf(
                        WordPlacement(
                            Word(text = "PARIS", definition = "Capitale", lemma = "PARIS"),
                            Position(Row(0), Column(0)),
                            Direction.RIGHT,
                        ),
                    ),
            )
        val stored = StoredPuzzle(grid, "T", "fr", 3, Instant.parse("2026-04-24T15:30:00Z"))

        repo.getOrCompute(id) { stored }
        val loaded = repo.get(id)

        assertThat(loaded).isNotNull()
        val placement = loaded!!.grid.placements.single()
        assertThat(placement.word.text).isEqualTo("PARIS")
        assertThat(placement.word.lemma).isEqualTo("PARIS")
        assertThat(placement.cluePosition.row.value).isEqualTo(0)
        assertThat(placement.cluePosition.column.value).isEqualTo(0)
    }

    private fun sampleGrid(): Grid =
        Grid.fromPlacements(
            width = 3,
            height = 3,
            placements =
                listOf(
                    WordPlacement(
                        Word(text = "OR", definition = "metal precieux"),
                        Position(Row(0), Column(0)),
                        Direction.RIGHT,
                    ),
                ),
        )
}
