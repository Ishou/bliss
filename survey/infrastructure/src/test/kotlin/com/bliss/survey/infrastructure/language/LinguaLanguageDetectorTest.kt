package com.bliss.survey.infrastructure.language

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinguaLanguageDetectorTest {
    private val detector = LinguaLanguageDetector()

    @Test
    fun `clear English text is detected as English`() {
        val sentence = "The quick brown fox jumps over the lazy dog every single morning."
        assertThat(detector.isClearlyEnglish(sentence)).isEqualTo(true)
    }

    @Test
    fun `clear French text is not flagged as English`() {
        val sentence = "Le renard brun rapide saute par dessus le chien paresseux chaque matin."
        assertThat(detector.isClearlyEnglish(sentence)).isEqualTo(false)
    }

    @Test
    fun `borderline loan-word gentleman is not flagged as English`() {
        // EN>=1.0 deliberately excludes only confident English; French loan-words like "gentleman" pass.
        assertThat(detector.isClearlyEnglish("gentleman")).isEqualTo(false)
    }

    @Test
    fun `blank input is not flagged as English`() {
        assertThat(detector.isClearlyEnglish("")).isEqualTo(false)
        assertThat(detector.isClearlyEnglish("   ")).isEqualTo(false)
    }
}
