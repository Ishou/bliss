package com.bliss.survey.application.csv

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.bliss.survey.domain.model.Categorie
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Source
import com.bliss.survey.domain.model.Style
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.Tier
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StyleGuideCsvRoundTripTest {
    private val parser = StyleGuideCsvParser()
    private val writer = StyleGuideCsvWriter()

    private fun arbItem(): Arb<SurveyItem> =
        Arb.bind(
            Arb.string(2..12, Codepoint.alphanumeric()).map { it.uppercase() },
            Arb.string(2..50, Codepoint.alphanumeric()).map { raw ->
                raw.replaceFirstChar { c -> c.uppercase() }
            },
            Arb.enum<Pos>(),
            Arb.enum<Categorie>(),
            Arb.enum<Style>(),
            Arb.int(1..5),
        ) { mot, def, pos, cat, style, force ->
            SurveyItem(
                id = ItemId(UUID.randomUUID()),
                mot = mot,
                definition = def,
                pos = pos,
                categorie = cat,
                style = style,
                forceClaimed = force,
                longueur = mot.length,
                source = Source.SYNTHETIC_V1,
                sourceBatch = "test_batch",
                tier = Tier.MID,
                isCalibration = false,
                expected = null,
                retiredAt = null,
                createdAt = Instant.now(),
            )
        }

    @Test
    fun `parse of write equals original on structural fields`() =
        runTest {
            checkAll(arbItem()) { original ->
                val csvRow = writer.toRow(original, meta = emptyMap())
                val parsed = parser.parseRow(csvRow)
                assertThat(parsed.mot).isEqualTo(original.mot)
                assertThat(parsed.definition).isEqualTo(original.definition)
                assertThat(parsed.pos).isEqualTo(original.pos)
                assertThat(parsed.categorie).isEqualTo(original.categorie)
                assertThat(parsed.style).isEqualTo(original.style)
                assertThat(parsed.forceClaimed).isEqualTo(original.forceClaimed)
                assertThat(parsed.longueur).isEqualTo(original.longueur)
                assertThat(parsed.source).isEqualTo(original.source)
            }
        }

    @Test
    fun `parser accepts polyvalent pos token`() {
        val row = "ARGENT;Monnaie ou couleur;polyvalent;units;définition_directe;3;6;synthetic_v1"
        assertThat(parser.parseRow(row).pos).isEqualTo(Pos.POLYVALENT)
    }

    @Test
    fun `header is fixed schema`() {
        assertThat(writer.header()).isEqualTo("mot;definition;pos;categorie;style;force;longueur;source;meta")
    }
}
