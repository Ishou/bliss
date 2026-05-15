package com.bliss.grid.domain.generation

/**
 * Closed-set categories the grid filler can cap. A word's theme is a
 * single string drawn from [Themes]; `null` means the word doesn't belong
 * to any tracked theme — the common case for ordinary vocabulary.
 *
 * Theme tags come from **hand-curated** files only — one CSV per theme
 * (classpath resource `words/themed/<theme>.csv`, bundled with the infrastructure module).
 * There is no auto-detection;
 * if a word isn't on a curated themed list, it doesn't carry a theme,
 * and the per-grid cap doesn't apply to it.
 *
 * Per-grid caps live in [GridConstraints.themeLimits]; the filler tracks
 * usage and rejects placements that would exceed a cap.
 */
object Themes {
    /** Roman numerals (II, IV, XII, …). Capped because they read as filler. */
    const val ROMAN: String = "roman"

    /** IUPAC chemical element symbols (AG, AU, FE, …). */
    const val CHEM: String = "chem"

    /** French title abbreviations (DR, MR, MME, …). */
    const val ABBREV: String = "abbrev"

    /** ISO-3166 country codes (FR, US, ITA, …). */
    const val COUNTRY: String = "country"

    /** French interjections (AH, OH, EH, …). */
    const val INTERJECTION: String = "interjection"

    /** Solfeggio musical notes (DO, RE, MI, FA, SOL, LA, SI). */
    const val NOTE: String = "note"

    /** Common SI / metric units written as letter sequences (KG, KM, KO, …). */
    const val UNIT: String = "unit"

    /**
     * French cardinal/ordinal compass points (NE, NO, SO, ENE, ESE, …).
     * Heavily represented in the curated corpus thanks to mots-fléchés
     * convention "Point cardinal"; capping prevents grids from being
     * half compass directions.
     */
    const val COMPASS: String = "compass"

    /**
     * Greek letters (alpha, beta, mu, pi, ...). The corpus carries
     * enough of them — bliss-curated 2/3-letter symbols (mu, nu, pi,
     * tau, phi, psi, rho) plus longer grammalecte names (alpha, delta,
     * gamma) — that an uncapped grid can stack three of them and read
     * like a math primer. Default cap = 2.
     */
    const val GREEK: String = "greek"

    /**
     * Common French acronyms / sigles (TV, FM, PQ, CP, BAC, TGV, ...).
     * Distinct from [ABBREV] which is title-only (DR, MR, MME). The
     * corpus has a long tail of these and they read as filler if a
     * grid stacks more than a couple. Default cap = 2.
     */
    const val SIGLE: String = "sigle"
}
