package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.survey.application.ports.MaintainerRole
import com.bliss.survey.domain.model.ActionId
import com.bliss.survey.domain.model.ActionKind
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.SurveyAction
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AnonymizeUserRatingsUseCaseTest {
    private val now = Instant.parse("2026-05-25T12:00:00Z")

    private fun item(id: ItemId = ItemId(UUID.randomUUID())): SurveyItem =
        SurveyItem(
            id = id,
            mot = "MOT",
            definition = "def",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.AUTRE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = 3,
            source = Source.CURATED_V1,
            sourceBatch = "b",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = now,
        )

    @Test
    fun `deletes opted-out contributions, anonymises ratings, drops links and progress`() =
        runTest {
            val items = InMemorySurveyItemRepository()
            val ratings = InMemoryRatingRepository()
            val proposedBy = InMemoryProposedByRepository()
            val progress = InMemoryUserProgressRepository()
            val user = UserId(UUID.randomUUID())

            val contribItem = item()
            items.insert(contribItem)
            proposedBy.insert(contribItem.id, user, optedOut = true)

            val keepItem = item()
            items.insert(keepItem)
            val rating =
                Rating(
                    id = RatingId(UUID.randomUUID()),
                    itemId = keepItem.id,
                    userId = user,
                    submittedAs = SubmittedAs.AUTH,
                    qualite = 4,
                    difficulte = 3,
                    flag = null,
                    proposedItemId = null,
                    latencyMs = 800,
                    createdAt = now,
                )
            ratings.insert(rating)
            progress.incrementItemsRated(user, now)

            val actions = InMemoryActionLogRepository()
            actions.insert(
                SurveyAction(
                    id = ActionId(UUID.randomUUID()),
                    undoTokenHash = byteArrayOf(1, 2, 3),
                    userId = user,
                    kind = ActionKind.BINARY,
                    campaignId = CampaignId(UUID.randomUUID()),
                    createdAt = now,
                    undoneAt = null,
                    createdRatingIds = listOf(rating.id),
                    createdPairId = null,
                    createdItemId = null,
                    proposedItemId = null,
                    patchedItemId = null,
                    priorPos = null,
                    priorLastRatedAt = null,
                ),
            )

            val uc =
                AnonymizeUserRatingsUseCase(ratings, proposedBy, items, progress, InMemoryMaintainerRoleRepository(), actions)
            uc.execute(user)

            assertThat(items.items.containsKey(contribItem.id)).isEqualTo(false)
            assertThat(ratings.anonymisedUsers.contains(user)).isTrue()
            assertThat(progress.deleted.contains(user)).isTrue()
            // Rating is now anonymous; userId is null on every rating belonging to user
            assertThat(ratings.ratings.none { it.userId == user }).isTrue()
            // RGPD: the logged action's user_id is scrubbed too
            assertThat(actions.actions.none { it.userId == user }).isTrue()
        }

    @Test
    fun `erases the cached maintainer role on user deletion`() =
        runTest {
            val roles = InMemoryMaintainerRoleRepository()
            val userId = UserId(UUID.randomUUID())
            roles.upsert(MaintainerRole(userId, "maintainer", Instant.parse("2026-05-30T00:00:00Z")))
            val useCase =
                AnonymizeUserRatingsUseCase(
                    ratings = InMemoryRatingRepository(),
                    proposedBy = InMemoryProposedByRepository(),
                    items = InMemorySurveyItemRepository(),
                    progress = InMemoryUserProgressRepository(),
                    maintainerRoles = roles,
                    actions = InMemoryActionLogRepository(),
                )
            useCase.execute(userId)
            assertThat(roles.find(userId)).isNull()
        }
}
