package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter2CaracteresInterditsTest {
    private val f = Filter2CaracteresInterdits()

    private fun input(def: String) = FilterInput(mot = "MOT", definition = def)

    @Test
    fun `nfc french accept`() {
        // E with acute, NFC pre-composed
        val def = "Pomme " + String(Character.toChars(0x00C9)) + "paule"
        assertThat(f.apply(input(def))).isInstanceOf(FilterResult.Accept::class)
    }

    @Test
    fun `nfd decomposed accent accept after normalisation`() {
        // E + combining acute, decomposed form
        val def = "Pomme " + String(Character.toChars(0x0045)) + String(Character.toChars(0x0301)) + "paule"
        assertThat(f.apply(input(def))).isInstanceOf(FilterResult.Accept::class)
    }

    @Test
    fun `arbitrary symbol reject`() {
        // U+0024 (dollar sign) is Symbol_Currency, not letter/digit/punct/space
        val def = "Aliment" + String(Character.toChars(0x0024))
        assertThat(f.apply(input(def))).isInstanceOf(FilterResult.Reject::class)
    }
}
