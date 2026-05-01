package com.bliss.grid.domain.model

data class Word private constructor(
    val text: String,
    val definition: String,
    /**
     * Dictionary headword — the canonical (lemmatised) form behind [text].
     * For inflected forms, lemma differs from text (e.g. text="COURUE" lemma="COURIR");
     * for headwords themselves it equals text. Folded to grid-cell ASCII (A–Z, no
     * accents) so two inflected forms of the same lemma share an identical key for
     * dedup purposes during generation. Defaults to [text] when callers don't carry
     * lemma data — equivalent to surface-form-only dedup.
     */
    val lemma: String,
) {
    init {
        require(text.isNotEmpty()) { "Word text must not be empty" }
        require(text.all { it in 'A'..'Z' }) { "Word text must be A-Z, was '$text'" }
        require(lemma.isNotEmpty()) { "Word lemma must not be empty (defaults to text)" }
        require(lemma.all { it in 'A'..'Z' }) { "Word lemma must be A-Z, was '$lemma'" }
    }

    companion object {
        operator fun invoke(
            text: String,
            definition: String,
            lemma: String? = null,
        ): Word {
            val foldedText = text.uppercase()
            return Word(foldedText, definition, lemma?.uppercase() ?: foldedText)
        }
    }
}
