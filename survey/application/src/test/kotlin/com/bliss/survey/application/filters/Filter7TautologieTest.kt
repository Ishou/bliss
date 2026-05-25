package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter7TautologieTest {
    private val f = Filter7Tautologie()

    private fun input(def: String) = FilterInput(mot = "MOT", definition = def)

    @Test
    fun `bare generic label rejects`() {
        assertThat(f.apply(input("Animal"))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `thin label plus qualifier warns`() {
        assertThat(f.apply(input("Animal commun"))).isInstanceOf(FilterResult.Warning::class)
    }

    @Test
    fun `rich definition accepts`() {
        assertThat(f.apply(input("Animal de basse-cour eleve pour ses oeufs"))).isInstanceOf(FilterResult.Accept::class)
    }
}
