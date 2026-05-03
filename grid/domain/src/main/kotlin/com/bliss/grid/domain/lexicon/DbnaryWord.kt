// DBnary (CC BY-SA RDF extract of French Wiktionary) entry, as the foundation
// for clue generation: each word carries a citation form, POS, an ordered list
// of senses (DBnary's `skos:definition`), and a flat synonym graph
// (`dbnary:synonym` at LexicalEntry level — same shape DBnary's fra/ graph
// serves over SPARQL).
package com.bliss.grid.domain.lexicon

/**
 * A single word entry pulled from DBnary, keyed by `(language, lemma, pos)`.
 *
 * Senses come back ordered as DBnary returned them; `senseIndex` is the
 * original 0-based position so downstream consumers can walk them in priority
 * order (sense 1 is usually the canonical/most-common).
 *
 * Synonyms are at the word level, not per-sense. DBnary's RDF model attaches
 * `dbnary:synonym` to the LexicalEntry, and the fra/ graph follows that.
 */
data class DbnaryWord(
    val lemma: String,
    val pos: String,
    val language: String = "fr",
    val senses: List<DbnarySense> = emptyList(),
    val synonyms: List<String> = emptyList(),
) {
    init {
        require(lemma.isNotBlank()) { "lemma must not be blank" }
        require(pos.isNotBlank()) { "pos must not be blank" }
        require(language.isNotBlank()) { "language must not be blank" }
        val senseIndices = senses.map { it.senseIndex }
        require(senseIndices.size == senseIndices.toSet().size) {
            "senseIndex must be unique within a word: $senseIndices"
        }
        require(synonyms.size == synonyms.toSet().size) {
            "synonyms must be unique: $synonyms"
        }
    }
}

/**
 * A single dictionary sense: the raw `definition_text` plus an optional
 * `register` marker (`(Familier)`, `(Vieilli)`, `(Religion)`, ...) which
 * DBnary embeds in parentheses at the start of definitions and which downstream
 * filters can use to prefer or reject senses.
 */
data class DbnarySense(
    val senseIndex: Int,
    val definitionText: String,
    val register: String? = null,
) {
    init {
        require(senseIndex >= 0) { "senseIndex must be >= 0, got $senseIndex" }
        require(definitionText.isNotBlank()) { "definitionText must not be blank" }
        require(register == null || register.isNotBlank()) {
            "register must be null or non-blank"
        }
    }
}
