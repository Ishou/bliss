package com.bliss.survey.application.filters

import com.github.pemistahl.lingua.api.IsoCode639_1
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

class Filter6LangueFr : Filter {
    override val id = 6

    private val detector =
        LanguageDetectorBuilder
            .fromIsoCodes639_1(IsoCode639_1.FR, IsoCode639_1.EN)
            .build()

    override fun apply(input: FilterInput): FilterResult {
        // Lingua's computeLanguageConfidenceValues returns relative confidences
        // where the most-likely language scores 1.0. We reject when English
        // dominates French by a clear margin; the magic numbers were calibrated
        // against the bliss-clue-ai pipeline_test_pilot_v1 fixtures (LinguaProbe
        // dumps the calibration data).
        val confidences = detector.computeLanguageConfidenceValues(input.definition)
        val fr = confidences.entries.firstOrNull { it.key.isoCode639_1 == IsoCode639_1.FR }?.value ?: 0.0
        val en = confidences.entries.firstOrNull { it.key.isoCode639_1 == IsoCode639_1.EN }?.value ?: 0.0
        return if (en >= 1.0 && fr < 0.85) {
            FilterResult.Reject(id, "looks English (FR=$fr, EN=$en)")
        } else {
            FilterResult.Accept(id)
        }
    }
}
