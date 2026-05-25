package com.bliss.survey.application.filters

import java.text.Normalizer

class Filter2CaracteresInterdits : Filter {
    override val id = 2
    private val allowed = Regex("^[\\p{L}\\p{N}\\p{P}\\s]+$")

    override fun apply(input: FilterInput): FilterResult {
        val nfc = Normalizer.normalize(input.definition, Normalizer.Form.NFC)
        return if (allowed.matches(nfc)) {
            FilterResult.Accept(id)
        } else {
            FilterResult.Reject(id, "contains characters outside [letters, digits, punctuation, whitespace]")
        }
    }
}
