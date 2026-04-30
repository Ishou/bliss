package com.bliss.grid.application.clue

import com.bliss.grid.domain.clue.ClueClient
import com.bliss.grid.domain.clue.ClueResult
import com.bliss.grid.domain.clue.ClueSelectionCriteria
import com.bliss.grid.domain.clue.WordCorpusClueWriter
import com.bliss.grid.domain.clue.WordRow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/** Per-row outcome — drives the structured-log `outcome` field and the end-of-run summary. */
enum class ClueOutcome { ACCEPTED, RETRIED, SKIPPED_TOO_LONG, API_ERROR }

/**
 * `generate-clues` use case (ADR-0013 §5, §7): select corpus rows, fan out clue
 * requests to the [ClueClient] bounded by `concurrency`, retry overlong clues
 * up to [MAX_ATTEMPTS] times, and persist accepted clues via [WordCorpusClueWriter].
 *
 * Returns a [GenerateCluesReport] for the caller to log/render. Concurrency,
 * dry-run, and retry policy live here — the worker just supplies the inputs.
 */
class GenerateCluesUseCase(
    private val clueClient: ClueClient,
    private val writer: WordCorpusClueWriter,
    private val concurrency: Int,
    private val dryRun: Boolean,
) {
    private val log = LoggerFactory.getLogger(GenerateCluesUseCase::class.java)

    init {
        require(concurrency >= 1) { "concurrency must be >= 1, got $concurrency" }
    }

    fun execute(criteria: ClueSelectionCriteria): GenerateCluesReport {
        val rows = writer.selectRows(criteria)
        log.info("generate_clues_selected rows={}", rows.size)
        if (rows.isEmpty() && !criteria.includeAllForms) {
            log.warn(
                "generate_clues_selected_empty language={} — no lemma rows found; " +
                    "if your corpus was imported via import-words (NULL lemma), pass --all-forms",
                criteria.language,
            )
        }

        val processed = AtomicInteger(0)
        val written = AtomicInteger(0)
        val skippedTooLong = AtomicInteger(0)
        val apiErrors = AtomicInteger(0)

        runBlocking(Dispatchers.IO) {
            val gate = Semaphore(concurrency)
            val jobs: List<Deferred<ClueOutcome>> =
                rows.map { row ->
                    async {
                        gate.withPermit {
                            val outcome = processRow(row)
                            when (outcome) {
                                ClueOutcome.ACCEPTED, ClueOutcome.RETRIED -> written.incrementAndGet()
                                ClueOutcome.SKIPPED_TOO_LONG -> skippedTooLong.incrementAndGet()
                                ClueOutcome.API_ERROR -> apiErrors.incrementAndGet()
                            }
                            processed.incrementAndGet()
                            outcome
                        }
                    }
                }
            jobs.awaitAll()
        }

        return GenerateCluesReport(
            processed = processed.get(),
            cluesWritten = written.get(),
            skippedTooLong = skippedTooLong.get(),
            apiErrors = apiErrors.get(),
        )
    }

    private suspend fun processRow(row: WordRow): ClueOutcome {
        var attempt = 0
        var lastTooLong: String? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            when (val result = clueClient.generateClue(row.word, retry = attempt > 1)) {
                is ClueResult.Accepted -> {
                    val outcome = if (attempt == 1) ClueOutcome.ACCEPTED else ClueOutcome.RETRIED
                    if (!dryRun) writer.writeClue(row.wordId, result.clue)
                    log.info(
                        "clue_row word_id={} word=\"{}\" word_length={} attempt={} clue_chars={} clue=\"{}\" outcome={} dry_run={}",
                        row.wordId,
                        row.word,
                        row.length,
                        attempt,
                        result.clue.length,
                        result.clue,
                        outcome.name.lowercase(),
                        dryRun,
                    )
                    return outcome
                }
                is ClueResult.TooLong -> {
                    lastTooLong = result.rejectedClue
                    log.info(
                        "clue_row word_id={} word=\"{}\" word_length={} attempt={} clue_chars={} rejected_clue=\"{}\" outcome=too_long",
                        row.wordId,
                        row.word,
                        row.length,
                        attempt,
                        result.rejectedClue.length,
                        result.rejectedClue,
                    )
                }
                is ClueResult.ApiError -> {
                    // Drop the row — leave clue NULL so the next run picks it up. ADR-0013 §5
                    // is explicit: do not mark "permanently failed".
                    log.warn(
                        "row_api_error word_id={} word_length={} attempt={} cause={}",
                        row.wordId,
                        row.length,
                        attempt,
                        result.cause.message,
                    )
                    return ClueOutcome.API_ERROR
                }
            }
        }
        log.warn(
            "row_skipped_clue_too_long word_id={} word_length={} attempts={} last_clue_chars={}",
            row.wordId,
            row.length,
            MAX_ATTEMPTS,
            lastTooLong?.length ?: -1,
        )
        return ClueOutcome.SKIPPED_TOO_LONG
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 3
    }
}

data class GenerateCluesReport(
    val processed: Int,
    val cluesWritten: Int,
    val skippedTooLong: Int,
    val apiErrors: Int,
)
