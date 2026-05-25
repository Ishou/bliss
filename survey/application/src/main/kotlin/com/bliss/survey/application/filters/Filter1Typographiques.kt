package com.bliss.survey.application.filters

class Filter1Typographiques : Filter {
    override val id = 1

    private val markdownBold = Regex("\\*\\*")
    private val htmlTag = Regex("<[^>]+>")

    override fun apply(input: FilterInput): FilterResult {
        val def = input.definition
        if (def.codePoints().anyMatch { it >= 0x1F000 }) {
            return FilterResult.Reject(id, "emoji or supplementary codepoint")
        }
        if (markdownBold.containsMatchIn(def)) return FilterResult.Reject(id, "markdown bold (** **)")
        if (htmlTag.containsMatchIn(def)) return FilterResult.Reject(id, "html tag")
        for (ch in def) {
            if (ch.code in 0..31 && ch != '\t' && ch != '\n' && ch != '\r') {
                return FilterResult.Reject(id, "non-printable control character")
            }
            if (ch.code == 0x7F) return FilterResult.Reject(id, "DEL character")
        }
        return FilterResult.Accept(id)
    }
}
