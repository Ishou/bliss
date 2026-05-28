package com.bliss.survey.application.usecases

import com.bliss.survey.application.csv.StyleGuideCsvParser
import com.bliss.survey.application.filters.FilterInput
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.filters.FilterResult
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Tier

class IngestBatchUseCase(
    private val parser: StyleGuideCsvParser,
    private val filters: FilterPipeline,
    private val items: SurveyItemRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    data class Report(
        val accepted: Int,
        val alreadyPresent: Int,
        val rejected: List<Pair<Int, String>>,
    )

    suspend fun execute(
        csvLines: List<String>,
        sourceBatch: String,
        tier: Tier,
    ): Report {
        val rejected = mutableListOf<Pair<Int, String>>()
        var ok = 0
        var present = 0
        for ((i, raw) in csvLines.drop(1).withIndex()) {
            val lineNumber = i + 2
            try {
                val parsed = parser.parseRow(raw, lineNumber = lineNumber)
                val r =
                    filters.run(
                        FilterInput(
                            mot = parsed.mot,
                            definition = parsed.definition,
                            pos = parsed.pos,
                            style = parsed.style,
                        ),
                    )
                if (r is FilterResult.Reject) {
                    rejected += lineNumber to "filter ${r.filterId}: ${r.reason}"
                    continue
                }
                val toInsert =
                    parsed.copy(
                        id = ItemId(ids.next()),
                        sourceBatch = sourceBatch,
                        tier = tier,
                        createdAt = clock.now(),
                    )
                val stored = items.insertIfAbsent(toInsert)
                if (stored.id == toInsert.id) ok++ else present++
            } catch (e: IllegalArgumentException) {
                rejected += lineNumber to "parse: ${e.message}"
            } catch (e: NumberFormatException) {
                rejected += lineNumber to "parse: ${e.message}"
            }
        }
        return Report(ok, present, rejected)
    }
}
