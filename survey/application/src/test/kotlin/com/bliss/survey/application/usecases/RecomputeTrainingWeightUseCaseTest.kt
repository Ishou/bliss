package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.application.ports.ProposedContribution
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.weight.GoldWindowPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RecomputeTrainingWeightUseCaseTest {
    private val cutoff = Instant.parse("2026-05-30T00:00:00Z")
    private val policy = GoldWindowPolicy(cutoff, goldMultiplier = 3.0)

    private val items = InMemorySurveyItemRepository()
    private val roles = InMemoryMaintainerRoleRepository()
    private val useCase = RecomputeTrainingWeightUseCase(roles, items, policy)

    private fun item(
        id: ItemId,
        createdAt: Instant,
    ): SurveyItem =
        SurveyItem(
            id = id,
            mot = "chat",
            definition = "def ${id.value}",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.ANIMALS,
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

    @Test
    fun `role grant back-stamps post-cutoff items, leaves pre-cutoff neutral`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val post = ItemId(UUID.randomUUID())
            val pre = ItemId(UUID.randomUUID())
            items.proposedByUser =
                mapOf(
                    author to
                        listOf(
                            ProposedContribution(item(post, cutoff.plusSeconds(1)), optedOut = false, kCoverage = 2),
                            ProposedContribution(item(pre, cutoff.minusSeconds(1)), optedOut = false, kCoverage = 2),
                        ),
                )

            useCase.onRoleChanged(author, "maintainer", cutoff)

            assertThat(items.trainingWeights[post]).isEqualTo(3.0)
            assertThat(items.trainingWeights[pre]).isEqualTo(1.0)
        }

    @Test
    fun `role revocation resets the user's items to neutral`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.proposedByUser =
                mapOf(author to listOf(ProposedContribution(item(itemId, cutoff.plusSeconds(1)), optedOut = false, kCoverage = 2)))
            roles.upsert(MaintainerRole(author, "maintainer", cutoff))

            useCase.onRoleChanged(author, "player", cutoff.plusSeconds(60))

            assertThat(items.trainingWeights[itemId]).isEqualTo(1.0)
        }

    @Test
    fun `out-of-order event older than the cached one is ignored`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            roles.upsert(MaintainerRole(author, "maintainer", cutoff.plusSeconds(100)))

            useCase.onRoleChanged(author, "player", cutoff.plusSeconds(10))

            assertThat(roles.find(author)).isEqualTo(MaintainerRole(author, "maintainer", cutoff.plusSeconds(100)))
        }

    @Test
    fun `forItem stamps gold when the cached author is a maintainer`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.items[itemId] = item(itemId, cutoff.plusSeconds(1))
            roles.upsert(MaintainerRole(author, "maintainer", cutoff))

            useCase.forItem(itemId, author)

            assertThat(items.trainingWeights[itemId]).isEqualTo(3.0)
        }

    @Test
    fun `forItem leaves a non-maintainer author's item neutral`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.items[itemId] = item(itemId, cutoff.plusSeconds(1))

            useCase.forItem(itemId, author)

            assertThat(items.trainingWeights[itemId]).isEqualTo(1.0)
        }

    @Test
    fun `recomputeAll restamps every cached maintainer's items`() =
        runTest {
            val author = UserId(UUID.randomUUID())
            val itemId = ItemId(UUID.randomUUID())
            items.proposedByUser =
                mapOf(author to listOf(ProposedContribution(item(itemId, cutoff.plusSeconds(1)), optedOut = false, kCoverage = 2)))
            roles.upsert(MaintainerRole(author, "maintainer", cutoff))

            useCase.recomputeAll()

            assertThat(items.trainingWeights[itemId]).isEqualTo(3.0)
        }
}
