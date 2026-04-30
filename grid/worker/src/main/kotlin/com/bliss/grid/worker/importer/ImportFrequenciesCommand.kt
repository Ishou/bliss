// `bliss-worker import-frequencies` — load <word> <count> file into words.frequency
// and recompute words.difficulty from frequency rank (ADR-0013 §4 sigmoid).
package com.bliss.grid.worker.importer

import com.bliss.grid.application.lexicon.ImportFrequenciesUseCase
import com.bliss.grid.domain.lexicon.WordFrequency
import com.bliss.grid.domain.lexicon.parseFrequencies
import com.bliss.grid.infrastructure.persistence.JdbcWordDifficultyRecomputer
import com.bliss.grid.infrastructure.persistence.JdbcWordFrequencyUpdater
import com.bliss.grid.worker.db.Database
import com.bliss.grid.worker.withCorrelationId
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

class ImportFrequenciesCommand : CliktCommand(name = "import-frequencies") {
    private val log = LoggerFactory.getLogger(ImportFrequenciesCommand::class.java)

    private val input by option("--input", help = "UTF-8 frequency file: '<word> <count>' per line")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val language by option("--language").default("fr")
    private val batchSize by option("--batch-size").int().default(DEFAULT_BATCH_SIZE)

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for import-frequencies")
        executeImport(ds, input)
    }

    internal fun executeImport(
        ds: DataSource,
        path: Path,
    ) {
        withCorrelationId {
            require(batchSize >= 1) { "--batch-size must be >= 1, got $batchSize" }
            log.info("import_frequencies_start input={} language={} batch_size={}", path, language, batchSize)

            val pairs = parse(path)
            log.info("import_frequencies_parsed entries={}", pairs.size)

            val useCase =
                ImportFrequenciesUseCase(
                    updater = JdbcWordFrequencyUpdater(ds, batchSize),
                    recomputer = JdbcWordDifficultyRecomputer(ds),
                )
            val report = useCase.execute(pairs, language)
            log.info("import_frequencies_applied rows_updated={}", report.updated)
            log.info("import_frequencies_difficulty_recomputed rows_updated={}", report.recomputed)
            log.info(
                "import_frequencies_complete entries={} updated={} difficulty_recomputed={}",
                report.entries,
                report.updated,
                report.recomputed,
            )
        }
    }

    /** Test seam — kept here to preserve the existing test surface. */
    internal fun parse(path: Path): List<WordFrequency> =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            parseFrequencies(reader.lineSequence())
        }

    companion object {
        private const val DEFAULT_BATCH_SIZE: Int = 1000
    }
}
