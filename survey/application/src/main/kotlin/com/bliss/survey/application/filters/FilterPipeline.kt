package com.bliss.survey.application.filters

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
        fun default(): FilterPipeline =
            FilterPipeline(
                listOf(
                    Filter1Typographiques(),
                    Filter2CaracteresInterdits(),
                    Filter3Longueur(),
                    Filter4StereotypesIa(),
                    Filter5AutoReference(),
                    Filter6LangueFr(),
                    Filter7Tautologie(),
                ),
            )
    }
}
