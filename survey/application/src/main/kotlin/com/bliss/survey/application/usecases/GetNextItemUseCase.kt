package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.RandomFactory
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.SurveyItem
import com.bliss.survey.domain.model.UserId
import com.bliss.survey.domain.routing.StratifiedSampler

class GetNextItemUseCase(
    private val itemRepo: SurveyItemRepository,
    private val sampler: StratifiedSampler,
    private val randomFactory: RandomFactory,
) {
    suspend fun execute(
        forUser: UserId?,
        locallyExcluded: Set<ItemId>,
    ): SurveyItem? {
        val rng = randomFactory.create()
        repeat(MAX_ATTEMPTS) {
            val tier = sampler.pickTier(rng)
            val pick = itemRepo.pickUnratedForUser(forUser, tier, locallyExcluded)
            if (pick != null) return pick
        }
        return null
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
    }
}
