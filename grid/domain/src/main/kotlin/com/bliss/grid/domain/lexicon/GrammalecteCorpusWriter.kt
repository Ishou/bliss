package com.bliss.grid.domain.lexicon

/** Port: bulk-load a Grammalecte lexique into the corpus, with an optional truncate. */
interface GrammalecteCorpusWriter {
    /** `DELETE FROM words WHERE language = ?` — returns rows deleted. */
    fun truncateLanguage(language: String): Int

    /** Inserts entries with `ON CONFLICT (word, language) DO NOTHING`. Returns rows inserted. */
    fun insertLexique(
        language: String,
        source: String,
        sourceLicense: String,
        entries: Sequence<GrammalecteEntry>,
    ): Int
}
