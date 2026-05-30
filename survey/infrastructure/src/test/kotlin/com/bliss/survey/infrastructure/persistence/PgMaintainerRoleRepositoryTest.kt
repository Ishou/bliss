package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.domain.model.UserId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgMaintainerRoleRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repo: PgMaintainerRoleRepository

    private val now: Instant = Instant.parse("2026-05-30T12:00:00Z")

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = SurveyTestcontainer.startPostgres()
        dataSource = SurveyTestcontainer.dataSourceFor(pg)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @BeforeEach
    fun freshRepo() {
        if (::dataSource.isInitialized) repo = PgMaintainerRoleRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) {
            dataSource.connection.use { conn ->
                conn.prepareStatement("TRUNCATE maintainer_roles").use { it.executeUpdate() }
            }
        }
    }

    @Test
    fun `find returns null when absent`() =
        runTest {
            assertThat(repo.find(UserId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `upsert then find round-trips role and changedAt`() =
        runTest {
            val role = MaintainerRole(UserId(UUID.randomUUID()), "maintainer", now)
            repo.upsert(role)
            assertThat(repo.find(role.userId)).isEqualTo(role)
        }

    @Test
    fun `upsert overwrites an existing row`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            repo.upsert(MaintainerRole(userId, "maintainer", now))
            repo.upsert(MaintainerRole(userId, "player", now.plusSeconds(60)))
            assertThat(repo.find(userId)).isEqualTo(MaintainerRole(userId, "player", now.plusSeconds(60)))
        }

    @Test
    fun `delete removes the row`() =
        runTest {
            val userId = UserId(UUID.randomUUID())
            repo.upsert(MaintainerRole(userId, "maintainer", now))
            repo.delete(userId)
            assertThat(repo.find(userId)).isNull()
        }

    @Test
    fun `listMaintainers returns only maintainer-role users`() =
        runTest {
            val keeper = UserId(UUID.randomUUID())
            val player = UserId(UUID.randomUUID())
            repo.upsert(MaintainerRole(keeper, "maintainer", now))
            repo.upsert(MaintainerRole(player, "player", now))
            assertThat(repo.listMaintainers()).containsExactlyInAnyOrder(keeper)
        }
}
