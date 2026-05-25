package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter6LangueFrTest {
    private fun input(def: String) = FilterInput(mot = "MOT", definition = def)

    @Test
    fun `clearly english definition rejects`() {
        val f = Filter6LangueFr { _ -> true }
        assertThat(f.apply(input("The friendly cat at home"))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `not clearly english definition accepts`() {
        val f = Filter6LangueFr { _ -> false }
        assertThat(f.apply(input("Aliment de boulangerie"))).isInstanceOf(FilterResult.Accept::class)
    }
}
