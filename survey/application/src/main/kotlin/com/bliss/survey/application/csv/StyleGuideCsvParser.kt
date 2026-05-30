package com.bliss.survey.application.csv

import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

class StyleGuideCsvParser {
    fun parseRow(
        row: String,
        lineNumber: Int = 1,
    ): SurveyItem {
        val cells = splitSemicolons(row)
        require(cells.size >= 8) { "row must have at least 8 columns (got ${cells.size}) at line $lineNumber" }
        val mot = nfc(cells[0])
        val definition = nfc(cells[1])
        val pos = parsePos(cells[2])
        val categorie = parseCategorie(cells[3])
        val style = parseStyle(cells[4])
        val force = cells[5].toInt()
        val longueur = cells[6].toInt()
        val source = parseSource(cells[7])
        return SurveyItem(
            id = ItemId(UUID.randomUUID()),
            mot = mot,
            definition = definition,
            pos = pos,
            categorie = categorie,
            style = style,
            forceClaimed = force,
            longueur = longueur,
            source = source,
            sourceBatch = "unknown",
            tier = Tier.MID,
            isCalibration = false,
            expected = null,
            retiredAt = null,
            createdAt = Instant.now(),
            trainingWeight = cells.getOrNull(8)?.toDoubleOrNull() ?: 1.0,
        )
    }

    private fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun splitSemicolons(row: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                inQuote && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> {
                    cur.append('"')
                    i += 2
                }
                c == '"' -> {
                    inQuote = !inQuote
                    i++
                }
                c == ';' && !inQuote -> {
                    out += cur.toString()
                    cur.clear()
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        out += cur.toString()
        return out
    }

    private fun parsePos(s: String) = Pos.valueOf(s.uppercase())

    private fun parseCategorie(s: String) = Categorie.valueOf(s.uppercase())

    private fun parseStyle(s: String): Style {
        // Style names may be written with French accents on the wire; strip them first.
        val canonical =
            Normalizer
                .normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .uppercase()
        return Style.valueOf(canonical)
    }

    private fun parseSource(s: String) = Source.valueOf(s.uppercase())
}
