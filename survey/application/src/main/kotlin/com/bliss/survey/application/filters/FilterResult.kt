package com.bliss.survey.application.filters

sealed interface FilterResult {
    val filterId: Int
    val reason: String

    data class Accept(
        override val filterId: Int,
    ) : FilterResult {
        override val reason = "ok"
    }

    data class Warning(
        override val filterId: Int,
        override val reason: String,
    ) : FilterResult

    data class Reject(
        override val filterId: Int,
        override val reason: String,
    ) : FilterResult
}
