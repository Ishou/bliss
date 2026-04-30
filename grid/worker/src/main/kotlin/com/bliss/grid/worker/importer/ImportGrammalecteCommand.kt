// `bliss-worker import-grammalecte` — load Grammalecte's `lexique-grammalecte-fr-v7.7.txt`
// into the words table, populating word + lemma + frequency in a single pass.
// Replaces the unmunch-based `import-words` flow for languages that have a Grammalecte lexicon.
package com.bliss.grid.worker.importer

import com.bliss.grid.application.lexicon.ImportGrammalecteUseCase
import com.bliss.grid.domain.lexicon.GrammalecteEntry
import com.bliss.grid.domain.lexicon.parseGrammalecteLexique
import com.bliss.grid.infrastructure.persistence.JdbcGrammalecteCorpusWriter
import com.bliss.grid.infrastructure.persistence.JdbcWordDifficultyRecomputer
import com.bliss.grid.worker.db.Database
import com.bliss.grid.worker.withCorrelationId
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

class ImportGrammalecteCommand : CliktCommand(name = "import-grammalecte") {
    private val log = LoggerFactory.getLogger(ImportGrammalecteCommand::class.java)

    private val input by option("--input", help = "Tab-separated lexique-grammalecte-fr-vX.txt")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val language by option("--language").default("fr")
    private val source by option("--source").default("grammalecte-fr-v7.7")
    private val sourceLicense by option("--source-license").default("MPL-2.0")
    private val batchSize by option("--batch-size").int().default(DEFAULT_BATCH_SIZE)
    private val truncate by option("--truncate", help = "DELETE existing rows for the language before import").flag()

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for import-grammalecte")
        executeImport(ds, input)
    }

    internal fun executeImport(
        ds: DataSource,
        path: Path,
    ) {
        withCorrelationId {
            require(batchSize >= 1) { "--batch-size must be >= 1, got $batchSize" }
            log.info(
                "import_grammalecte_start input={} language={} source={} truncate={} batch_size={}",
                path,
                language,
                source,
                truncate,
                batchSize,
            )

            val rows = parse(path)
            log.info("import_grammalecte_parsed unique_forms={}", rows.size)

            val useCase =
                ImportGrammalecteUseCase(
                    writer = JdbcGrammalecteCorpusWriter(ds, batchSize),
                    recomputer = JdbcWordDifficultyRecomputer(ds),
                )
            val report = useCase.execute(rows, language, source, sourceLicense, truncate)

            if (truncate) log.info("import_grammalecte_truncated rows_deleted={}", report.deleted)
            log.info("import_grammalecte_inserted rows_inserted={}", report.inserted)
            log.info("import_grammalecte_difficulty_recomputed rows_updated={}", report.recomputed)
            log.info(
                "import_grammalecte_complete unique_forms={} inserted={} difficulty_recomputed={}",
                report.uniqueForms,
                report.inserted,
                report.recomputed,
            )
        }
    }

    /** Test seam — kept here so existing tests can call `cmd.parse(file)`. */
    internal fun parse(path: Path): Map<String, GrammalecteEntry> =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            parseGrammalecteLexique(reader.lineSequence())
        }

    companion object {
        private const val DEFAULT_BATCH_SIZE: Int = 2000
    }
}
