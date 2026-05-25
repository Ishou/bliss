package com.bliss.survey.application.filters

import com.bliss.survey.domain.model.Pos
import com.bliss.survey.domain.model.Style

data class FilterInput(
    val mot: String,
    val definition: String,
    val pos: Pos? = null,
    val style: Style? = null,
)

interface Filter {
    val id: Int

    fun apply(input: FilterInput): FilterResult
}
