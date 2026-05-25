package com.bliss.survey.application.ports

fun interface LanguageDetector {
    /** True if the text is clearly English per the project threshold (FR < 0.3 AND EN > 0.7). */
    fun isClearlyEnglish(text: String): Boolean
}
