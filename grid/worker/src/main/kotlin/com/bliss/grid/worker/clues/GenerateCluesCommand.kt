// `bliss-worker generate-clues` — Anthropic-backed clue generator (ADR-0013 §5, §7).
package com.bliss.grid.worker.clues

import com.bliss.grid.worker.db.Database
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Per-row outcome — drives the structured-log `outcome` field and the end-of-run summary.
 * Surfaced as `internal` for the unit test (test code in the same module).
 */
internal enum class RowOutcome { ACCEPTED, RETRIED, SKIPPED_TOO_LONG, API_ERROR }

internal data class WordRow(
    val wordId: UUID,
    val word: String,
    val length: Int,
)

internal class GenerateCluesCommand(
    /** Test seam: production wiring uses [ClueClientFactory]; tests inject a fake. */
    private val clientFactory: () -> ClueClient = { ClueClientFactory.fromEnv() },
) : CliktCommand(name = "generate-clues") {
    private val log = LoggerFactory.getLogger(GenerateCluesCommand::class.java)

    private val language by option("--language", help = "ISO language code").default("fr")
    private val allLengths by option("--all-lengths", help = "Drop the [2,9] length filter (ADR-0013 §7)").flag()
    private val force by option("--force", help = "Re-clue rows whose clue is already populated").flag()
    private val concurrency by option("--concurrency", help = "Max in-flight Anthropic calls").int().default(DEFAULT_CONCURRENCY)
    private val limit by option("--limit", help = "Cap rows processed in this run").int()
    private val dryRun by option("--dry-run", help = "Run selector + Claude calls but skip UPDATEs").flag()

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for generate-clues")
        executeGenerate(ds, clientFactory())
    }

    /** Test seam: integration test wires its own [DataSource] and [ClueClient]. */
    internal fun executeGenerate(
        ds: DataSource,
        client: ClueClient,
    ) {
        MDC.put("correlation_id", UUID.randomUUID().toString())
        try {
            require(concurrency >= 1) { "--concurrency must be >= 1, got $concurrency" }
            limit?.let { require(it >= 1) { "--limit must be >= 1, got $it" } }
            if (force) {
                log.warn("generate_clues_force_enabled language={} — re-cluing rows with non-null clue", language)
            }
            val started = System.currentTimeMillis()
            log.info(
                "generate_clues_start language={} all_lengths={} force={} concurrency={} limit={} dry_run={}",
                language,
                allLengths,
                force,
                concurrency,
                limit,
                dryRun,
            )

            val rows = selectRows(ds)
            log.info("generate_clues_selected rows={}", rows.size)

            val processed = AtomicInteger(0)
            val written = AtomicInteger(0)
            val skippedTooLong = AtomicInteger(0)
            val apiErrors = AtomicInteger(0)

            runBlocking(Dispatchers.IO) {
                val gate = Semaphore(concurrency)
                val jobs: List<Deferred<RowOutcome>> =
                    rows.map { row ->
                        async {
                            gate.withPermit {
                                val outcome = processRow(ds, client, row)
                                when (outcome) {
                                    RowOutcome.ACCEPTED, RowOutcome.RETRIED -> written.incrementAndGet()
                                    RowOutcome.SKIPPED_TOO_LONG -> skippedTooLong.incrementAndGet()
                                    RowOutcome.API_ERROR -> apiErrors.incrementAndGet()
                                }
                                processed.incrementAndGet()
                                outcome
                            }
                        }
                    }
                jobs.awaitAll()
            }

            log.info(
                "generate_clues_complete rows_processed={} clues_written={} rows_skipped_too_long={} rows_api_error={} wall_clock_ms={}",
                processed.get(),
                written.get(),
                skippedTooLong.get(),
                apiErrors.get(),
                System.currentTimeMillis() - started,
            )
        } finally {
            MDC.remove("correlation_id")
        }
    }

    private fun selectRows(ds: DataSource): List<WordRow> {
        val sb = StringBuilder("SELECT word_id, word, length FROM words WHERE language = ?")
        if (!force) sb.append(" AND clue IS NULL")
        if (!allLengths) sb.append(" AND length BETWEEN 2 AND 9")
        // Stable order keeps re-runs predictable when --limit is in play (smoke testing).
        sb.append(" ORDER BY word_id")
        limit?.let { sb.append(" LIMIT ").append(it) }

        return ds.connection.use { conn ->
            conn.prepareStatement(sb.toString()).use { stmt ->
                stmt.setString(1, language)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                WordRow(
                                    wordId = rs.getObject("word_id", UUID::class.java),
                                    word = rs.getString("word"),
                                    length = rs.getInt("length"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun processRow(
        ds: DataSource,
        client: ClueClient,
        row: WordRow,
    ): RowOutcome {
        var attempt = 0
        var lastTooLong: String? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            when (val result = client.generateClue(row.word, retry = attempt > 1)) {
                is ClueResult.Accepted -> {
                    val outcome = if (attempt == 1) RowOutcome.ACCEPTED else RowOutcome.RETRIED
                    if (!dryRun) writeClue(ds, row.wordId, result.clue)
                    log.info(
                        "clue_row word_id={} word_length={} attempt={} clue_chars={} outcome={} dry_run={}",
                        row.wordId,
                        row.length,
                        attempt,
                        result.clue.length,
                        outcome.name.lowercase(),
                        dryRun,
                    )
                    return outcome
                }
                is ClueResult.TooLong -> {
                    lastTooLong = result.rejectedClue
                    log.info(
                        "clue_row word_id={} word_length={} attempt={} clue_chars={} outcome=too_long",
                        row.wordId,
                        row.length,
                        attempt,
                        result.rejectedClue.length,
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
                    return RowOutcome.API_ERROR
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
        return RowOutcome.SKIPPED_TOO_LONG
    }

    private fun writeClue(
        ds: DataSource,
        wordId: UUID,
        clue: String,
    ) {
        ds.connection.use { conn ->
            conn.prepareStatement(UPDATE_SQL).use { stmt ->
                stmt.setString(1, clue)
                stmt.setObject(2, wordId)
                stmt.executeUpdate()
            }
        }
    }

    companion object {
        private const val DEFAULT_CONCURRENCY: Int = 5
        private const val UPDATE_SQL: String = "UPDATE words SET clue = ? WHERE word_id = ?"
    }
}
