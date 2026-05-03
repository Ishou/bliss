// `bliss-worker ingest-clue-candidates` — loads LLM-generated lemma clues
// from a CSV into the `clue_candidates` table.
// Mirrors the ingest-dbnary pattern: Python upstream produces the CSV,
// the worker upserts it into Postgres.
package com.bliss.grid.worker.clues

import com.bliss.grid.application.lexicon.ClueCandidateRepository
import com.bliss.grid.domain.lexicon.ClueCandidate
import com.bliss.grid.infrastructure.persistence.JdbcClueCandidateRepository
import com.bliss.grid.worker.db.Database
import com.bliss.grid.worker.withCorrelationId
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

class IngestClueCandidatesCommand : CliktCommand(name = "ingest-clue-candidates") {
    private val log = LoggerFactory.getLogger(IngestClueCandidatesCommand::class.java)

    private val input by option(
        "--input",
        help = "CSV with columns lemma, clue_text, source; optional model_version, confidence",
    ).path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val language by option("--language").default("fr")
    private val truncate by
        option(
            "--truncate",
            help = "DELETE existing candidates for the given --source before inserting (uses csv's --source value)",
        ).flag()

    /**
     * When set, override the `source` column from the CSV with this value.
     * Useful when the upstream CSV doesn't carry one (e.g. a one-off run
     * tagging everything as `mistral-nemo-v1`).
     */
    private val sourceOverride by
        option(
            "--source",
            help = "Override the CSV's source column (e.g. 'mistral-nemo:latest')",
        )

    /** When set, override `model_version` for every row. */
    private val modelVersionOverride by option("--model-version", help = "Override the CSV's model_version column")

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for ingest-clue-candidates")
        executeIngest(ds, input)
    }

    internal fun executeIngest(
        ds: DataSource,
        path: Path,
    ) {
        withCorrelationId {
            log.info(
                "ingest_clue_candidates_start input={} language={} source_override={} model_version_override={} truncate={}",
                path,
                language,
                sourceOverride ?: "(use CSV)",
                modelVersionOverride ?: "(use CSV)",
                truncate,
            )

            val rawRows = parse(path)
            log.info("ingest_clue_candidates_parsed rows={}", rawRows.size)

            require(sourceOverride != null || rawRows.all { it.source.isNotBlank() }) {
                "CSV must include a non-blank 'source' column, or pass --source to override."
            }

            val repository = JdbcClueCandidateRepository(ds)
            val candidates = resolveCandidates(repository, rawRows)
            val resolved = candidates.size
            val unresolved = rawRows.size - resolved
            log.info(
                "ingest_clue_candidates_resolved resolved={} unresolved={} (lemmas without a matching words row)",
                resolved,
                unresolved,
            )

            if (truncate) {
                val target =
                    sourceOverride ?: run {
                        val sources = candidates.map { it.source }.toSet()
                        require(sources.size <= 1) {
                            "--truncate requires a single source per run; found $sources. " +
                                "Pass --source to force one, or run without --truncate."
                        }
                        sources.firstOrNull() ?: return@withCorrelationId
                    }
                val deleted = repository.deleteBySource(target, language)
                log.info("ingest_clue_candidates_truncated source={} rows_deleted={}", target, deleted)
            }

            val report = repository.upsertAll(candidates.asSequence())
            log.info(
                "ingest_clue_candidates_complete inserted={} updated={}",
                report.inserted,
                report.updated,
            )
        }
    }

    private fun resolveCandidates(
        repository: ClueCandidateRepository,
        rows: List<RawRow>,
    ): List<ClueCandidate> {
        val lemmas = rows.map { it.lemma }.distinct()
        val wordIds = repository.findLemmaWordIds(language, lemmas)
        return rows.mapNotNull { row ->
            val wordId = wordIds[row.lemma] ?: return@mapNotNull null
            ClueCandidate(
                wordId = wordId,
                source = sourceOverride ?: row.source,
                clueText = row.clueText,
                senseIndex = null,
                confidence = row.confidence,
                modelVersion = modelVersionOverride ?: row.modelVersion,
            )
        }
    }

    /** Test seam. */
    internal fun parse(path: Path): List<RawRow> =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            readClueCandidatesCsv(reader)
        }

    /** Pre-resolution row shape; `RawRow.lemma` becomes a `word_id` once joined. */
    internal data class RawRow(
        val lemma: String,
        val clueText: String,
        val source: String,
        val modelVersion: String?,
        val confidence: Double?,
    )
}

/**
 * Parse a clue-candidates CSV into [IngestClueCandidatesCommand.RawRow] records.
 *
 * Required header columns: `lemma`, `clue_text`. Either `source` is present in
 * the header, or the caller passes `--source <value>` to override every row.
 *
 * Optional columns: `model_version`, `confidence`.
 *
 * Rows with blank `lemma` or blank `clue_text` are skipped silently — the CSV
 * is allowed to ship validation_flag != 'ok' rows that the downstream filter
 * dropped.
 */
internal fun readClueCandidatesCsv(reader: Reader): List<IngestClueCandidatesCommand.RawRow> {
    val format =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(true)
            .build()
    return format.parse(reader).use { parser ->
        val knownHeaders = parser.headerNames.toSet()
        val out = mutableListOf<IngestClueCandidatesCommand.RawRow>()
        for (record in parser) {
            val lemma = optionalCol("lemma", record, knownHeaders)?.lowercase().orEmpty()
            val clueText = optionalCol("clue_text", record, knownHeaders).orEmpty()
            if (lemma.isBlank() || clueText.isBlank()) continue
            val source = optionalCol("source", record, knownHeaders).orEmpty()
            // `source` is allowed to be blank if the caller passes --source;
            // we leave it blank here and the command layer overrides.
            out.add(
                IngestClueCandidatesCommand.RawRow(
                    lemma = lemma,
                    clueText = clueText,
                    source = source,
                    modelVersion = optionalCol("model_version", record, knownHeaders)?.takeIf { it.isNotBlank() },
                    confidence =
                        optionalCol("confidence", record, knownHeaders)
                            ?.takeIf { it.isNotBlank() }
                            ?.toDoubleOrNull(),
                ),
            )
        }
        out
    }
}

private fun optionalCol(
    column: String,
    record: CSVRecord,
    knownHeaders: Set<String>,
): String? = if (column in knownHeaders) record.get(column).trim().ifBlank { null } else null
