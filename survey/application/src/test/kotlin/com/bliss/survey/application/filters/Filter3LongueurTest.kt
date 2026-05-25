package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter3LongueurTest {
    private val f = Filter3Longueur()

    private fun input(def: String) = FilterInput(mot = "MOT", definition = def)

    @Test
    fun `short definition accepts`() {
        assertThat(f.apply(input("Aliment de boulangerie"))).isInstanceOf(FilterResult.Accept::class)
    }

    @Test
    fun `nine word definition warns`() {
        assertThat(f.apply(input("un deux trois quatre cinq six sept huit neuf"))).isInstanceOf(FilterResult.Warning::class)
    }

    @Test
    fun `thirteen word definition rejects`() {
        val words = (1..13).joinToString(" ") { "mot" }
        assertThat(f.apply(input(words))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `over sixty char definition rejects`() {
        val longDef = "a".repeat(61)
        assertThat(f.apply(input(longDef))).isInstanceOf(FilterResult.Reject::class)
    }
}
