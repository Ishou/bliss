package com.bliss.survey.application.filters

import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Style
import java.text.Normalizer

class Filter5AutoReference : Filter {
    override val id = 5

    private fun stripAccents(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    override fun apply(input: FilterInput): FilterResult {
        if (input.pos == Pos.SIGLE_ABREVIATION) return FilterResult.Accept(id)
        if (input.style == Style.CRYPTIQUE_MORPHOLOGIQUE) return FilterResult.Accept(id)
        val motStem = stripAccents(input.mot.lowercase())
        val defStripped = stripAccents(input.definition.lowercase())
        if (Regex("\\b${Regex.escape(motStem)}\\b").containsMatchIn(defStripped)) {
            return FilterResult.Reject(id, "self-reference: '${input.mot}' appears in definition")
        }
        return FilterResult.Accept(id)
    }
}
