package com.bliss.survey.infrastructure.language

import com.bliss.survey.application.ports.LanguageDetector
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

/**
 * Lingua-backed [LanguageDetector] (FR vs EN only). Calibration: a text is "clearly English"
 * when the FR-vs-EN confidence values satisfy EN >= 1.0 AND FR < 0.85. The Lingua detector
 * is heavy to initialise (~hundreds of MB of n-gram tables) so the singleton instance is
 * built once per process in the constructor.
 */
class LinguaLanguageDetector : LanguageDetector {
    private val detector =
        LanguageDetectorBuilder
            .fromLanguages(Language.FRENCH, Language.ENGLISH)
            .build()

    override fun isClearlyEnglish(text: String): Boolean {
        if (text.isBlank()) return false
        val confidence = detector.computeLanguageConfidenceValues(text)
        val englishConfidence = confidence[Language.ENGLISH] ?: 0.0
        val frenchConfidence = confidence[Language.FRENCH] ?: 0.0
        return englishConfidence >= EN_THRESHOLD && frenchConfidence < FR_THRESHOLD
    }

    private companion object {
        const val EN_THRESHOLD: Double = 1.0
        const val FR_THRESHOLD: Double = 0.85
    }
}
