package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.domain.model.Campaign

class GetCurrentCampaignUseCase(
    private val campaigns: CampaignRepository,
) {
    suspend fun execute(): Campaign? = campaigns.findCurrent()
}
