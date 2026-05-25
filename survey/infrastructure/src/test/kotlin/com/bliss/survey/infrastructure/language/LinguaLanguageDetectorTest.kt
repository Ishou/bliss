package com.bliss.survey.infrastructure.language

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Spec calibration (Phase 4 doc): EN >= 1.0 AND FR < 0.85.
 *
 * Cases:
 *  - clear English text       -> rejects (isClearlyEnglish = true)
 *  - clear French text        -> accepts (false)
 *  - borderline loan-word "gentleman" -> accepts (false) per spec note
 */
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
        // Single-token loan-words like "gentleman" appear in French clues; the EN>=1.0
        // threshold deliberately rejects only confidently-English texts.
        assertThat(detector.isClearlyEnglish("gentleman")).isEqualTo(false)
    }

    @Test
    fun `blank input is not flagged as English`() {
        assertThat(detector.isClearlyEnglish("")).isEqualTo(false)
        assertThat(detector.isClearlyEnglish("   ")).isEqualTo(false)
    }
}
