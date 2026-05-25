package com.bliss.survey.application.filters

class Filter4StereotypesIa : Filter {
    override val id = 4

    private val proscribed =
        listOf(
            "quelqu'un qui ",
            "quelqu’un qui ",
            "personne qui ",
            "action de ",
            "fait de ",
            "chose qui ",
        )

    override fun apply(input: FilterInput): FilterResult {
        val lc = input.definition.lowercase()
        proscribed.firstOrNull { lc.startsWith(it) }?.let {
            return FilterResult.Reject(id, "starts with AI stereotype prefix '$it'")
        }
        return FilterResult.Accept(id)
    }
}
