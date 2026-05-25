package com.bliss.survey.application.usecases

import com.bliss.survey.application.csv.StyleGuideCsvWriter
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import java.time.Instant
import kotlin.math.max
import kotlin.math.sqrt

class ExportDatasetUseCase(
    private val items: SurveyItemRepository,
    private val ratings: RatingRepository,
    private val writer: StyleGuideCsvWriter,
) {
    suspend fun execute(
        minRatings: Int,
        since: Instant?,
        authWeight: Double,
        anonWeight: Double,
    ): String {
        val aggs =
            ratings
                .aggregateForExport(since)
                .filter { (it.qualiteAuthN + it.qualiteAnonN) >= minRatings }
                .sortedBy { it.itemId.value }
        val rows = mutableListOf(writer.header())
        for (agg in aggs) {
            val item = items.findById(agg.itemId) ?: continue
            val nAuth = agg.qualiteAuthN
            val nAnon = agg.qualiteAnonN
            val denom = authWeight * nAuth + anonWeight * nAnon
            if (denom == 0.0) continue
            val qMean = (authWeight * agg.qualiteAuthSum + anonWeight * agg.qualiteAnonSum) / denom
            val dMean = (authWeight * agg.difficulteAuthSum + anonWeight * agg.difficulteAnonSum) / denom
            val qStd =
                stdev(
                    agg.qualiteAuthSum,
                    agg.qualiteAnonSum,
                    agg.qualiteSquaredAuthSum,
                    agg.qualiteSquaredAnonSum,
                    nAuth,
                    nAnon,
                )
            val meta =
                linkedMapOf(
                    "qualite_mean" to "%.2f".format(qMean),
                    "qualite_n_auth" to nAuth.toString(),
                    "qualite_n_anon" to nAnon.toString(),
                    "qualite_stdev" to "%.2f".format(qStd),
                    "difficulte_mean" to "%.2f".format(dMean),
                    "difficulte_n_auth" to nAuth.toString(),
                    "difficulte_n_anon" to nAnon.toString(),
                    "flags" to agg.flagCount.toString(),
                    "source_batch" to item.sourceBatch,
                )
            rows += writer.toRow(item, meta)
        }
        return rows.joinToString("\n")
    }

    private fun stdev(
        authSum: Int,
        anonSum: Int,
        authSq: Int,
        anonSq: Int,
        nAuth: Int,
        nAnon: Int,
    ): Double {
        val n = nAuth + nAnon
        if (n < 2) return 0.0
        val mean = (authSum + anonSum).toDouble() / n
        val variance = ((authSq + anonSq).toDouble() / n) - mean * mean
        return sqrt(max(0.0, variance))
    }
}
