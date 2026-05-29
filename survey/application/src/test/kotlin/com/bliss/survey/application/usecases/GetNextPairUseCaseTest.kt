package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetNextPairUseCaseTest {
    private fun item(
        mot: String,
        definitionSuffix: String,
    ): SurveyItem =
        SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = "def-$definitionSuffix",
            pos = Pos.NOM_COMMUN,
            categorie = Categorie.AUTRE,
            style = Style.DEFINITION_DIRECTE,
            forceClaimed = 3,
            longueur = mot.length,
            source = Source.SYNTHETIC_V1,
            sourceBatch = "test",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.parse("2026-05-25T00:00:00Z"),
        )

    @Test
    fun `returns a pair when a mot has two unrated candidates`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            repo.insert(a)
            repo.insert(b)
            val uc = GetNextPairUseCase(repo)
            val pair = uc.execute(forUser = null, locallyExcluded = emptySet())
            assertThat(pair).isNotNull()
            assertThat(pair!!.mot).isEqualTo("POMME")
        }

    @Test
    fun `returns null when no mot has two candidates`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            repo.insert(item("POMME", "a"))
            repo.insert(item("CHIEN", "a"))
            val uc = GetNextPairUseCase(repo)
            val pair = uc.execute(forUser = null, locallyExcluded = emptySet())
            assertThat(pair).isNull()
        }

    @Test
    fun `respects locally excluded ids`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            repo.insert(a)
            repo.insert(b)
            val uc = GetNextPairUseCase(repo)
            val pair = uc.execute(forUser = null, locallyExcluded = setOf(a.id))
            assertThat(pair).isNull()
        }

    @Test
    fun `prefers an anchor pair built from the caller's known-good item`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val anchor = item("POMME", "anchor")
            val sibling = item("POMME", "sibling")
            repo.insert(anchor)
            repo.insert(sibling)
            val user = UserId(UUID.randomUUID())
            // anchor is rated qualite=5 by the caller; sibling is genuinely unrated.
            repo.ratedByUser = mapOf(user to setOf(anchor.id))
            repo.knownGoodByUser = mapOf(user to setOf(anchor.id))
            val uc = GetNextPairUseCase(repo)
            val pair = uc.execute(forUser = user, locallyExcluded = emptySet())
            assertThat(pair).isNotNull()
            assertThat(pair!!.left.id).isEqualTo(anchor.id)
            assertThat(pair.right.id).isEqualTo(sibling.id)
        }

    @Test
    fun `falls back to two unrated items when no anchor exists`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            repo.insert(a)
            repo.insert(b)
            val user = UserId(UUID.randomUUID())
            // No known-good item for this user; must fall back to two unrated.
            val uc = GetNextPairUseCase(repo)
            val pair = uc.execute(forUser = user, locallyExcluded = emptySet())
            assertThat(pair).isNotNull()
            assertThat(pair!!.mot).isEqualTo("POMME")
        }

    @Test
    fun `does not anchor when the only sibling was already pair-rated by the caller`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val anchor = item("POMME", "anchor")
            val sibling = item("POMME", "sibling")
            repo.insert(anchor)
            repo.insert(sibling)
            val user = UserId(UUID.randomUUID())
            repo.ratedByUser = mapOf(user to setOf(anchor.id))
            repo.knownGoodByUser = mapOf(user to setOf(anchor.id))
            repo.pairRatedByUser = mapOf(user to setOf(setOf(anchor.id, sibling.id)))
            val uc = GetNextPairUseCase(repo)
            // anchor's only sibling is consumed; sibling alone can't form a fallback pair.
            val pair = uc.execute(forUser = user, locallyExcluded = emptySet())
            assertThat(pair).isNull()
        }

    @Test
    fun `excludes items the user has rated`() =
        runTest {
            val repo = InMemorySurveyItemRepository()
            val a = item("POMME", "a")
            val b = item("POMME", "b")
            val c = item("POMME", "c")
            repo.insert(a)
            repo.insert(b)
            repo.insert(c)
            val user = UserId(UUID.randomUUID())
            repo.ratedByUser = mapOf(user to setOf(a.id, b.id))
            val uc = GetNextPairUseCase(repo)
            // Only c is unrated by the user; not enough for a pair.
            val pair = uc.execute(forUser = user, locallyExcluded = emptySet())
            assertThat(pair).isNull()
        }
}
