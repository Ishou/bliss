package com.bliss.survey.application.filters

import assertk.assertThat
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class Filter1TypographiquesTest {
    private val f = Filter1Typographiques()

    private fun input(def: String) = FilterInput(mot = "PAIN", definition = def)

    @Test
    fun `clean definition accepts`() {
        assertThat(f.apply(input("Aliment de boulangerie"))).isInstanceOf(FilterResult.Accept::class)
    }

    @Test
    fun `supplementary codepoint such as emoji rejects`() {
        // U+1F35E (bread) is a supplementary plane codepoint encoded as a surrogate pair.
        val emojiCodePoint = 0x1F35E
        val def = "Aliment " + String(Character.toChars(emojiCodePoint)) + " de boulangerie"
        assertThat(f.apply(input(def))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `markdown bold rejects`() {
        assertThat(f.apply(input("**Aliment** de boulangerie"))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `html tag rejects`() {
        assertThat(f.apply(input("<b>Aliment</b> de boulangerie"))).isInstanceOf(FilterResult.Reject::class)
    }

    @Test
    fun `non-printable control character rejects`() {
        // U+0007 (BEL) is an ASCII control char not in {tab, LF, CR}.
        val def = "Aliment\u0007de boulangerie"
        assertThat(f.apply(input(def))).isInstanceOf(FilterResult.Reject::class)
    }
}
