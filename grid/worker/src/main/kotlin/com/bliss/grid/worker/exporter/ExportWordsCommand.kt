// `bliss-worker export-words` — DB → CSV exporter (ADR-0013 §7, §8).
package com.bliss.grid.worker.exporter

import com.bliss.grid.application.lexicon.ExportWordsUseCase
import com.bliss.grid.domain.lexicon.ExportSelectionCriteria
import com.bliss.grid.infrastructure.persistence.CsvWordCorpusExportSink
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
class ExportWordsCommand : CliktCommand(name = "export-words") {
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

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for export-words")
        executeExport(ds, output ?: defaultOutputPath(language))
    }

    /** Test seam: integration test wires its own [DataSource] and output path. */
    internal fun executeExport(
        ds: DataSource,
        outputPath: Path,
    ) {
        withCorrelationId {
            log.info(
                "export_words_start language={} include_clueless={} placeholder_clue_from_word={} output={}",
                language,
                includeClueless,
                placeholderClueFromWord,
                outputPath,
            )
            val report =
                ExportWordsUseCase(
                    reader = JdbcWordCorpusReader(ds),
                    sink = CsvWordCorpusExportSink(outputPath),
                ).execute(
                    ExportSelectionCriteria(
                        language = language,
                        includeClueless = includeClueless,
                        placeholderClueFromWord = placeholderClueFromWord,
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

    companion object {
        private fun defaultOutputPath(language: String): Path = Path.of("grid/api/src/main/resources/words/words-$language.csv")
    }
}
