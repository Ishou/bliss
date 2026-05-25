package com.bliss.survey.domain.calibration

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CalibrationAgreementTest {
    @Test
    fun `empty window yields null agreement`() {
        assertThat(CalibrationAgreement.rollingAgreement(emptyList(), windowSize = 20)).isEqualTo(null)
    }

    @Test
    fun `all-agree window yields 1_0`() {
        val results = List(20) { true }
        assertThat(CalibrationAgreement.rollingAgreement(results, 20)).isEqualTo(1.0)
    }

    @Test
    fun `all-disagree window yields 0_0`() {
        val results = List(20) { false }
        assertThat(CalibrationAgreement.rollingAgreement(results, 20)).isEqualTo(0.0)
    }

    @Test
    fun `monotonicity - adding a matching rating never decreases agreement`() =
        runTest {
            checkAll(Arb.list(Arb.boolean(), 1..50)) { history ->
                val before = CalibrationAgreement.rollingAgreement(history, 20) ?: 0.0
                val after = CalibrationAgreement.rollingAgreement(history + true, 20) ?: 0.0
                assertThat(after, "before=$before after=$after history=$history")
                    .isGreaterThanOrEqualTo(before)
            }
        }

    @Test
    fun `monotonicity - adding a non-matching rating never increases agreement`() =
        runTest {
            checkAll(Arb.list(Arb.boolean(), 1..50)) { history ->
                val before = CalibrationAgreement.rollingAgreement(history, 20) ?: 1.0
                val after = CalibrationAgreement.rollingAgreement(history + false, 20) ?: 1.0
                assertThat(after, "before=$before after=$after history=$history")
                    .isLessThanOrEqualTo(before)
            }
        }

    @Test
    fun `windowSize less than 1 throws`() {
        assertFailure { CalibrationAgreement.rollingAgreement(listOf(true), windowSize = 0) }
            .isInstanceOf(IllegalArgumentException::class)
        assertFailure { CalibrationAgreement.rollingAgreement(listOf(true), windowSize = -3) }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
