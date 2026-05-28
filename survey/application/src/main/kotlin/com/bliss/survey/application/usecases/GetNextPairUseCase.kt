package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.ItemPair
import com.bliss.survey.domain.model.UserId

class GetNextPairUseCase(
    private val itemRepo: SurveyItemRepository,
) {
    suspend fun execute(
        forUser: UserId?,
        locallyExcluded: Set<ItemId>,
    ): ItemPair? = itemRepo.pickPairForUser(forUser, locallyExcluded)
}
