package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.routing.KCoveragePolicy

class RetireSaturatedItemsUseCase(
    private val items: SurveyItemRepository,
    private val policy: KCoveragePolicy,
    private val clock: Clock,
) {
    suspend fun execute(): Int {
        val ids = items.listSaturated(policy)
        val now = clock.now()
        for (id in ids) items.retire(id, now)
        return ids.size
    }
}
