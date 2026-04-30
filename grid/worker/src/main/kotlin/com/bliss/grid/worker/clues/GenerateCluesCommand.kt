// `bliss-worker generate-clues` — Anthropic-backed clue generator (ADR-0013 §5, §7).
package com.bliss.grid.worker.clues

import com.bliss.grid.application.clue.GenerateCluesUseCase
import com.bliss.grid.domain.clue.ClueClient
import com.bliss.grid.domain.clue.ClueSelectionCriteria
import com.bliss.grid.infrastructure.persistence.JdbcWordCorpusClueWriter
import com.bliss.grid.worker.db.Database
import com.bliss.grid.worker.withCorrelationId
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import org.slf4j.LoggerFactory
import javax.sql.DataSource

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

    /**
     * When true (default), only target rows where `word = lemma` — clueing the lemma is sufficient
     * because export-words propagates the lemma's clue to every inflected form. Pass
     * `--all-forms` to clue every form individually (legacy behaviour, ~10× more API calls).
     */
    private val allForms by option(
        "--all-forms",
        help = "Generate one clue per surface form (default: lemmas only, propagated at export)",
    ).flag()

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
        withCorrelationId {
            require(concurrency >= 1) { "--concurrency must be >= 1, got $concurrency" }
            limit?.let { require(it >= 1) { "--limit must be >= 1, got $it" } }
            if (force) {
                log.warn("generate_clues_force_enabled language={} — re-cluing rows with non-null clue", language)
            }
            val started = System.currentTimeMillis()
            log.info(
                "generate_clues_start language={} all_lengths={} all_forms={} force={} concurrency={} limit={} dry_run={}",
                language,
                allLengths,
                allForms,
                force,
                concurrency,
                limit,
                dryRun,
            )

            val criteria =
                ClueSelectionCriteria(
                    language = language,
                    includeAlreadyClued = force,
                    includeAllLengths = allLengths,
                    includeAllForms = allForms,
                    limit = limit,
                )
            val report =
                GenerateCluesUseCase(
                    clueClient = client,
                    writer = JdbcWordCorpusClueWriter(ds),
                    concurrency = concurrency,
                    dryRun = dryRun,
                ).execute(criteria)

            log.info(
                "generate_clues_complete rows_processed={} clues_written={} rows_skipped_too_long={} rows_api_error={} wall_clock_ms={}",
                report.processed,
                report.cluesWritten,
                report.skippedTooLong,
                report.apiErrors,
                System.currentTimeMillis() - started,
            )
        }
    }

    companion object {
        private const val DEFAULT_CONCURRENCY: Int = 5
    }
}
