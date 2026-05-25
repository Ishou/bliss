package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter6LangueFrTest {
    private val f = Filter6LangueFr()

    private fun input(def: String) = FilterInput(mot = "MOT", definition = def)

    @Test
    fun `clearly english definition rejects`() {
        // Longer text gives lingua enough signal to exceed the 0.7/0.3 thresholds.
        val englishText = "Hello world I would like to know what time you go home tonight my friend"
        assertThat(f.apply(input(englishText))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `clearly french definition accepts`() {
        val frenchText = "Aliment de boulangerie tres apprecie au petit dejeuner avec un cafe et du beurre"
        assertThat(f.apply(input(frenchText))).isInstanceOf(FilterResult.Accept::class)
    }
}
