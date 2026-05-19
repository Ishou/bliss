package com.bliss.grid.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
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
import java.sql.Connection
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
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
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
        val (puzzleId, userId) = setup()
        withConnection { conn ->
            assertThat(hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)).isEqualTo(1)
            assertThat(hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)).isEqualTo(2)
            assertThat(hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)).isEqualTo(3)
        }
    }

    @Test
    fun `trySpend at cap returns null without changing the row`() {
        val (puzzleId, userId) = setup()
        withConnection { conn ->
            repeat(3) { hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3) }
            assertThat(hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)).isNull()
            assertThat(hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)).isNull()
        }
    }

    @Test
    fun `trySpend keeps separate counters per user`() {
        val (puzzleId, _) = setup()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        withConnection { conn ->
            assertThat(hintUsage.trySpend(conn, puzzleId, userA, hintsAllowed = 3)).isEqualTo(1)
            assertThat(hintUsage.trySpend(conn, puzzleId, userA, hintsAllowed = 3)).isEqualTo(2)
            assertThat(hintUsage.trySpend(conn, puzzleId, userB, hintsAllowed = 3)).isEqualTo(1)
        }
    }

    @Test
    fun `trySpend with hintsAllowed=0 returns null without inserting`() {
        val (puzzleId, userId) = setup()
        withConnection { conn ->
            assertThat(hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 0)).isNull()
        }
        assertThat(hintUsage.usedFor(puzzleId, userId)).isEqualTo(0)
    }

    @Test
    fun `usedFor returns zero when there is no row`() {
        val (puzzleId, userId) = setup()
        assertThat(hintUsage.usedFor(puzzleId, userId)).isEqualTo(0)
    }

    @Test
    fun `usedFor returns the current hints_used after a spend`() {
        val (puzzleId, userId) = setup()
        withConnection { conn ->
            hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)
            hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)
        }
        assertThat(hintUsage.usedFor(puzzleId, userId)).isEqualTo(2)
    }

    @Test
    fun `deleteByUser blocks while another transaction holds the user advisory lock`() {
        val (puzzleId, userId) = setup()
        withConnection { conn ->
            hintUsage.trySpend(conn, puzzleId, userId, hintsAllowed = 3)
        }
        val holdMillis = 500L
        val holderReleased = java.util.concurrent.CountDownLatch(1)
        val holderStarted = java.util.concurrent.CountDownLatch(1)
        val holder =
            Thread {
                dataSource.connection.use { conn ->
                    conn.autoCommit = false
                    conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { stmt ->
                        stmt.setString(1, "user:$userId")
                        stmt.execute()
                    }
                    holderStarted.countDown()
                    Thread.sleep(holdMillis)
                    conn.commit()
                    holderReleased.countDown()
                }
            }
        holder.start()
        holderStarted.await()
        val startNs = System.nanoTime()
        val deleted = hintUsage.deleteByUser(userId)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        holder.join()
        assertThat(deleted).isEqualTo(1)
        // Lock contention forces deleteByUser to wait at least 450ms while the holder thread sleeps 500ms.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(450L)
    }

    @Test
    fun `deleteByUser removes every row for the user and is idempotent`() {
        val (puzzleId, userA) = setup()
        val userB = UUID.randomUUID()
        withConnection { conn ->
            hintUsage.trySpend(conn, puzzleId, userA, hintsAllowed = 3)
            hintUsage.trySpend(conn, puzzleId, userB, hintsAllowed = 3)
        }
        assertThat(hintUsage.deleteByUser(userA)).isEqualTo(1)
        assertThat(hintUsage.usedFor(puzzleId, userA)).isEqualTo(0)
        assertThat(hintUsage.usedFor(puzzleId, userB)).isEqualTo(1)
        // Idempotent: second call deletes nothing.
        assertThat(hintUsage.deleteByUser(userA)).isEqualTo(0)
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

    private fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }
}
