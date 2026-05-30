package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
class PgUserProgressRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var progress: PgUserProgressRepository

    private val now: Instant = Instant.parse("2026-05-25T12:00:00Z")

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
    fun freshRepos() {
        if (!::dataSource.isInitialized) return
        progress = PgUserProgressRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    @Test
    fun `incrementItemsRated upserts and increments`() =
        runTest {
            val user = UserId(UUID.randomUUID())
            progress.incrementItemsRated(user, now)
            progress.incrementItemsRated(user, now.plusSeconds(60))
            progress.incrementItemsRated(user, now.plusSeconds(120))
            val loaded = progress.get(user)
            assertThat(loaded).isNotNull()
            assertThat(loaded?.itemsRated).isEqualTo(3)
            assertThat(loaded?.lastRatedAt).isEqualTo(now.plusSeconds(120))
        }

    @Test
    fun `updateCalibrationAgreement upserts without altering items_rated`() =
        runTest {
            val user = UserId(UUID.randomUUID())
            progress.incrementItemsRated(user, now)
            progress.updateCalibrationAgreement(user, 0.875)
            val loaded = progress.get(user)
            assertThat(loaded?.itemsRated).isEqualTo(1)
            assertThat(loaded?.calibrationAgreement).isEqualTo(0.875)
        }

    @Test
    fun `get returns null for unknown user`() =
        runTest {
            assertThat(progress.get(UserId(UUID.randomUUID()))).isNull()
        }

    @Test
    fun `decrementItemsRated floors at zero and restores prior last_rated_at`() =
        runTest {
            val user = UserId(UUID.randomUUID())
            progress.incrementItemsRated(user, now)
            progress.incrementItemsRated(user, now.plusSeconds(60))

            progress.decrementItemsRated(user, by = 1, priorLastRatedAt = now)
            val afterOne = progress.get(user)
            assertThat(afterOne?.itemsRated).isEqualTo(1)
            assertThat(afterOne?.lastRatedAt).isEqualTo(now)

            // Decrementing by more than remaining floors at 0 and may null last_rated_at.
            progress.decrementItemsRated(user, by = 5, priorLastRatedAt = null)
            val floored = progress.get(user)
            assertThat(floored?.itemsRated).isEqualTo(0)
            assertThat(floored?.lastRatedAt).isNull()
        }

    @Test
    fun `deleteByUser removes the row`() =
        runTest {
            val user = UserId(UUID.randomUUID())
            progress.incrementItemsRated(user, now)
            progress.deleteByUser(user)
            assertThat(progress.get(user)).isNull()
        }
}
