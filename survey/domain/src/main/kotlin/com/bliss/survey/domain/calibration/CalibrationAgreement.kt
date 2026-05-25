package com.bliss.survey.domain.calibration

// Rolling agreement rate over the last windowSize history entries; null when history is empty.
object CalibrationAgreement {
    fun rollingAgreement(
        history: List<Boolean>,
        windowSize: Int,
    ): Double? {
        require(windowSize >= 1) { "windowSize must be >= 1" }
        if (history.isEmpty()) return null
        val tail = if (history.size <= windowSize) history else history.takeLast(windowSize)
        return tail.count { it }.toDouble() / tail.size
    }
}
