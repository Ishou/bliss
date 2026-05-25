package com.bliss.survey.application.filters

import com.bliss.survey.application.ports.LanguageDetector

class Filter6LangueFr(
    private val detector: LanguageDetector,
) : Filter {
    override val id = 6

    override fun apply(input: FilterInput): FilterResult =
        if (detector.isClearlyEnglish(input.definition)) {
            FilterResult.Reject(id, "looks English")
        } else {
            FilterResult.Accept(id)
        }
}
