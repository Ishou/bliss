package com.bliss.identity.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.identity.domain.session.Session
import com.bliss.identity.domain.session.SessionId
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
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresSessionRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var userRepo: PostgresUserRepository
    private lateinit var repo: PostgresSessionRepository

    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")

    private fun user(id: UUID = UUID.randomUUID()): User =
        User(
            id = UserId(id),
            displayName = DisplayName.of("Alice"),
            createdAt = now,
            lastSeenAt = now,
        )

    private fun session(
        userId: UserId,
        id: UUID = UUID.randomUUID(),
        revokedAt: Instant? = null,
    ): Session =
        Session(
            id = SessionId(id),
            userId = userId,
            createdAt = now,
            lastSeenAt = now,
            revokedAt = revokedAt,
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
        userRepo = PostgresUserRepository(dataSource)
        repo = PostgresSessionRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) {
            dataSource.connection.use { conn ->
                // CASCADE handles identity_sessions automatically
                conn.prepareStatement("TRUNCATE identity_users CASCADE").use { it.executeUpdate() }
            }
        }
    }

    @Test
    fun `findById returns null when empty`() =
        runTest {
            assertThat(repo.findById(SessionId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `create then findById round-trips`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val s = session(userId = u.id)
            repo.create(s)
            assertThat(repo.findById(s.id)).isEqualTo(s)
        }

    @Test
    fun `create preserves null revokedAt and isActive is true`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val s = session(userId = u.id, revokedAt = null)
            repo.create(s)
            val loaded = repo.findById(s.id)!!
            assertThat(loaded.revokedAt).isNull()
            assertThat(loaded.isActive).isTrue()
        }

    @Test
    fun `revoke sets revokedAt and makes isActive false`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val s = session(userId = u.id)
            repo.create(s)
            val revokedAt = now.plusSeconds(120)
            repo.revoke(s.id, revokedAt)
            val after = repo.findById(s.id)!!
            assertThat(after.revokedAt).isEqualTo(revokedAt)
            assertThat(after.isActive).isFalse()
        }

    @Test
    fun `revoke is a no-op for an unknown session`() =
        runTest {
            val unknownId = SessionId(UUID.randomUUID())
            repo.revoke(unknownId, now)
            assertThat(repo.findById(unknownId)).isNull()
        }

    @Test
    fun `deleteForUser removes every session for the user`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val a = session(userId = u.id)
            val b = session(userId = u.id)
            repo.create(a)
            repo.create(b)
            repo.deleteForUser(u.id)
            assertThat(repo.findById(a.id)).isNull()
            assertThat(repo.findById(b.id)).isNull()
        }

    @Test
    fun `deleteForUser leaves sessions for other users untouched`() =
        runTest {
            val u1 = user()
            val u2 = user()
            userRepo.create(u1)
            userRepo.create(u2)
            val mine = session(userId = u1.id)
            val theirs = session(userId = u2.id)
            repo.create(mine)
            repo.create(theirs)
            repo.deleteForUser(u1.id)
            assertThat(repo.findById(mine.id)).isNull()
            assertThat(repo.findById(theirs.id)).isEqualTo(theirs)
        }

    @Test
    fun `revoke is idempotent — second call preserves the original revokedAt`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val s = session(userId = u.id)
            repo.create(s)
            val firstRevoke = now.plusSeconds(60)
            val secondRevoke = now.plusSeconds(120)
            repo.revoke(s.id, firstRevoke)
            repo.revoke(s.id, secondRevoke)
            assertThat(repo.findById(s.id)?.revokedAt).isEqualTo(firstRevoke)
        }

    @Test
    fun `delete of parent user cascades to sessions`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val s = session(userId = u.id)
            repo.create(s)
            userRepo.delete(u.id)
            assertThat(repo.findById(s.id)).isNull()
        }
}
