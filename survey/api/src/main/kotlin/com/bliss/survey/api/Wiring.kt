package com.bliss.survey.api

import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.application.usecases.GetCurrentCampaignUseCase
import com.bliss.survey.application.usecases.GetNextItemUseCase
import com.bliss.survey.application.usecases.GetNextPairUseCase
import com.bliss.survey.application.usecases.SubmitPairRatingCommand
import com.bliss.survey.application.usecases.SubmitPairRatingResult
import com.bliss.survey.application.usecases.SubmitRatingCommand
import com.bliss.survey.application.usecases.SubmitRatingResult
import com.bliss.survey.infrastructure.nats.UserDeletedConsumer
import java.util.UUID

// Hand-rolled DI graph; Module.kt consumes this, Main.kt wires adapters, tests stub directly.
class Wiring(
    val verifyCookie: suspend (String) -> UUID?,
    val getNextItem: GetNextItemUseCase,
    val submitRating: suspend (SubmitRatingCommand) -> SubmitRatingResult,
    val getNextPair: GetNextPairUseCase,
    val submitPairRating: suspend (SubmitPairRatingCommand) -> SubmitPairRatingResult,
    val getCurrentCampaign: GetCurrentCampaignUseCase,
    val items: SurveyItemRepository,
    val proposedBy: ProposedByRepository,
    val userProgress: UserProgressRepository,
    val userDeletedConsumer: UserDeletedConsumer? = null,
    val closeNats: () -> Unit = {},
)
