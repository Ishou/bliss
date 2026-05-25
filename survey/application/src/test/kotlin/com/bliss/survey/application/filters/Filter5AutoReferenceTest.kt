package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Style
import org.junit.jupiter.api.Test

class Filter5AutoReferenceTest {
    private val f = Filter5AutoReference()

    @Test
    fun `self reference via accent strip rejects`() {
        // POMME vs Pomme(e-acute) becomes pomm-e match after stripping accents
        val accented = "Pomm" + String(Character.toChars(0x00E9))
        val r = f.apply(FilterInput(mot = "POMME", definition = accented))
        assertThat(r).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `substring without boundary accepts`() {
        val r = f.apply(FilterInput(mot = "RIO", definition = "Carioca du carnaval"))
        assertThat(r).isInstanceOf(FilterResult.Accept::class)
    }

    @Test
    fun `sigle abreviation bypasses filter`() {
        val r =
            f.apply(
                FilterInput(
                    mot = "KO",
                    definition = "Abreviation pour kilooctet KO",
                    pos = Pos.SIGLE_ABREVIATION,
                ),
            )
        assertThat(r).isInstanceOf(FilterResult.Accept::class)
    }

    @Test
    fun `cryptique morphologique bypasses filter`() {
        val r =
            f.apply(
                FilterInput(
                    mot = "MOT",
                    definition = "le mot a 3 lettres",
                    style = Style.CRYPTIQUE_MORPHOLOGIQUE,
                ),
            )
        assertThat(r).isInstanceOf(FilterResult.Accept::class)
    }
}
