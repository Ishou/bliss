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
    /**
     * `true` when the clue is short enough to fit in a stacked half-cell (two
     * clues sharing one definition cell). Computed offline by `clue_metrics.py`
     * at corpus-build time; `false` means the clue can only be placed in a
     * single-clue cell. Defaults to `true` for hand-curated short words and
     * for legacy rows missing the column.
     */
    val compact: Boolean = true,
    /**
     * Theme tag — a closed-set category drawn from
     * [com.bliss.grid.domain.generation.Themes]. `null` means the
     * word doesn't belong to any tracked theme (the common case). Used by
     * the grid filler to enforce per-grid caps (e.g. "at most 1 Roman
     * numeral per grid"). Curation lives in per-theme CSV overlays under
     * `grid/api/src/main/resources/words/themed/<theme>.csv`; words absent
     * from those files carry no theme.
     */
    val theme: String? = null,
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
            compact: Boolean = true,
            theme: String? = null,
        ): Word {
            val foldedText = text.uppercase()
            return Word(foldedText, definition, lemma?.uppercase() ?: foldedText, compact, theme)
        }
    }
}
