package com.bliss.survey.domain.model

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class EnumsTest {
    @Test
    fun `Pos has 13 values (§7-1)`() {
        assertThat(Pos.values().toList()).hasSize(13)
    }

    @Test
    fun `Pos POLYVALENT round-trips through the wire-DB string`() {
        assertThat(Pos.POLYVALENT.name.lowercase()).isEqualTo("polyvalent")
        assertThat(Pos.valueOf("polyvalent".uppercase())).isEqualTo(Pos.POLYVALENT)
    }

    @Test
    fun `Pos NOM_COMMUN round-trips (precedent)`() {
        assertThat(Pos.NOM_COMMUN.name.lowercase()).isEqualTo("nom_commun")
        assertThat(Pos.valueOf("nom_commun".uppercase())).isEqualTo(Pos.NOM_COMMUN)
    }

    @Test
    fun `Categorie has 43 values (§7-2)`() {
        assertThat(Categorie.values().toList()).hasSize(43)
    }

    @Test
    fun `Style has 9 values (§4)`() {
        assertThat(Style.values().toList()).hasSize(9)
    }

    @Test
    fun `Tier has 4 values`() {
        assertThat(Tier.values().toList()).hasSize(4)
    }

    @Test
    fun `FlagReason has 4 values`() {
        assertThat(FlagReason.values().toList()).hasSize(4)
    }

    @Test
    fun `Source has 5 values`() {
        assertThat(Source.values().toList()).hasSize(5)
    }

    @Test
    fun `SubmittedAs has 2 values`() {
        assertThat(SubmittedAs.values().toList()).hasSize(2)
    }
}
