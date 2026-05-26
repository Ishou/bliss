package com.bliss.survey.infrastructure.nats

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.application.usecases.AnonymizeUserRatingsUseCase
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.KCoveragePolicy
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDeletedConsumerTest {
    private lateinit var natsContainer: GenericContainer<*>
    private lateinit var nats: Connection
    private val scope = CoroutineScope(SupervisorJob())

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
        // Create the stream that publishers (identity-api) own in production.
        nats.jetStreamManagement().addStream(
            StreamConfiguration
                .builder()
                .name(UserDeletedConsumer.STREAM_NAME)
                .subjects(UserDeletedConsumer.SUBJECT)
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
    fun `consumer triggers anonymise exactly once per delivered event`() =
        runBlocking {
            UserDeletedConsumerConfig.bootstrap(nats)

            val anonymisedUsers = ConcurrentLinkedQueue<UserId>()
            val invocations = AtomicInteger()
            val anonymise =
                AnonymizeUserRatingsUseCase(
                    ratings = CapturingRatings(invocations, anonymisedUsers),
                    proposedBy = NoopProposedBy,
                    items = NoopItems,
                    progress = NoopProgress,
                )
            val consumer =
                UserDeletedConsumer(
                    nats = nats,
                    anonymise = anonymise,
                    scope = scope,
                    pollWait = Duration.ofMillis(200),
                )
            consumer.start()

            val userId = UUID.fromString("00000000-0000-7000-8000-000000000010")
            val payload =
                """{"userId":"$userId","deletedAt":"${Instant.parse("2026-05-25T12:00:00Z")}"}"""
            nats.jetStream().publish(UserDeletedConsumer.SUBJECT, payload.toByteArray())

            // Poll up to 5 s for the use case to be invoked once.
            val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
            while (invocations.get() < 1 && System.nanoTime() < deadline) {
                delay(50)
            }
            // No duplicate redelivery within an additional 1 s window.
            delay(1000)
            consumer.stop()

            assertThat(anonymisedUsers.toList()).containsExactlyInAnyOrder(UserId(userId))
            check(invocations.get() == 1) { "expected exactly one invocation, got ${invocations.get()}" }
        }

    @Test
    fun `bootstrap is idempotent across repeated invocations`() {
        // addOrUpdateConsumer with a matching config must be a no-op.
        UserDeletedConsumerConfig.bootstrap(nats)
        UserDeletedConsumerConfig.bootstrap(nats)
        UserDeletedConsumerConfig.bootstrap(nats)
    }

    @Test
    fun `start returns null when the consumer has not been bootstrapped`() {
        // Non-default durable keeps this test independent of the bootstrap-then-bind tests above.
        val anonymise =
            AnonymizeUserRatingsUseCase(
                ratings = CapturingRatings(AtomicInteger(), ConcurrentLinkedQueue()),
                proposedBy = NoopProposedBy,
                items = NoopItems,
                progress = NoopProgress,
            )
        val consumer =
            UserDeletedConsumer(
                nats = nats,
                anonymise = anonymise,
                scope = scope,
                durableName = "survey-api-test-no-bootstrap",
                pollWait = Duration.ofMillis(200),
            )
        check(consumer.start() == null) { "expected null (consumer not bootstrapped)" }
    }

    private class CapturingRatings(
        private val invocations: AtomicInteger,
        private val captured: ConcurrentLinkedQueue<UserId>,
    ) : RatingRepository {
        override suspend fun findAuthRating(
            itemId: ItemId,
            userId: UserId,
        ): Rating? = null

        override suspend fun insert(rating: Rating) = Unit

        override suspend fun countByItem(itemId: ItemId): Int = 0

        override suspend fun anonymiseForUser(userId: UserId) {
            invocations.incrementAndGet()
            captured += userId
        }

        override suspend fun aggregateForExport(since: Instant?) = emptyList<com.bliss.survey.application.ports.RatingAggregate>()
    }

    private object NoopProposedBy : ProposedByRepository {
        override suspend fun insert(
            itemId: ItemId,
            userId: UserId,
            optedOut: Boolean,
        ) = Unit

        override suspend fun setOptOut(
            userId: UserId,
            optedOut: Boolean,
        ) = Unit

        override suspend fun listOptedOutByUser(userId: UserId): List<ItemId> = emptyList()

        override suspend fun deleteByUser(userId: UserId) = Unit
    }

    private object NoopItems : SurveyItemRepository {
        override suspend fun findById(id: ItemId): SurveyItem? = null

        override suspend fun insert(item: SurveyItem) = Unit

        override suspend fun retire(
            id: ItemId,
            at: Instant,
        ) = Unit

        override suspend fun pickUnratedForUser(
            userId: UserId?,
            tier: Tier,
            exclude: Set<ItemId>,
        ): SurveyItem? = null

        override suspend fun countUnretiredByTier(): Map<Tier, Int> = emptyMap()

        override suspend fun listSaturated(policy: KCoveragePolicy): List<ItemId> = emptyList()

        override suspend fun listProposedByUser(userId: UserId) = emptyList<com.bliss.survey.application.ports.ProposedContribution>()

        override suspend fun deleteByIds(ids: Collection<ItemId>) = Unit
    }

    private object NoopProgress : UserProgressRepository {
        override suspend fun incrementItemsRated(
            userId: UserId,
            at: Instant,
        ) = Unit

        override suspend fun updateCalibrationAgreement(
            userId: UserId,
            agreement: Double,
        ) = Unit

        override suspend fun get(userId: UserId) = null

        override suspend fun deleteByUser(userId: UserId) = Unit
    }
}
