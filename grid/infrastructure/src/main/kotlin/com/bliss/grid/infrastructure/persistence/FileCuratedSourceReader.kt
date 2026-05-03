package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.CURATED_LICENSE
import com.bliss.grid.domain.lexicon.CURATED_SOURCE
import com.bliss.grid.domain.lexicon.CuratedSourceReader
import com.bliss.grid.domain.lexicon.ExportRow
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads curated rows from every `*.csv` file in [rootDir] (e.g. `fr.csv`,
 * `roman2.csv`), each sharing the schema of the exported `words-<lang>.csv`
 * (header `word,language,length,frequency,difficulty,clue,source,source_license`,
 * with an optional `lemma` column that defaults to the word itself when absent
 * or blank — useful for inflected forms whose dictionary headword differs,
 * e.g. `va,fr,…,aller`).
 *
 * Files are filtered by their `language` column, so a single CSV may carry
 * multiple languages and a single language may be split across multiple files
 * (e.g. one hand-curated, one script-generated). Returns an empty sequence when
 * no files match. Length is recomputed from the word so a copy-paste mistake in
 * the `length` column cannot mis-bucket a row downstream. Source and license
 * are forced to the curated markers regardless of what is written in the file,
 * ensuring [PercentileLengthFilter] recognises them as bypass-eligible.
 */
class FileCuratedSourceReader(
    private val rootDir: Path,
) : CuratedSourceReader {
    override fun rows(language: String): Sequence<ExportRow> {
        if (!Files.isDirectory(rootDir)) return emptySequence()
        val files =
            Files.newDirectoryStream(rootDir, "*.csv").use { stream ->
                stream.sortedBy { it.fileName.toString() }
            }
        return files.asSequence().flatMap { file -> rowsFrom(file, language) }
    }

    private fun rowsFrom(
        file: Path,
        language: String,
    ): Sequence<ExportRow> {
        val records =
            Files.newInputStream(file).use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    CSVParser.parse(reader, CSV_FORMAT).use { parser ->
                        parser.records.toList()
                    }
                }
            }
        return records
            .asSequence()
            .map { rec ->
                val word = rec.get("word").trim()
                require(word.isNotEmpty()) { "curated row has empty word in $file (line ${rec.recordNumber + 1})" }
                val clue = rec.get("clue").trim()
                require(clue.isNotEmpty()) {
                    "curated row '$word' has blank clue in $file (line ${rec.recordNumber + 1})"
                }
                ExportRow(
                    word = word,
                    language = rec.get("language").trim().ifEmpty { language },
                    length = word.length,
                    frequency = rec.get("frequency").trim().toLongOrNull(),
                    difficulty = rec.get("difficulty").trim().toFloatOrNull(),
                    clue = clue,
                    source = CURATED_SOURCE,
                    sourceLicense = CURATED_LICENSE,
                    lemma = optionalColumn(rec, "lemma")?.takeIf { it.isNotEmpty() } ?: word,
                )
            }.filter { it.language == language }
    }

    private fun optionalColumn(
        rec: org.apache.commons.csv.CSVRecord,
        name: String,
    ): String? = if (rec.isMapped(name)) rec.get(name).trim() else null

    companion object {
        private val CSV_FORMAT: CSVFormat =
            CSVFormat.RFC4180
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
    }
}
