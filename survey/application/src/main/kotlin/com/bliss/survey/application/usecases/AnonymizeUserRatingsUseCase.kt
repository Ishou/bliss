package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.UserId

class AnonymizeUserRatingsUseCase(
    private val ratings: RatingRepository,
    private val proposedBy: ProposedByRepository,
    private val items: SurveyItemRepository,
    private val progress: UserProgressRepository,
) {
    suspend fun execute(userId: UserId) {
        val optedOut = proposedBy.listOptedOutByUser(userId)
        if (optedOut.isNotEmpty()) items.deleteByIds(optedOut)
        ratings.anonymiseForUser(userId)
        proposedBy.deleteByUser(userId)
        progress.deleteByUser(userId)
    }
}
