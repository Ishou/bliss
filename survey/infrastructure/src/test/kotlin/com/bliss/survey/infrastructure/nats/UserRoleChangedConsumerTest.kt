package com.bliss.survey.infrastructure.nats

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.MaintainerRoleRepository
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.usecases.RecomputeTrainingWeightUseCase
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
import com.bliss.survey.domain.weight.GoldWindowPolicy
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRoleChangedConsumerTest {
    private lateinit var natsContainer: GenericContainer<*>
    private lateinit var nats: Connection
    private val scope = CoroutineScope(SupervisorJob())
    private val cutoff = Instant.parse("2026-05-30T00:00:00Z")

    @BeforeAll
    fun startNats() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        natsContainer =
            GenericContainer(DockerImageName.parse("nats:2.10-alpine"))
                .withCommand("-js")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))
        natsContainer.start()
        val url = "nats://${natsContainer.host}:${natsContainer.getMappedPort(4222)}"
        nats =
            Nats.connect(
                Options
                    .Builder()
                    .server(url)
                    .connectionTimeout(Duration.ofSeconds(5))
                    .build(),
            )
        nats.jetStreamManagement().addStream(
            StreamConfiguration
                .builder()
                .name(UserRoleChangedConsumer.STREAM_NAME)
                .subjects(UserRoleChangedConsumer.SUBJECT)
                .retentionPolicy(RetentionPolicy.Limits)
                .storageType(StorageType.Memory)
                .build(),
        )
    }

    @AfterAll
    fun stopNats() {
        if (::nats.isInitialized) nats.close()
        if (::natsContainer.isInitialized) natsContainer.stop()
    }

    @Test
    fun `consumer decodes a role-grant event and stamps the maintainer's post-cutoff item`() =
        runBlocking {
            UserRoleChangedConsumerConfig.bootstrap(nats)

            val author = UserId(UUID.fromString("00000000-0000-7000-8000-000000000010"))
            val itemId = ItemId(UUID.randomUUID())
            val item = sampleItem(itemId, cutoff.plusSeconds(1))
            val items = RecordingItems(mapOf(author to listOf(item)))
            val roles = MapRoles()
            val recompute = RecomputeTrainingWeightUseCase(roles, items, GoldWindowPolicy(cutoff, 3.0))
            val consumer = UserRoleChangedConsumer(nats, recompute, scope, pollWait = Duration.ofMillis(200))
            consumer.start()

            val payload =
                """{"userId":"${author.value}","role":"maintainer","changedAt":"${Instant.parse("2026-05-30T08:00:00Z")}"}"""
            nats.jetStream().publish(UserRoleChangedConsumer.SUBJECT, payload.toByteArray())

            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (items.weights[itemId] == null && System.nanoTime() < deadline) delay(50)
            consumer.stop()

            assertThat(items.weights[itemId]).isEqualTo(3.0)
        }

    private fun sampleItem(
        id: ItemId,
        createdAt: Instant,
    ): SurveyItem =
        SurveyItem(
            id = id,
            mot = "chat",
            definition = "def",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.FAUNE_FLORE,
            style = Style.PERIPHRASE,
            forceClaimed = 2,
            longueur = 4,
            source = Source.RATER_PROPOSED,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = createdAt,
        )

    private class MapRoles : MaintainerRoleRepository {
        val rows = ConcurrentHashMap<UserId, MaintainerRole>()

        override suspend fun find(userId: UserId): MaintainerRole? = rows[userId]

        override suspend fun upsert(role: MaintainerRole) {
            rows[role.userId] = role
        }

        override suspend fun delete(userId: UserId) {
            rows.remove(userId)
        }

        override suspend fun listMaintainers(): List<UserId> = rows.values.filter { it.role == "maintainer" }.map { it.userId }
    }

    private class RecordingItems(
        private val proposed: Map<UserId, List<SurveyItem>>,
    ) : SurveyItemRepository {
        val weights = ConcurrentHashMap<ItemId, Double>()

        override suspend fun updateTrainingWeight(
            id: ItemId,
            weight: Double,
        ) {
            weights[id] = weight
        }

        override suspend fun listProposedByUser(userId: UserId): List<ProposedContribution> =
            proposed[userId].orEmpty().map { ProposedContribution(it, optedOut = false, kCoverage = 2) }

        override suspend fun findById(id: ItemId): SurveyItem? = proposed.values.flatten().firstOrNull { it.id == id }

        override suspend fun insert(item: SurveyItem) = Unit

        override suspend fun insertIfAbsent(item: SurveyItem): SurveyItem = item

        override suspend fun retire(
            id: ItemId,
            at: Instant,
        ) = Unit

        override suspend fun updatePos(
            id: ItemId,
            pos: Pos,
        ) = Unit

        override suspend fun pickUnratedForUser(
            userId: UserId?,
            tier: Tier,
            exclude: Set<ItemId>,
        ): SurveyItem? = null

        override suspend fun pickPairForUser(
            userId: UserId?,
            exclude: Set<ItemId>,
        ): ItemPair? = null

        override suspend fun countUnretiredByTier(): Map<Tier, Int> = emptyMap()

        override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = emptyList()

        override suspend fun deleteByIds(ids: Collection<ItemId>) = Unit
    }
}
