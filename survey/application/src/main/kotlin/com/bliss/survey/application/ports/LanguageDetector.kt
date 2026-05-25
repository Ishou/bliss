package com.bliss.survey.application.ports

fun interface LanguageDetector {
    /** True when the definition is clearly English (calibrated: EN=1.0 AND FR<0.85 via Lingua 1.2.2). */
    fun isClearlyEnglish(text: String): Boolean
}
