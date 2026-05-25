package com.bliss.survey.domain.model

import assertk.assertFailure
import assertk.assertions.hasClass
import org.junit.jupiter.api.Test

class CalibrationAnswerTest {
    @Test
    fun `valid construction succeeds`() {
        CalibrationAnswer(
            expectedQualiteMin = 1,
            expectedQualiteMax = 5,
            expectedDifficulteMin = 1,
            expectedDifficulteMax = 5,
        )
    }

    @Test
    fun `qualiteMin greater than qualiteMax throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 4,
                expectedQualiteMax = 2,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 5,
            )
        }.hasClass(IllegalArgumentException::class)
    }

    @Test
    fun `qualiteMin below 1 throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 0,
                expectedQualiteMax = 5,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 5,
            )
        }.hasClass(IllegalArgumentException::class)
    }

    @Test
    fun `qualiteMax above 5 throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 6,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 5,
            )
        }.hasClass(IllegalArgumentException::class)
    }

    @Test
    fun `difficulteMin greater than difficulteMax throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 5,
                expectedDifficulteMin = 4,
                expectedDifficulteMax = 2,
            )
        }.hasClass(IllegalArgumentException::class)
    }

    @Test
    fun `difficulteMin below 1 throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 5,
                expectedDifficulteMin = 0,
                expectedDifficulteMax = 5,
            )
        }.hasClass(IllegalArgumentException::class)
    }

    @Test
    fun `difficulteMax above 5 throws`() {
        assertFailure {
            CalibrationAnswer(
                expectedQualiteMin = 1,
                expectedQualiteMax = 5,
                expectedDifficulteMin = 1,
                expectedDifficulteMax = 6,
            )
        }.hasClass(IllegalArgumentException::class)
    }
}
