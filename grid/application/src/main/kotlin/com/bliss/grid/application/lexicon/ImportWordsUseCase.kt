package com.bliss.grid.application.lexicon

import com.bliss.grid.domain.lexicon.InsertReport
import com.bliss.grid.domain.lexicon.SurfaceFormRecord
import com.bliss.grid.domain.lexicon.WordCorpusWriter
import com.bliss.grid.domain.lexicon.difficulty
import com.bliss.grid.domain.lexicon.filterAndSort

/**
 * `import-words` use case: filter and sort raw surface forms (Hunspell-fr et al.),
 * compute per-row difficulty, and hand the records to the [WordCorpusWriter] adapter
 * (ADR-0013 §2, §4).
 */
class ImportWordsUseCase(
    private val writer: WordCorpusWriter,
) {
    fun execute(
        rawLines: List<String>,
        language: String,
        source: String,
        sourceLicense: String,
    ): ImportReport {
        val kept = filterAndSort(rawLines.asSequence())
        val records =
            kept.asSequence().mapIndexed { index, word ->
                SurfaceFormRecord(
                    word = word,
                    language = language,
                    difficulty = difficulty(rank = index + 1, length = word.length),
                    source = source,
                    sourceLicense = sourceLicense,
                )
            }
        val insertReport = writer.insertSurfaceForms(records)
        return ImportReport(
            totalRead = rawLines.size,
            kept = kept.size,
            insert = insertReport,
        )
    }
}

data class ImportReport(
    val totalRead: Int,
    val kept: Int,
    val insert: InsertReport,
)
