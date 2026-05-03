// `bliss-worker export-words` — DB → CSV exporter (ADR-0013 §7, §8).
package com.bliss.grid.worker.exporter

import com.bliss.grid.application.lexicon.ExportWordsUseCase
import com.bliss.grid.domain.lexicon.ExportSelectionCriteria
import com.bliss.grid.domain.lexicon.PercentileLengthFilterConfig
import com.bliss.grid.infrastructure.persistence.CsvWordCorpusExportSink
import com.bliss.grid.infrastructure.persistence.FileCuratedSourceReader
import com.bliss.grid.infrastructure.persistence.JdbcWordCorpusReader
import com.bliss.grid.worker.db.Database
import com.bliss.grid.worker.withCorrelationId
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import org.slf4j.LoggerFactory
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Exports the locally-reviewed `words` table to a committed CSV (ADR-0013 §8).
 *
 * The CSV is the production source of truth for `grid-api`; the DB is a
 * local-dev scratch space for the `import-words` → `generate-clues` →
 * review → `export-words` pipeline.
 */
class ExportWordsCommand(
    /**
     * Per-length percentile filter applied at export time. Production wiring
     * uses [DEFAULT_PERCENTILE_CONFIG]; tests inject a passthrough config to
     * keep fixture rows intact.
     */
    private val percentileConfig: PercentileLengthFilterConfig = DEFAULT_PERCENTILE_CONFIG,
) : CliktCommand(name = "export-words") {
    private val log = LoggerFactory.getLogger(ExportWordsCommand::class.java)

    private val language by option("--language", help = "ISO language code").default("fr")

    /**
     * Default targets the api module's classpath resource. The path is
     * computed at run time using the resolved `--language` value, so
     * `--language en` writes `words-en.csv` next to `words-fr.csv`.
     * Override with `--output` for ad-hoc exports.
     */
    private val output by option("--output", help = "Destination CSV path")
        .path(canBeDir = false)

    private val includeClueless by option(
        "--include-clueless",
        help = "Include rows whose clue is still NULL (default: clued rows only)",
    ).flag()

    private val placeholderClueFromWord by option(
        "--placeholder-clue-from-word",
        help = "When clue is NULL, emit the word as its own clue (transitional)",
    ).flag()

    /** Override the curated-source root directory; default is repo `data/curated`. */
    private val curatedDir by option("--curated-dir", help = "Root dir holding <language>.csv curated files")
        .path(canBeFile = false)

    /**
     * Source priority for the clue_candidates overlay. Comma-separated; first
     * matching source wins. Empty disables the overlay (legacy behaviour).
     */
    private val candidatePriorityRaw by option(
        "--candidate-priority",
        help = "Comma-separated priority list of clue_candidates sources (e.g. 'curated,dbnary-synonym')",
    ).default(DEFAULT_CANDIDATE_PRIORITY)

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for export-words")
        executeExport(ds, output ?: defaultOutputPath(language), curatedDir ?: DEFAULT_CURATED_DIR)
    }

    /** Test seam: integration test wires its own [DataSource], output, and curated dir. */
    internal fun executeExport(
        ds: DataSource,
        outputPath: Path,
        curatedRoot: Path,
    ) {
        withCorrelationId {
            val priority = parseCandidatePriority(candidatePriorityRaw)
            log.info(
                "export_words_start language={} include_clueless={} placeholder_clue_from_word={} " +
                    "candidate_priority={} output={} curated_dir={}",
                language,
                includeClueless,
                placeholderClueFromWord,
                priority,
                outputPath,
                curatedRoot,
            )
            val report =
                ExportWordsUseCase(
                    reader = JdbcWordCorpusReader(ds),
                    sink = CsvWordCorpusExportSink(outputPath),
                    curatedReader = FileCuratedSourceReader(curatedRoot),
                    percentileConfig = percentileConfig,
                ).execute(
                    ExportSelectionCriteria(
                        language = language,
                        includeClueless = includeClueless,
                        placeholderClueFromWord = placeholderClueFromWord,
                        candidateSourcePriority = priority,
                    ),
                )
            log.info(
                "export_words_complete language={} rows_written={} output={}",
                language,
                report.rowsWritten,
                outputPath,
            )
        }
    }

    private fun parseCandidatePriority(raw: String): List<String> = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }

    companion object {
        private fun defaultOutputPath(language: String): Path = Path.of("grid/api/src/main/resources/words/words-$language.csv")

        private val DEFAULT_CURATED_DIR: Path = Path.of("data/curated")

        // Default priority: curated wins over DBnary-derived synonyms; lower
        // tiers (model-generated, legacy) are added at the user's discretion
        // via --candidate-priority. ADR-0024 sets `dbnary-synonym` below LLM
        // sources by default — this is the LLM-free baseline; once Phase 3
        // ships, `--candidate-priority "curated,mistral-nemo,dbnary-synonym"`.
        private const val DEFAULT_CANDIDATE_PRIORITY: String = "curated,dbnary-synonym"

        // Length-2 grammalecte rows are dominated by junk (`ck, cp, mv, qi, …`) that
        // looks frequent in raw text but isn't crossword-valid; rely on the curated
        // source instead. Length-3 keeps the top 40 % by frequency, length 4+ keeps
        // the top half (median). Tune in PRs after measuring grid quality.
        private val DEFAULT_PERCENTILE_CONFIG: PercentileLengthFilterConfig =
            PercentileLengthFilterConfig(
                keepRatioByLength = mapOf(2 to 0.0, 3 to 0.4),
                defaultKeepRatio = 0.5,
            )
    }
}
