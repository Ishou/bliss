package com.bliss.survey.domain.model

import java.time.Instant

data class SurveyItem(
    val id: ItemId,
    val mot: String,
    val definition: String,
    val pos: Pos,
    val categorie: Categorie,
    val style: Style,
    val forceClaimed: Int,
    val longueur: Int,
    val source: Source,
    val sourceBatch: String,
    val tier: Tier,
    val isCalibration: Boolean,
    val expected: CalibrationAnswer?,
    val retiredAt: Instant?,
    val createdAt: Instant,
    val trainingWeight: Double = 1.0,
) {
    init {
        require(mot.isNotBlank()) { "mot must not be blank" }
        require(definition.isNotBlank()) { "definition must not be blank" }
        require(forceClaimed in 1..5) { "force_claimed must be in 1..5 (was $forceClaimed)" }
        require(longueur > 0) { "longueur must be positive (was $longueur)" }
        require(sourceBatch.isNotBlank()) { "source_batch must not be blank" }
        require(trainingWeight > 0) { "training_weight must be positive (was $trainingWeight)" }
    }
}

data class CalibrationAnswer(
    val expectedQualiteMin: Int,
    val expectedQualiteMax: Int,
    val expectedDifficulteMin: Int,
    val expectedDifficulteMax: Int,
) {
    init {
        require(expectedQualiteMin in 1..5 && expectedQualiteMax in 1..5 && expectedQualiteMin <= expectedQualiteMax)
        require(expectedDifficulteMin in 1..5 && expectedDifficulteMax in 1..5 && expectedDifficulteMin <= expectedDifficulteMax)
    }
}
