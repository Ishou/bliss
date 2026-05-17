package com.bliss.identity.infrastructure.persistence

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.Subject
import com.bliss.identity.domain.provider.UserProvider
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
class PostgresUserProviderRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var userRepo: PostgresUserRepository
    private lateinit var repo: PostgresUserProviderRepository

    private val now: Instant = Instant.parse("2026-05-17T12:00:00Z")

    private fun user(id: UUID = UUID.randomUUID()): User =
        User(
            id = UserId(id),
            displayName = DisplayName.of("Alice"),
            createdAt = now,
            lastSeenAt = now,
        )

    private fun userProvider(
        userId: UserId,
        provider: Provider = Provider.GOOGLE,
        subject: String = "google-sub-1",
        emailAtLink: String? = null,
    ): UserProvider =
        UserProvider(
            userId = userId,
            provider = provider,
            subject = Subject.of(subject),
            emailAtLink = emailAtLink,
            linkedAt = now,
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
        repo = PostgresUserProviderRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) {
            dataSource.connection.use { conn ->
                // CASCADE handles identity_user_providers automatically
                conn.prepareStatement("TRUNCATE identity_users CASCADE").use { it.executeUpdate() }
            }
        }
    }

    @Test
    fun `findByProviderAndSubject returns null when empty`() =
        runTest {
            assertThat(repo.findByProviderAndSubject(Provider.GOOGLE, Subject.of("unknown"))).isNull()
        }

    @Test
    fun `link then findByProviderAndSubject round-trips`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val up = userProvider(userId = u.id)
            repo.link(up)
            assertThat(repo.findByProviderAndSubject(up.provider, up.subject)).isEqualTo(up)
        }

    @Test
    fun `link preserves null emailAtLink`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val up = userProvider(userId = u.id, emailAtLink = null)
            repo.link(up)
            assertThat(repo.findByProviderAndSubject(up.provider, up.subject)?.emailAtLink).isNull()
        }

    @Test
    fun `link preserves non-null emailAtLink`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val up = userProvider(userId = u.id, emailAtLink = "alice@example.com")
            repo.link(up)
            assertThat(repo.findByProviderAndSubject(up.provider, up.subject)?.emailAtLink).isEqualTo("alice@example.com")
        }

    @Test
    fun `listForUser returns all linkages across providers`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val google = userProvider(userId = u.id, provider = Provider.GOOGLE, subject = "g-1")
            val apple = userProvider(userId = u.id, provider = Provider.APPLE, subject = "a-1")
            repo.link(google)
            repo.link(apple)
            assertThat(repo.listForUser(u.id)).containsExactlyInAnyOrder(google, apple)
        }

    @Test
    fun `listForUser returns empty for unknown user`() =
        runTest {
            assertThat(repo.listForUser(UserId(UUID.randomUUID()))).isEmpty()
        }

    @Test
    fun `deleteForUser removes every linkage`() =
        runTest {
            val u = user()
            userRepo.create(u)
            repo.link(userProvider(userId = u.id, provider = Provider.GOOGLE, subject = "g-1"))
            repo.link(userProvider(userId = u.id, provider = Provider.APPLE, subject = "a-1"))
            repo.deleteForUser(u.id)
            assertThat(repo.listForUser(u.id)).isEmpty()
        }

    @Test
    fun `deleteForUser leaves linkages for other users untouched`() =
        runTest {
            val u1 = user()
            val u2 = user()
            userRepo.create(u1)
            userRepo.create(u2)
            val mine = userProvider(userId = u1.id, provider = Provider.GOOGLE, subject = "g-1")
            val theirs = userProvider(userId = u2.id, provider = Provider.APPLE, subject = "a-other")
            repo.link(mine)
            repo.link(theirs)
            repo.deleteForUser(u1.id)
            assertThat(repo.listForUser(u1.id)).isEmpty()
            assertThat(repo.listForUser(u2.id)).containsExactlyInAnyOrder(theirs)
        }

    @Test
    fun `delete of parent user cascades to provider links`() =
        runTest {
            val u = user()
            userRepo.create(u)
            repo.link(userProvider(userId = u.id, provider = Provider.GOOGLE, subject = "g-cascade"))
            userRepo.delete(u.id)
            assertThat(repo.findByProviderAndSubject(Provider.GOOGLE, Subject.of("g-cascade"))).isNull()
        }

    @Test
    fun `linking the same provider twice for one user violates the PK`() =
        runTest {
            val u = user()
            userRepo.create(u)
            val first = userProvider(userId = u.id, provider = Provider.GOOGLE, subject = "g-1")
            val second = userProvider(userId = u.id, provider = Provider.GOOGLE, subject = "g-2")
            repo.link(first)
            assertFailure { repo.link(second) }.isInstanceOf(java.sql.SQLException::class)
        }

    @Test
    fun `linking the same provider-subject to a second user violates the UNIQUE constraint`() =
        runTest {
            val u1 = user()
            userRepo.create(u1)
            val u1Link = userProvider(userId = u1.id, provider = Provider.GOOGLE, subject = "g-1")
            repo.link(u1Link)

            val otherUserId = UserId(UUID.randomUUID())
            userRepo.create(User(otherUserId, DisplayName.of("Bob"), now, now))
            val u2SameSub = userProvider(userId = otherUserId, provider = Provider.GOOGLE, subject = "g-1")
            assertFailure { repo.link(u2SameSub) }.isInstanceOf(java.sql.SQLException::class)
        }
}
