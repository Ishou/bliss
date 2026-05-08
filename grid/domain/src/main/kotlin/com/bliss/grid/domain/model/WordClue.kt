package com.bliss.grid.domain.model

/**
 * One candidate clue for a [Word]. A word may carry several `WordClue`s
 * — typically one from the LoRA pipeline (theme = null) plus one or
 * more from themed overlay files (theme = the overlay's category).
 *
 * The clue actually shown for a placed word is chosen by the grid
 * filler at placement time, prioritising clues whose theme respects
 * the per-grid theme caps. See `SkeletonFiller.pickClueForPlacement`.
 *
 * Distinct from [Clue] (which represents an *already placed* clue at
 * a specific grid position with a direction); `WordClue` is the
 * candidate-text-with-theme record at the corpus layer.
 */
data class WordClue(
    val text: String,
    val theme: String? = null,
)
// Intentionally no `text isNotBlank` check: prior Word.definition was
// unchecked, and several tests pass empty fixtures where the clue text
// is irrelevant to the assertion. Production code that loads from CSV
// drops blank-clue rows in `CsvWordRepository.toWordWithFreq`, so the
// runtime never sees them.
