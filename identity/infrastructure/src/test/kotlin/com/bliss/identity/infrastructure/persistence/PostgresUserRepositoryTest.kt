package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
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
class PostgresUserRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: PostgresUserRepository

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
            .table("flyway_schema_history_identity")
            .load()
            .migrate()
        repo = PostgresUserRepository(dataSource)
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
            conn.createStatement().use { it.executeUpdate("TRUNCATE identity_users CASCADE") }
        }
    }

    @Test
    fun `findById returns null for unknown user`() =
        runTest {
            assertThat(repo.findById(UserId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `create then findById round-trips all fields`() =
        runTest {
            val id = UserId(UUID.randomUUID())
            val user =
                User(
                    id = id,
                    displayName = DisplayName.of("Joueur Test"),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    lastSeenAt = Instant.parse("2026-01-02T12:00:00Z"),
                )
            repo.create(user)
            val loaded = repo.findById(id)
            assertThat(loaded).isNotNull()
            assertThat(loaded!!.id).isEqualTo(id)
            assertThat(loaded.displayName.value).isEqualTo("Joueur Test")
            assertThat(loaded.createdAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
            assertThat(loaded.lastSeenAt).isEqualTo(Instant.parse("2026-01-02T12:00:00Z"))
        }

    @Test
    fun `updateLastSeenAt advances the timestamp`() =
        runTest {
            val id = UserId(UUID.randomUUID())
            val user =
                User(
                    id = id,
                    displayName = DisplayName.of("Joueur"),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    lastSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            repo.create(user)
            val newLastSeen = Instant.parse("2026-06-15T10:30:00Z")
            repo.updateLastSeenAt(id, newLastSeen)
            val loaded = repo.findById(id)
            assertThat(loaded!!.lastSeenAt).isEqualTo(newLastSeen)
        }

    @Test
    fun `updateLastSeenAt is a no-op for unknown user`() =
        runTest {
            // Must not throw — documented contract in the port.
            repo.updateLastSeenAt(UserId(UUID.randomUUID()), Instant.now())
        }

    @Test
    fun `delete removes the row — findById returns null afterwards`() =
        runTest {
            val id = UserId(UUID.randomUUID())
            val user =
                User(
                    id = id,
                    displayName = DisplayName.of("Ephemere"),
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    lastSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            repo.create(user)
            assertThat(repo.findById(id)).isNotNull()
            repo.delete(id)
            assertThat(repo.findById(id)).isNull()
        }
}
