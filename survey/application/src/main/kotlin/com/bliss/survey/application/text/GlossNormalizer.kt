package com.bliss.survey.application.text

import java.text.Normalizer

/** Soft-normalize a French gloss for autocomplete matching and inventory dedup (ADR-0061). */
object GlossNormalizer {
    private val DETERMINER = Regex("^(l['’]|la |le |les )\\s*", RegexOption.IGNORE_CASE)
    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(input: String): String =
        input
            .let { DETERMINER.replace(it, "") }
            .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            .let { COMBINING_MARKS.replace(it, "") }
            .lowercase()
            .let { WHITESPACE.replace(it, " ") }
            .trim()
}
