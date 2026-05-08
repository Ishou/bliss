package com.bliss.grid.domain.model

data class Word private constructor(
    val text: String,
    /**
     * One or more candidate clues. The list is non-empty by invariant;
     * exactly which clue is shown for a placed word is decided by the
     * grid filler at placement time (it picks the first clue whose
     * theme respects the active theme caps, see `SkeletonFiller`).
     *
     * Most words have a single clue (LoRA-generated); a few — like
     * `est` — carry both a verb-form clue from the main corpus and a
     * compass-themed alternate from `themed/compass.csv`.
     */
    val clues: List<WordClue>,
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
        require(clues.isNotEmpty()) { "Word must carry at least one WordClue" }
    }

    /**
     * The "primary" clue text — the first entry in [clues]. Convenience
     * accessor for sites that don't know about the multi-clue feature
     * (rendering at the API layer should prefer the placement's
     * `chosenClue` instead, which respects per-grid theme diversity).
     */
    val definition: String get() = clues.first().text

    /**
     * Set of distinct themes carried across all clues. Empty if every
     * clue has `theme = null`. Used by the filler's domain check to
     * decide whether a word can fit a given theme-cap state.
     */
    val themes: Set<String> get() = clues.mapNotNullTo(HashSet()) { it.theme }

    companion object {
        operator fun invoke(
            text: String,
            definition: String,
            lemma: String? = null,
            theme: String? = null,
        ): Word {
            val foldedText = text.uppercase()
            return Word(
                foldedText,
                listOf(WordClue(definition, theme)),
                lemma?.uppercase() ?: foldedText,
            )
        }

        operator fun invoke(
            text: String,
            clues: List<WordClue>,
            lemma: String? = null,
        ): Word {
            require(clues.isNotEmpty()) { "Word must carry at least one WordClue" }
            val foldedText = text.uppercase()
            return Word(foldedText, clues, lemma?.uppercase() ?: foldedText)
        }
    }
}
