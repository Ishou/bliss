package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.RatingId
import com.bliss.survey.domain.model.SubmittedAs
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetLemmaMetaUseCaseTest {
    private val mot = "CHAT"
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val userId = UserId(UUID(0L, 1L))

    private fun itemId() = ItemId(UUID.randomUUID())

    private fun ratingWith(
        itemId: ItemId,
        targetSense: String? = null,
        subTags: List<String> = emptyList(),
    ) = Rating(
        id = RatingId(UUID.randomUUID()),
        itemId = itemId,
        userId = userId,
        submittedAs = SubmittedAs.AUTH,
        qualite = 3,
        difficulte = 2,
        flag = null,
        proposedItemId = null,
        latencyMs = null,
        createdAt = now,
        targetSense = targetSense,
        subTags = subTags,
    )

    private fun repoFor(mot: String = this.mot): InMemoryRatingRepository = InMemoryRatingRepository()

    private fun InMemoryRatingRepository.seed(
        mot: String,
        targetSense: String? = null,
        subTags: List<String> = emptyList(),
    ): ItemId {
        val id = itemId()
        itemIdToMot[id] = mot
        ratings += ratingWith(id, targetSense = targetSense, subTags = subTags)
        return id
    }

    @Test
    fun `empty repository returns empty meta`() =
        runTest {
            val result = GetLemmaMetaUseCase(repoFor()).execute(mot)
            assertThat(result.priorSenses).isEmpty()
            assertThat(result.priorSubTags).isEmpty()
        }

    @Test
    fun `exact-duplicate senses collapse to one entry`() =
        runTest {
            val repo = repoFor()
            repo.seed(mot, targetSense = "animal")
            repo.seed(mot, targetSense = "animal")
            val result = GetLemmaMetaUseCase(repo).execute(mot)
            assertThat(result.priorSenses).isEqualTo(listOf("animal"))
        }

    @Test
    fun `near-duplicate senses that normalize to the same form collapse to first-seen raw value`() =
        runTest {
            val repo = repoFor()
            // "L'animal" and "animal" both normalize to "animal"; first-seen raw wins
            repo.seed(mot, targetSense = "L'animal")
            repo.seed(mot, targetSense = "animal")
            val result = GetLemmaMetaUseCase(repo).execute(mot)
            assertThat(result.priorSenses).isEqualTo(listOf("L'animal"))
        }

    @Test
    fun `sense whose normalized form is blank is dropped`() =
        runTest {
            val repo = repoFor()
            // "La " is non-blank as a raw string but GlossNormalizer strips it to "" (determiner-only)
            repo.seed(mot, targetSense = "La ")
            repo.seed(mot, targetSense = "félin domestique")
            val result = GetLemmaMetaUseCase(repo).execute(mot)
            assertThat(result.priorSenses).isEqualTo(listOf("félin domestique"))
        }

    @Test
    fun `ratings for other lemmas are excluded`() =
        runTest {
            val repo = repoFor()
            repo.seed(mot, targetSense = "félin")
            repo.seed("CHIEN", targetSense = "canidé")
            val result = GetLemmaMetaUseCase(repo).execute(mot)
            assertThat(result.priorSenses).isEqualTo(listOf("félin"))
        }

    @Test
    fun `sub-tags are deduplicated by normalized form`() =
        runTest {
            val repo = repoFor()
            repo.seed(mot, subTags = listOf("mammifère", "Mammifère"))
            val result = GetLemmaMetaUseCase(repo).execute(mot)
            assertThat(result.priorSubTags).isEqualTo(listOf("mammifère"))
        }
}
