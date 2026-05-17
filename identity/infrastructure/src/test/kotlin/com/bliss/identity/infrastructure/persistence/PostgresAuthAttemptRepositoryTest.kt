package com.bliss.identity.infrastructure.persistence

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.bliss.identity.domain.auth.AuthAttempt
import com.bliss.identity.domain.auth.AuthAttemptId
import com.bliss.identity.domain.auth.PkceVerifier
import com.bliss.identity.domain.auth.State
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.user.DisplayName
import com.bliss.identity.domain.user.User
import com.bliss.identity.domain.user.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresAuthAttemptRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var users: PostgresUserRepository
    private lateinit var repo: PostgresAuthAttemptRepository

    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")
    private val random = SecureRandom()

    private fun attempt(
        state: State = State.generate(random),
        provider: Provider = Provider.GOOGLE,
        linkTo: UserId? = null,
    ): AuthAttempt =
        AuthAttempt(
            id = AuthAttemptId(UUID.randomUUID()),
            state = state,
            pkceVerifier = PkceVerifier.generate(random),
            provider = provider,
            returnTo = "/dashboard",
            linkToUserId = linkTo,
            expiresAt = now.plusSeconds(600),
        )

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
            .table("flyway_schema_history_identity")
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun freshRepo() {
        if (!::dataSource.isInitialized) return
        users = PostgresUserRepository(dataSource)
        repo = PostgresAuthAttemptRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) {
            dataSource.connection.use { conn ->
                // CASCADE handles identity_auth_attempts automatically
                conn.prepareStatement("TRUNCATE identity_users CASCADE").use { it.executeUpdate() }
                conn.prepareStatement("TRUNCATE identity_auth_attempts").use { it.executeUpdate() }
            }
        }
    }

    @Test
    fun `findByState returns null when empty`() =
        runTest {
            assertThat(repo.findByState(State.generate(random))).isNull()
        }

    @Test
    fun `create then findByState round-trips a sign-in attempt`() =
        runTest {
            val a = attempt(provider = Provider.GOOGLE, linkTo = null)
            repo.create(a)
            assertThat(repo.findByState(a.state)).isEqualTo(a)
        }

    @Test
    fun `create then findByState round-trips a linking-mode attempt`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            val a = attempt(linkTo = userId)
            repo.create(a)
            assertThat(repo.findByState(a.state)).isEqualTo(a)
        }

    @Test
    fun `deleteByState removes the attempt`() =
        runTest {
            val a = attempt()
            repo.create(a)
            repo.deleteByState(a.state)
            assertThat(repo.findByState(a.state)).isNull()
        }

    @Test
    fun `deleteByState is a no-op for an unknown state`() =
        runTest {
            val unknown = State.generate(random)
            repo.deleteByState(unknown)
            assertThat(repo.findByState(unknown)).isNull()
        }

    @Test
    fun `linking-mode null link_to_user_id round-trips`() =
        runTest {
            val a = attempt(linkTo = null)
            repo.create(a)
            val found = repo.findByState(a.state)!!
            assertThat(found.linkToUserId).isNull()
        }

    @Test
    fun `creating two attempts with the same state violates the UNIQUE constraint`() =
        runTest {
            val state = State.generate(random)
            repo.create(attempt(state = state))
            assertFailure { repo.create(attempt(state = state)) }
                .isInstanceOf(java.sql.SQLException::class)
        }

    @Test
    fun `deleting the linked user deletes the attempt via ON DELETE CASCADE`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            users.create(User(userId, DisplayName.of("Alice"), now, now))
            val a = attempt(linkTo = userId)
            repo.create(a)
            users.delete(userId)
            assertThat(repo.findByState(a.state)).isNull()
        }
}
