package com.bliss.grid.application.lexicon

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.lexicon.DbnarySense
import com.bliss.grid.domain.lexicon.DbnaryWord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IngestDbnaryUseCaseTest {
    @Test
    fun `execute upserts entries without truncating by default`() {
        val repo = RecordingDbnaryRepository()
        val useCase = IngestDbnaryUseCase(repo)

        val report = useCase.execute(sequenceOf(sample("voiture", "noun")), "fr", truncate = false)

        assertThat(repo.deletedLanguages).isEqualTo(emptyList())
        assertThat(repo.upsertedBatches.size).isEqualTo(1)
        assertThat(repo.upsertedBatches[0].map { it.lemma }).containsExactly("voiture")
        assertThat(report.deletedFromTruncate).isEqualTo(0)
        assertThat(report.wordsInserted).isEqualTo(1)
    }

    @Test
    fun `execute truncates before upsert when truncate is true`() {
        val repo = RecordingDbnaryRepository(deleteResult = 7)
        val useCase = IngestDbnaryUseCase(repo)

        val report =
            useCase.execute(sequenceOf(sample("manger", "verb")), "fr", truncate = true)

        assertThat(repo.deletedLanguages).containsExactly("fr")
        assertThat(repo.deleteCallOrder).isEqualTo(0)
        assertThat(repo.upsertCallOrder).isEqualTo(1)
        assertThat(report.deletedFromTruncate).isEqualTo(7)
    }

    @Test
    fun `execute mirrors the repository's UpsertReport into IngestDbnaryReport`() {
        val repo =
            RecordingDbnaryRepository(
                upsertResult =
                    UpsertReport(
                        wordsInserted = 3,
                        wordsUpdated = 2,
                        sensesWritten = 12,
                        synonymsWritten = 8,
                    ),
            )
        val useCase = IngestDbnaryUseCase(repo)

        val report =
            useCase.execute(sequenceOf(sample("a", "noun")), "fr", truncate = false)

        assertThat(report.wordsInserted).isEqualTo(3)
        assertThat(report.wordsUpdated).isEqualTo(2)
        assertThat(report.sensesWritten).isEqualTo(12)
        assertThat(report.synonymsWritten).isEqualTo(8)
    }

    @Test
    fun `execute rejects blank language`() {
        val useCase = IngestDbnaryUseCase(RecordingDbnaryRepository())
        assertThrows<IllegalArgumentException> {
            useCase.execute(emptySequence(), "  ", truncate = false)
        }
    }

    private fun sample(
        lemma: String,
        pos: String,
    ) = DbnaryWord(
        lemma = lemma,
        pos = pos,
        senses = listOf(DbnarySense(0, "Une définition.")),
        synonyms = listOf("syn"),
    )
}

private class RecordingDbnaryRepository(
    private val deleteResult: Int = 0,
    private val upsertResult: UpsertReport =
        UpsertReport(wordsInserted = 1, wordsUpdated = 0, sensesWritten = 1, synonymsWritten = 1),
) : DbnaryRepository {
    val upsertedBatches = mutableListOf<List<DbnaryWord>>()
    val deletedLanguages = mutableListOf<String>()
    var deleteCallOrder = -1
    var upsertCallOrder = -1
    private var nextOrder = 0

    override fun upsertAll(entries: Sequence<DbnaryWord>): UpsertReport {
        upsertCallOrder = nextOrder++
        upsertedBatches += entries.toList()
        return upsertResult
    }

    override fun deleteByLanguage(language: String): Int {
        deleteCallOrder = nextOrder++
        deletedLanguages += language
        return deleteResult
    }

    override fun findOne(
        language: String,
        lemma: String,
        pos: String,
    ): DbnaryWord? = null

    override fun countByLanguage(language: String): Long = 0L
}
