package com.bliss.game.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import com.bliss.game.domain.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresLobbyWriteCoordinatorTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var coordinator: PostgresLobbyWriteCoordinator

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
                    maximumPoolSize = 4
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        coordinator = PostgresLobbyWriteCoordinator(dataSource)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @Test
    fun `withUserLock blocks while another transaction holds the user advisory lock`() {
        val userId = UserId(UUID.randomUUID().toString())
        val holdMillis = 500L
        val holderStarted = CountDownLatch(1)
        val holder =
            Thread {
                dataSource.connection.use { conn ->
                    conn.autoCommit = false
                    conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { stmt ->
                        stmt.setString(1, "user:${userId.value}")
                        stmt.execute()
                    }
                    holderStarted.countDown()
                    Thread.sleep(holdMillis)
                    conn.commit()
                }
            }
        holder.start()
        holderStarted.await()
        val startNs = System.nanoTime()
        runBlocking {
            coordinator.withUserLock(userId) { _ -> Unit }
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        holder.join()
        // Lock contention forces withUserLock to wait at least 450ms while the holder thread sleeps 500ms.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(450L)
    }

    @Test
    fun `withUserLock commits the block result and releases the lock`() {
        val userId = UserId(UUID.randomUUID().toString())
        val result =
            runBlocking {
                coordinator.withUserLock(userId) { _ -> 42 }
            }
        assertThat(result).isEqualTo(42)
        // Second acquisition does not block.
        runBlocking { coordinator.withUserLock(userId) { _ -> Unit } }
    }

    @Test
    fun `withUserLock rolls back on exception`() {
        val userId = UserId(UUID.randomUUID().toString())
        var threw = false
        try {
            runBlocking {
                coordinator.withUserLock(userId) { _ -> error("boom") }
            }
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertThat(threw).isEqualTo(true)
        // Lock released after rollback; re-acquire would block forever otherwise.
        runBlocking { coordinator.withUserLock(userId) { _ -> Unit } }
    }
}
