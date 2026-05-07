// `bliss-worker ingest-dbnary` — load a DBnary CSV (produced offline by
// scripts/eval/fetch_dbnary_for_sample.py) into the dbnary_words / senses /
// synonyms scratch tables (ADR-0023).
//
// CC BY-SA constraint reminder (ADR-0023): the populated tables stay
// local-dev / offline-pipeline scratch space. They feed `derive-synonym-clues`
// and `ingest-clue-candidates` but no DBnary text reaches the API or the exported CSV.
package com.bliss.grid.worker.dbnary

import com.bliss.grid.application.lexicon.IngestDbnaryUseCase
import com.bliss.grid.domain.lexicon.DbnarySense
import com.bliss.grid.domain.lexicon.DbnaryWord
import com.bliss.grid.infrastructure.persistence.JdbcDbnaryRepository
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

class IngestDbnaryCommand : CliktCommand(name = "ingest-dbnary") {
    private val log = LoggerFactory.getLogger(IngestDbnaryCommand::class.java)

    private val input by option(
        "--input",
        help = "DBnary CSV with columns lemma, pos, language, definition (pipe-delimited senses), synonyms",
    ).path(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    private val language by option("--language").default("fr")
    private val truncate by
        option(
            "--truncate",
            help = "DELETE every dbnary_words row (and cascade) for the language before ingest",
        ).flag()

    override fun run() {
        Database.start()
        val ds = Database.dataSource() ?: error("DATABASE_URL is required for ingest-dbnary")
        executeIngest(ds, input)
    }

    internal fun executeIngest(
        ds: DataSource,
        path: Path,
    ) {
        withCorrelationId {
            log.info(
                "ingest_dbnary_start input={} language={} truncate={}",
                path,
                language,
                truncate,
            )
            val useCase = IngestDbnaryUseCase(JdbcDbnaryRepository(ds))
            val entries = parse(path)
            log.info("ingest_dbnary_parsed unique_entries={}", entries.size)

            val report = useCase.execute(entries.asSequence(), language, truncate)

            if (truncate) {
                log.info("ingest_dbnary_truncated rows_deleted={}", report.deletedFromTruncate)
            }
            log.info(
                "ingest_dbnary_complete words_inserted={} words_updated={} senses_written={} synonyms_written={}",
                report.wordsInserted,
                report.wordsUpdated,
                report.sensesWritten,
                report.synonymsWritten,
            )
        }
    }

    /** Test seam — kept here so the integration test can call `cmd.parse(file)`. */
    internal fun parse(path: Path): List<DbnaryWord> =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            readDbnaryCsv(reader)
        }
}

/**
 * Parse a DBnary CSV into [DbnaryWord] records.
 *
 * Required header columns: `lemma`, `pos`. Optional: `language` (defaults to
 * `"fr"`), `definition` (pipe-delimited multi-sense), `synonyms` (pipe-
 * delimited). Extra columns are ignored — the output of
 * `scripts/eval/fetch_dbnary_for_sample.py` carries `word`, `length`,
 * `frequency` etc. which are irrelevant here.
 *
 * Dedupes by `(language, lemma, pos)`; the first row wins. Rows with blank
 * lemma or blank pos are skipped silently — the CSV is allowed to be a
 * superset of the lexicon (e.g. surface-form rows the eval pipeline kept
 * without a clean DBnary entry).
 */
internal fun readDbnaryCsv(reader: Reader): List<DbnaryWord> {
    val format: CSVFormat =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(true)
            .build()
    return format.parse(reader).use { parser ->
        val seen = mutableSetOf<Triple<String, String, String>>()
        val out = mutableListOf<DbnaryWord>()
        val knownHeaders = parser.headerNames.toSet()
        for (record in parser) {
            val lemma =
                if ("lemma" in knownHeaders) record.get("lemma").trim().lowercase() else ""
            val pos = if ("pos" in knownHeaders) record.get("pos").trim() else ""
            if (lemma.isBlank() || pos.isBlank()) continue
            val language =
                if ("language" in knownHeaders) {
                    record
                        .get("language")
                        .trim()
                        .lowercase()
                        .ifBlank { "fr" }
                } else {
                    "fr"
                }
            val key = Triple(language, lemma, pos)
            if (!seen.add(key)) continue

            val senses =
                parsePipeList("definition", record, knownHeaders)
                    .mapIndexed { idx, text -> DbnarySense(senseIndex = idx, definitionText = text) }
            val synonyms = parsePipeList("synonyms", record, knownHeaders).distinct()
            out.add(
                DbnaryWord(
                    lemma = lemma,
                    pos = pos,
                    language = language,
                    senses = senses,
                    synonyms = synonyms,
                ),
            )
        }
        out
    }
}

private fun parsePipeList(
    column: String,
    record: CSVRecord,
    knownHeaders: Set<String>,
): List<String> {
    if (column !in knownHeaders) return emptyList()
    val raw = record.get(column)
    if (raw.isNullOrBlank()) return emptyList()
    return raw
        .split("|")
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
}
