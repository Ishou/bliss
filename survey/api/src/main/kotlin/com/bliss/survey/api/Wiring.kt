package com.bliss.survey.api

import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.application.usecases.GetNextItemUseCase
import com.bliss.survey.application.usecases.SubmitRatingCommand
import com.bliss.survey.application.usecases.SubmitRatingResult
import java.util.UUID

// Hand-rolled DI graph for survey-api. Module.kt consumes this; Main.kt wires
// adapters; tests construct directly with stubs. Mirrors identity/api but
// stays smaller because survey-api has fewer mount points.
class Wiring(
    val verifyCookie: suspend (String) -> UUID?,
    val getNextItem: GetNextItemUseCase,
    val submitRating: suspend (SubmitRatingCommand) -> SubmitRatingResult,
    val items: SurveyItemRepository,
    val proposedBy: ProposedByRepository,
    val userProgress: UserProgressRepository,
)
