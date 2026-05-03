// `bliss-worker derive-synonym-clues` — populate `clue_candidates` from the
// DBnary synonym graph via the SQL derivation in
// `JdbcClueCandidateRepository#deriveSynonymClues`.
//
// Prerequisites in the same database:
// - `words` table populated (import-grammalecte / import-frequencies / ...).
// - `dbnary_words/senses/synonyms` populated (ingest-dbnary).
//
// CC BY-SA reminder (ADR-0023, ADR-0024): each row inserted here carries a
// CLUE STRING (the synonym lemma capitalised), not raw DBnary text. The
// invariant — DBnary text never reaches the API or the exported CSV — holds
// because clue_candidates feeds export-time picking only, never the product
// surface directly.
package com.bliss.grid.worker.clues

import com.bliss.grid.application.lexicon.DeriveSynonymCluesUseCase
import com.bliss.grid.infrastructure.persistence.JdbcClueCandidateRepository
import com.bliss.grid.worker.db.Database
import com.bliss.grid.worker.withCorrelationId
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.LoggerFactory
import javax.sql.DataSource

class DeriveSynonymCluesCommand : CliktCommand(name = "derive-synonym-clues") {
    private val log = LoggerFactory.getLogger(DeriveSynonymCluesCommand::class.java)

    private val language by option("--language").default("fr")
    private val truncate by
        option(
            "--truncate",
            help = "DELETE existing dbnary-synonym candidates for the language before deriving",
        ).flag()

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for derive-synonym-clues")
        executeDerive(ds)
    }

    internal fun executeDerive(ds: DataSource) {
        withCorrelationId {
            log.info(
                "derive_synonym_clues_start language={} truncate={}",
                language,
                truncate,
            )
            val useCase = DeriveSynonymCluesUseCase(JdbcClueCandidateRepository(ds))
            val report = useCase.execute(language, truncate)

            if (truncate) {
                log.info("derive_synonym_clues_truncated rows_deleted={}", report.deleted)
            }
            log.info("derive_synonym_clues_complete rows_inserted={}", report.inserted)
        }
    }
}
