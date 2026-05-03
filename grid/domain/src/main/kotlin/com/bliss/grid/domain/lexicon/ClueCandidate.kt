// Phase 2 of the clue-generation pipeline plan — domain type for the
// `clue_candidates` table (V6 migration). One row = one possible clue for a
// word, annotated with its provenance (curated / DBnary-synonym derivation /
// model output) so the export step can rank multiple candidates per word.
package com.bliss.grid.domain.lexicon

import java.util.UUID

/**
 * A single candidate clue for a word.
 *
 * Identity at the database level is `(wordId, source, senseIndex, clueText)`
 * (the V6 UNIQUE constraint). `senseIndex` is `null` for sources that don't
 * map to a specific DBnary sense — curated entries, model-generated clues
 * that target the lemma rather than a single sense, etc.
 *
 * `confidence` is a 0..1 score when measurable (LLM logprob, retrieval
 * similarity), `null` when not — never compared lexicographically with the
 * unset case.
 */
data class ClueCandidate(
    val wordId: UUID,
    val source: String,
    val clueText: String,
    val senseIndex: Int? = null,
    val confidence: Double? = null,
    val modelVersion: String? = null,
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        val trimmedLength = clueText.length
        require(trimmedLength in CLUE_LENGTH_RANGE) {
            "clueText length must be in $CLUE_LENGTH_RANGE, got $trimmedLength"
        }
        require(senseIndex == null || senseIndex >= 0) {
            "senseIndex must be >= 0 when set, got $senseIndex"
        }
        require(confidence == null || confidence in CONFIDENCE_RANGE) {
            "confidence must be in $CONFIDENCE_RANGE when set, got $confidence"
        }
        require(modelVersion == null || modelVersion.isNotBlank()) {
            "modelVersion must be null or non-blank"
        }
    }

    companion object {
        /** Mots-fléchés clue length: at least one character, at most 80 (V6 CHECK). */
        val CLUE_LENGTH_RANGE: IntRange = 1..80
        val CONFIDENCE_RANGE: ClosedFloatingPointRange<Double> = 0.0..1.0
    }
}

/**
 * Canonical `source` values for [ClueCandidate.source]. Model-specific sources
 * (`mistral-7b-base`, `mistral-nemo-instruct-2407`, ...) are runtime-generated
 * by the batch generator and are not enumerated here.
 */
object ClueSource {
    /** Hand-curated entries (e.g. data/curated/fr.csv). Highest priority by default. */
    const val CURATED: String = "curated"

    /** One candidate per (word, synonym) pair derived from DBnary's synonym graph. */
    const val DBNARY_SYNONYM: String = "dbnary-synonym"

    /** Carry-forward of words.clue values that pre-date the candidates table. */
    const val LEGACY: String = "legacy"
}
