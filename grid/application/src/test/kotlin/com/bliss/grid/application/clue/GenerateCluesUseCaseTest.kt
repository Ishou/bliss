package com.bliss.grid.application.clue

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.bliss.grid.domain.clue.ClueClient
import com.bliss.grid.domain.clue.ClueResult
import com.bliss.grid.domain.clue.ClueSelectionCriteria
import com.bliss.grid.domain.clue.WordCorpusClueWriter
import com.bliss.grid.domain.clue.WordRow
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class GenerateCluesUseCaseTest {
    private val wordId = UUID.randomUUID()
    private val row = WordRow(wordId, "chat", 4)
    private val criteria =
        ClueSelectionCriteria(
            language = "fr",
            includeAlreadyClued = false,
            includeAllLengths = false,
            includeAllForms = false,
            limit = null,
        )

    @Test
    fun `accepted on first attempt writes clue and increments cluesWritten`() {
        val writer = FakeWordCorpusClueWriter(listOf(row))
        val client = FakeClueClient { _, _ -> ClueResult.Accepted("Animal") }
        val useCase = GenerateCluesUseCase(client, writer, concurrency = 1, dryRun = false)

        val report = useCase.execute(criteria)

        assertThat(report.cluesWritten).isEqualTo(1)
        assertThat(report.processed).isEqualTo(1)
        assertThat(report.skippedTooLong).isEqualTo(0)
        assertThat(report.apiErrors).isEqualTo(0)
        assertThat(writer.writeCalls).isEqualTo(listOf(wordId to "Animal"))
    }

    @Test
    fun `too long on first attempt then accepted on retry writes clue and increments cluesWritten`() {
        val writer = FakeWordCorpusClueWriter(listOf(row))
        val client =
            FakeClueClient { _, retry ->
                if (retry) ClueResult.Accepted("Court") else ClueResult.TooLong("X".repeat(100))
            }
        val useCase = GenerateCluesUseCase(client, writer, concurrency = 1, dryRun = false)

        val report = useCase.execute(criteria)

        assertThat(report.cluesWritten).isEqualTo(1)
        assertThat(writer.writeCalls).isEqualTo(listOf(wordId to "Court"))
    }

    @Test
    fun `all attempts too long skips row without writing`() {
        val writer = FakeWordCorpusClueWriter(listOf(row))
        val client = FakeClueClient { _, _ -> ClueResult.TooLong("X".repeat(100)) }
        val useCase = GenerateCluesUseCase(client, writer, concurrency = 1, dryRun = false)

        val report = useCase.execute(criteria)

        assertThat(report.skippedTooLong).isEqualTo(1)
        assertThat(report.cluesWritten).isEqualTo(0)
        assertThat(writer.writeCalls).isEmpty()
    }

    @Test
    fun `api error leaves row unwritten and does not retry`() {
        val wordId2 = UUID.randomUUID()
        val writer = FakeWordCorpusClueWriter(listOf(row, WordRow(wordId2, "arbre", 5)))
        var chatCallCount = 0
        val client =
            FakeClueClient { word, _ ->
                if (word == "chat") {
                    chatCallCount++
                    ClueResult.ApiError(RuntimeException("boom"))
                } else {
                    ClueResult.Accepted("Vegetal")
                }
            }
        val useCase = GenerateCluesUseCase(client, writer, concurrency = 1, dryRun = false)

        val report = useCase.execute(criteria)

        assertThat(report.apiErrors).isEqualTo(1)
        assertThat(report.cluesWritten).isEqualTo(1)
        // ApiError path does not retry: exactly 1 call, not MAX_ATTEMPTS
        assertThat(chatCallCount).isEqualTo(1)
    }

    @Test
    fun `dry run does not call writeClue regardless of accepted outcome`() {
        val writer = FakeWordCorpusClueWriter(listOf(row))
        val client = FakeClueClient { _, _ -> ClueResult.Accepted("OK") }
        val useCase = GenerateCluesUseCase(client, writer, concurrency = 1, dryRun = true)

        useCase.execute(criteria)

        assertThat(writer.writeCalls).isEmpty()
    }
}

private class FakeWordCorpusClueWriter(
    private val rows: List<WordRow>,
) : WordCorpusClueWriter {
    val writeCalls: MutableList<Pair<UUID, String>> = CopyOnWriteArrayList()

    override fun selectRows(criteria: ClueSelectionCriteria): List<WordRow> = rows

    override fun writeClue(
        wordId: UUID,
        clue: String,
    ) {
        writeCalls += wordId to clue
    }
}

private class FakeClueClient(
    private val behavior: (word: String, retry: Boolean) -> ClueResult,
) : ClueClient {
    override suspend fun generateClue(
        word: String,
        retry: Boolean,
    ): ClueResult = behavior(word, retry)
}
