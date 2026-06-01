package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.text.GlossNormalizer

data class LemmaMeta(
    val priorSenses: List<String>,
    val priorSubTags: List<String>,
)

class GetLemmaMetaUseCase(
    private val ratings: RatingRepository,
) {
    suspend fun execute(mot: String): LemmaMeta {
        val prior = ratings.priorMetaForMot(mot)
        return LemmaMeta(
            priorSenses = dedupByNormalized(prior.senses),
            priorSubTags = dedupByNormalized(prior.subTags),
        )
    }

    private fun dedupByNormalized(values: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(values.size)
        for (raw in values) {
            val key = GlossNormalizer.normalize(raw)
            if (key.isNotBlank() && seen.add(key)) out += raw
        }
        return out
    }
}
