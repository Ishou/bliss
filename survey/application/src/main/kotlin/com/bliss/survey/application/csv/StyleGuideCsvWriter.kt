package com.bliss.survey.application.csv

import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import java.text.Normalizer

class StyleGuideCsvWriter {
    fun header(): String = "mot;definition;pos;categorie;style;force;longueur;source;meta"

    fun toRow(
        item: SurveyItem,
        meta: Map<String, String>,
    ): String =
        buildString {
            append(quote(nfc(item.mot)))
            append(';')
            append(quote(nfc(item.definition)))
            append(';')
            append(item.pos.name.lowercase())
            append(';')
            append(item.categorie.name.lowercase())
            append(';')
            append(styleName(item.style))
            append(';')
            append(item.forceClaimed)
            append(';')
            append(item.longueur)
            append(';')
            append(item.source.name.lowercase())
            append(';')
            append(quote(meta.entries.joinToString("|") { "${it.key}:${it.value}" }))
        }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun quote(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) {
            "\"${s.replace("\"", "\"\"")}\""
        } else {
            s
        }

    private fun styleName(s: Style): String =
        when (s) {
            Style.DEFINITION_DIRECTE -> "définition_directe"
            Style.PERIPHRASE -> "périphrase"
            Style.METONYMIE -> "métonymie"
            Style.FONCTION_ROLE -> "fonction_rôle"
            Style.CALEMBOUR -> "calembour"
            Style.CULTUREL -> "culturel"
            Style.CRYPTIQUE -> "cryptique"
            Style.CRYPTIQUE_MORPHOLOGIQUE -> "cryptique_morphologique"
            Style.TECHNIQUE -> "technique"
        }
}
