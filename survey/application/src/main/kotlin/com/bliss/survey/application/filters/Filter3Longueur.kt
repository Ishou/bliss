package com.bliss.survey.application.filters

class Filter3Longueur : Filter {
    override val id = 3
    private val splitWords = Regex("[\\s'\\u2019\\-]+")

    override fun apply(input: FilterInput): FilterResult {
        val words =
            input.definition
                .trim()
                .split(splitWords)
                .filter { it.isNotEmpty() }
                .size
        val chars = input.definition.length
        return when {
            words > 12 || chars > 60 -> FilterResult.Reject(id, "too long ($words words / $chars chars)")
            words > 8 -> FilterResult.Warning(id, "long ($words words; cap is 8)")
            else -> FilterResult.Accept(id)
        }
    }
}
