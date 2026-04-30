package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.domain.lexicon.ExportRow
import com.bliss.grid.domain.lexicon.WordCorpusExportSink
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * RFC 4180 sink — writes [ExportRow]s as a header-prefixed CSV to [outputPath].
 *
 * `\n` line terminator (Unix; matches git/JVM convention) avoids mixed line
 * endings when the CSV is hand-edited on macOS/Linux. RFC 4180 permits CRLF,
 * but the in-repo dataset standardises on LF.
 */
class CsvWordCorpusExportSink(
    private val outputPath: Path,
) : WordCorpusExportSink {
    override fun write(rows: Sequence<ExportRow>): Int {
        outputPath.parent?.let { Files.createDirectories(it) }
        var count = 0
        Files.newOutputStream(outputPath).use { out ->
            OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                CSVPrinter(writer, csvFormat()).use { printer ->
                    for (row in rows) {
                        printer.printRecord(
                            row.word,
                            row.language,
                            row.length,
                            row.frequency?.toString() ?: "",
                            row.difficulty?.toString() ?: "",
                            row.clue,
                            row.source,
                            row.sourceLicense,
                        )
                        count++
                    }
                }
            }
        }
        return count
    }

    companion object {
        private val HEADER =
            arrayOf("word", "language", "length", "frequency", "difficulty", "clue", "source", "source_license")

        private fun csvFormat(): CSVFormat =
            CSVFormat.RFC4180
                .builder()
                .setHeader(*HEADER)
                .setRecordSeparator("\n")
                .build()
    }
}
