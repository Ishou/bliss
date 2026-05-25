package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter4StereotypesIaTest {
    private val f = Filter4StereotypesIa()

    private fun input(def: String) = FilterInput(mot = "MOT", definition = def)

    @Test
    fun `quelqu un qui prefix rejects`() {
        assertThat(f.apply(input("Quelqu'un qui mange"))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `quelqu un qui with typographic apostrophe rejects`() {
        val apos = String(Character.toChars(0x2019))
        assertThat(f.apply(input("Quelqu" + apos + "un qui mange"))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `action de prefix rejects`() {
        assertThat(f.apply(input("Action de courir"))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `clean accepts`() {
        assertThat(f.apply(input("Aliment de boulangerie"))).isInstanceOf(FilterResult.Accept::class)
    }
}
