package com.bliss.grid.application.lexicon

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.lexicon.ClueCandidate
import com.bliss.grid.domain.lexicon.ClueSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class DeriveSynonymCluesUseCaseTest {
    @Test
    fun `execute derives without truncating by default`() {
        val repo = RecordingRepository(deriveResult = 12)
        val useCase = DeriveSynonymCluesUseCase(repo)

        val report = useCase.execute("fr", truncate = false)

        assertThat(repo.deletedSources).isEqualTo(emptyList())
        assertThat(repo.derivedLanguages).containsExactly("fr")
        assertThat(report.deleted).isEqualTo(0)
        assertThat(report.inserted).isEqualTo(12)
    }

    @Test
    fun `execute truncates dbnary-synonym then derives when truncate is true`() {
        val repo = RecordingRepository(deleteResult = 8, deriveResult = 12)
        val useCase = DeriveSynonymCluesUseCase(repo)

        val report = useCase.execute("fr", truncate = true)

        assertThat(repo.deletedSources).containsExactly(ClueSource.DBNARY_SYNONYM to "fr")
        assertThat(repo.deleteCallOrder).isEqualTo(0)
        assertThat(repo.deriveCallOrder).isEqualTo(1)
        assertThat(report.deleted).isEqualTo(8)
        assertThat(report.inserted).isEqualTo(12)
    }

    @Test
    fun `execute rejects blank language`() {
        val useCase = DeriveSynonymCluesUseCase(RecordingRepository())
        assertThrows<IllegalArgumentException> { useCase.execute("  ", truncate = false) }
    }
}

private class RecordingRepository(
    private val deleteResult: Int = 0,
    private val deriveResult: Int = 0,
) : ClueCandidateRepository {
    val deletedSources = mutableListOf<Pair<String, String?>>()
    val derivedLanguages = mutableListOf<String>()
    var deleteCallOrder = -1
    var deriveCallOrder = -1
    private var nextOrder = 0

    override fun upsertAll(candidates: Sequence<ClueCandidate>): UpsertCandidatesReport = UpsertCandidatesReport(0, 0)

    override fun deleteBySource(
        source: String,
        language: String?,
    ): Int {
        deleteCallOrder = nextOrder++
        deletedSources += source to language
        return deleteResult
    }

    override fun findByWord(wordId: UUID): List<ClueCandidate> = emptyList()

    override fun findTopBySourcePriority(
        wordId: UUID,
        sourcePriority: List<String>,
    ): ClueCandidate? = null

    override fun countBySource(source: String): Long = 0L

    override fun deriveSynonymClues(language: String): Int {
        deriveCallOrder = nextOrder++
        derivedLanguages += language
        return deriveResult
    }
}
