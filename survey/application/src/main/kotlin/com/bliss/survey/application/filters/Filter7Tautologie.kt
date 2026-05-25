package com.bliss.survey.application.filters

class Filter7Tautologie : Filter {
    override val id = 7

    private val generic =
        setOf(
            "animal",
            "prénom",
            "plante",
            "objet",
            "fruit",
            "outil",
            "couleur",
            "mot",
        )

    override fun apply(input: FilterInput): FilterResult {
        val trimmed =
            input.definition
                .trim()
                .lowercase()
                .trimEnd('.', ',', '!', '?')
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            tokens.size == 1 && tokens[0] in generic -> FilterResult.Reject(id, "bare generic label '${tokens[0]}'")
            tokens.size == 2 && tokens[0] in generic -> FilterResult.Warning(id, "thin label plus qualifier")
            else -> FilterResult.Accept(id)
        }
    }
}
