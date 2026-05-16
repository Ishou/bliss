package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import com.bliss.grid.application.puzzle.StoredPuzzle
import com.bliss.grid.application.puzzle.StoredSummary
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
import org.postgresql.util.PGobject
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresPuzzleRepositorySummariesTest {
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
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
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
    fun `findSummariesByIds returns persisted ids with their totalLetterCells`() {
        val id5 = UUID.randomUUID()
        val id10 = UUID.randomUUID()
        val unseen = UUID.randomUUID()

        repo.getOrCompute(id5) { sampleStoredPuzzle(letterCount = 5) }
        repo.getOrCompute(id10) { sampleStoredPuzzle(letterCount = 10) }

        val summaries = repo.findSummariesByIds(listOf(id5, unseen, id10))

        assertThat(summaries).hasSize(2)
        assertThat(summaries.map { it.puzzleId to it.totalLetterCells })
            .containsExactlyInAnyOrder(id5 to 5, id10 to 10)
    }

    @Test
    fun `findSummariesByIds excludes rows where total_letter_cells is null`() {
        val id = UUID.randomUUID()
        // Pre-backfill leftover: bypass the repo and write a row with a NULL
        // total_letter_cells column to simulate a row inserted before V4 ran
        // (or under a future rollback).
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO puzzles (puzzle_id, width, height, language, title, payload, hints_allowed) " +
                    "VALUES (?, 5, 5, 'fr', 't', ?, 3)",
            ).use { s ->
                s.setObject(1, id)
                s.setObject(
                    2,
                    PGobject().apply {
                        type = "jsonb"
                        value = "{\"width\":5,\"height\":5,\"placements\":[]}"
                    },
                )
                s.executeUpdate()
            }
        }

        val summaries: List<StoredSummary> = repo.findSummariesByIds(listOf(id))

        assertThat(summaries).isEmpty()
    }

    @Test
    fun `findSummariesByIds returns empty list for empty input`() {
        assertThat(repo.findSummariesByIds(emptyList())).isEmpty()
    }

    private fun sampleStoredPuzzle(letterCount: Int): StoredPuzzle {
        require(letterCount in 1..15) { "test helper supports 1..15 letters, asked for $letterCount" }
        // ABCDEFGHIJKLMNO has 15 letters; truncate to letterCount.
        val text = "ABCDEFGHIJKLMNO".take(letterCount)
        val word = Word(text = text, definition = "test")
        val placement =
            WordPlacement(
                word = word,
                cluePosition = Position(Row(0), Column(0)),
                direction = Direction.DOWN_RIGHT,
                chosenClue = word.clues.first(),
            )
        return StoredPuzzle(
            grid = Grid.fromPlacements(width = letterCount + 1, height = letterCount + 1, placements = listOf(placement)),
            title = "t",
            language = "fr",
            hintsAllowed = 3,
            createdAt = Instant.parse("2026-05-13T00:00:00Z"),
        )
    }
}
