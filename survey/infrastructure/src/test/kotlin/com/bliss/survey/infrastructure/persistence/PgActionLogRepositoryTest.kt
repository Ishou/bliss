package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.survey.application.sha256
import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.SurveyAction
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
class PgActionLogRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var actions: PgActionLogRepository

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
        if (!::dataSource.isInitialized) return
        actions = PgActionLogRepository(dataSource)
    }

    @AfterEach
    fun truncate() {
        if (::dataSource.isInitialized) SurveyTestcontainer.truncateAll(dataSource)
    }

    private fun persistCampaign(): CampaignId {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c
                .prepareStatement("INSERT INTO campaigns (campaign_id, batch_label) VALUES (?, ?)")
                .use { s ->
                    s.setObject(1, id)
                    s.setString(2, "round-undo")
                    s.executeUpdate()
                }
        }
        return CampaignId(id)
    }

    private fun sampleBinaryAction(
        campaignId: CampaignId,
        tokenHash: ByteArray,
    ): SurveyAction =
        SurveyAction(
            id = ActionId(UUID.randomUUID()),
            undoTokenHash = tokenHash,
            userId = UserId(UUID.randomUUID()),
            kind = ActionKind.BINARY,
            campaignId = campaignId,
            createdAt = Instant.parse("2026-05-30T10:00:00Z"),
            undoneAt = null,
            createdRatingIds = listOf(RatingId(UUID.randomUUID())),
            createdPairId = null,
            createdItemId = null,
            proposedItemId = null,
            patchedItemId = null,
            priorPos = null,
            priorLastRatedAt = null,
        )

    @Test
    fun `round-trips an action and finds it by token hash`() =
        runTest {
            val campaign = persistCampaign()
            val action = sampleBinaryAction(campaign, sha256("tok"))
            actions.insert(action)

            val found = actions.findByTokenHash(sha256("tok"))
            assertThat(found).isNotNull()
            assertThat(found!!.id).isEqualTo(action.id)
            assertThat(found.createdRatingIds).isEqualTo(action.createdRatingIds)
            assertThat(found.kind).isEqualTo(ActionKind.BINARY)
            assertThat(found.undoneAt).isNull()
            assertThat(actions.findByTokenHash(sha256("other"))).isNull()
        }

    @Test
    fun `markUndone stamps undone_at and returns true, second call returns false`() =
        runTest {
            val campaign = persistCampaign()
            actions.insert(sampleBinaryAction(campaign, sha256("tok2")).copy(id = ActionId(UUID.randomUUID())))
            val stored = actions.findByTokenHash(sha256("tok2"))!!

            assertThat(actions.markUndone(stored.id, Instant.parse("2026-05-30T12:00:00Z"))).isEqualTo(true)
            assertThat(actions.findByTokenHash(sha256("tok2"))?.undoneAt)
                .isEqualTo(Instant.parse("2026-05-30T12:00:00Z"))

            // Conditional single-redemption: a second claim on an already-undone row changes nothing.
            assertThat(actions.markUndone(stored.id, Instant.parse("2026-05-30T13:00:00Z"))).isEqualTo(false)
            assertThat(actions.findByTokenHash(sha256("tok2"))?.undoneAt)
                .isEqualTo(Instant.parse("2026-05-30T12:00:00Z"))
        }

    @Test
    fun `scrubUser nulls user_id`() =
        runTest {
            val campaign = persistCampaign()
            val uid = UserId(UUID.randomUUID())
            actions.insert(sampleBinaryAction(campaign, sha256("tok3")).copy(userId = uid))

            actions.scrubUser(uid)

            assertThat(actions.findByTokenHash(sha256("tok3"))?.userId).isNull()
        }
}
