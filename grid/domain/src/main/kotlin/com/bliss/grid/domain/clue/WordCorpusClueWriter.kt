package com.bliss.grid.domain.clue

import java.util.UUID

/** A row from the corpus that needs (or has) a clue. */
data class WordRow(
    val wordId: UUID,
    val word: String,
    val length: Int,
)

/** Selection criteria for which rows to clue. */
data class ClueSelectionCriteria(
    val language: String,
    /** When `false`, restrict to rows whose `clue IS NULL`. */
    val includeAlreadyClued: Boolean,
    /** When `false`, restrict to `length BETWEEN 2 AND 9` (ADR-0013 §7). */
    val includeAllLengths: Boolean,
    /** When `false`, restrict to `word = lemma` — clue lemmas only and propagate at export. */
    val includeAllForms: Boolean,
    /** Cap rows returned. `null` means no cap. */
    val limit: Int?,
)

/** Port: select rows for clue generation and persist a generated clue. */
interface WordCorpusClueWriter {
    fun selectRows(criteria: ClueSelectionCriteria): List<WordRow>

    fun writeClue(wordId: UUID, clue: String)
}
