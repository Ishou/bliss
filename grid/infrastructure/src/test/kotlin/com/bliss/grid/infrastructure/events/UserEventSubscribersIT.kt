package com.bliss.grid.infrastructure.events

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.grid.application.puzzle.StoredPuzzle
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import com.bliss.grid.infrastructure.persistence.PostgresHintUsageRepository
import com.bliss.grid.infrastructure.persistence.PostgresPuzzleRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserEventSubscribersIT {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var nats: GenericContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var natsConnection: Connection
    private lateinit var puzzles: PostgresPuzzleRepository
    private lateinit var hintUsage: PostgresHintUsageRepository
    private lateinit var subscribers: UserEventSubscribers

    @BeforeAll
    fun startContainers() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
        nats =
            GenericContainer(DockerImageName.parse("nats:2.10-alpine"))
                .withCommand("-js")
                .withExposedPorts(NATS_PORT)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))
        nats.start()
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

        val natsUrl = "nats://${nats.host}:${nats.getMappedPort(NATS_PORT)}"
        val options =
            Options
                .Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(5))
                .build()
        natsConnection = Nats.connect(options)
        val streamMgmt = natsConnection.jetStreamManagement()
        streamMgmt.addStream(
            StreamConfiguration
                .builder()
                .name("WORDSPARROW_USER_EVENTS")
                .subjects("wordsparrow.user.>")
                .storageType(StorageType.File)
                .build(),
        )
        subscribers = UserEventSubscribers(natsConnection.jetStream(), hintUsage)
        subscribers.start()
    }

    @AfterAll
    fun stopContainers() {
        if (::subscribers.isInitialized) subscribers.close()
        if (::natsConnection.isInitialized) natsConnection.close()
        if (::dataSource.isInitialized) dataSource.close()
        if (::nats.isInitialized) nats.stop()
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
    fun `user_deleted message removes rows for that user and preserves others`() {
        val puzzleId = seedPuzzle()
        val deletedUser = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            hintUsage.trySpend(conn, puzzleId, deletedUser, hintsAllowed = 3)
            hintUsage.trySpend(conn, puzzleId, survivor, hintsAllowed = 3)
            conn.commit()
        }

        publishUserDeleted(deletedUser)

        waitUntil(Duration.ofSeconds(2)) {
            hintUsage.usedFor(puzzleId, deletedUser) == 0
        }
        assertThat(hintUsage.usedFor(puzzleId, deletedUser)).isEqualTo(0)
        assertThat(hintUsage.usedFor(puzzleId, survivor)).isEqualTo(1)
    }

    private fun publishUserDeleted(userId: UUID) {
        val payload = """{"userId":"$userId","deletedAt":"${Instant.now()}"}"""
        natsConnection
            .jetStream()
            .publish("wordsparrow.user.deleted", payload.toByteArray(Charsets.UTF_8))
    }

    private fun seedPuzzle(): UUID {
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
        return puzzleId
    }

    private fun waitUntil(
        timeout: Duration,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
    }

    companion object {
        private const val NATS_PORT = 4222
    }
}
