// Curated-source port: hand-maintained word entries that bypass frequency
// filtering in the export pipeline. Purpose: inject high-value short forms
// (titles, periodic-table symbols, Roman numerals, cardinal-point
// abbreviations, French staples) the upstream Grammalecte source either omits
// or buries beneath noise. See PercentileLengthFilter.
package com.bliss.grid.domain.lexicon

/** Marker source value identifying curated rows in [ExportRow.source]. */
const val CURATED_SOURCE: String = "bliss"

/** Marker license value for curated rows (own work, public domain). */
const val CURATED_LICENSE: String = "CC0-1.0"

/**
 * Port: read curated [ExportRow]s for a given language. Implementations may
 * return an empty sequence when no curation file exists for the language;
 * curation is opt-in per language.
 */
interface CuratedSourceReader {
    fun rows(language: String): Sequence<ExportRow>
}
