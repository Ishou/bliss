package com.bliss.survey.application.filters

import com.bliss.survey.application.ports.LanguageDetector

class FilterPipeline(
    private val filters: List<Filter>,
) {
    fun run(input: FilterInput): FilterResult {
        for (f in filters) {
            val r = f.apply(input)
            if (r is FilterResult.Reject) return r
        }
        return FilterResult.Accept(filterId = 0)
    }

    companion object {
        fun default(detector: LanguageDetector): FilterPipeline =
            FilterPipeline(
                listOf(
                    Filter1Typographiques(),
                    Filter2CaracteresInterdits(),
                    Filter3Longueur(),
                    Filter4StereotypesIa(),
                    Filter5AutoReference(),
                    Filter6LangueFr(detector),
                    Filter7Tautologie(),
                ),
            )
    }
}
