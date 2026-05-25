package com.bliss.survey.api

import com.bliss.survey.api.config.SurveyApiConfig
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.RandomFactory
import com.bliss.survey.application.usecases.GetNextItemUseCase
import com.bliss.survey.application.usecases.SubmitRatingUseCase
import com.bliss.survey.domain.routing.StratifiedSampler
import com.bliss.survey.domain.routing.TierWeights
import com.bliss.survey.infrastructure.identity.CachedSessionVerifier
import com.bliss.survey.infrastructure.identity.IdentityClient
import com.bliss.survey.infrastructure.language.LinguaLanguageDetector
import com.bliss.survey.infrastructure.persistence.PgProposedByRepository
import com.bliss.survey.infrastructure.persistence.PgRatingRepository
import com.bliss.survey.infrastructure.persistence.PgSurveyItemRepository
import com.bliss.survey.infrastructure.persistence.PgUserProgressRepository
import com.bliss.survey.infrastructure.persistence.SurveyDatabase
import com.fasterxml.uuid.Generators
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.time.Instant
import kotlin.random.Random

// Production entry-point. Tests construct the Ktor module directly via
// `Application.surveyApiModule(wiring, config)`.
fun main() {
    val config = SurveyApiConfig.load()
    val dataSource = SurveyDatabase.create(config.jdbcUrl, config.dbUser, config.dbPassword)

    val items = PgSurveyItemRepository(dataSource)
    val ratings = PgRatingRepository(dataSource)
    val proposedBy = PgProposedByRepository(dataSource)
    val progress = PgUserProgressRepository(dataSource)

    val clock = Clock { Instant.now() }
    val ids = IdGenerator { Generators.timeBasedEpochGenerator().generate() }
    val randomFactory = RandomFactory { Random.Default }

    val languageDetector = LinguaLanguageDetector()
    val getNextItem = GetNextItemUseCase(items, StratifiedSampler(TierWeights.DEFAULT), randomFactory)
    val submitRating =
        SubmitRatingUseCase(
            items = items,
            ratings = ratings,
            proposedBy = proposedBy,
            progress = progress,
            filters = FilterPipeline.default(languageDetector),
            ids = ids,
            clock = clock,
        )

    val identityClient = IdentityClient(config.identityBaseUrl)
    val sessionVerifier = CachedSessionVerifier(identityClient)

    val wiring =
        Wiring(
            verifyCookie = { cookie -> sessionVerifier.verify(cookie) },
            getNextItem = getNextItem,
            submitRating = { cmd -> submitRating.execute(cmd) },
            items = items,
            proposedBy = proposedBy,
            userProgress = progress,
        )

    embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
        surveyApiModule(wiring, config)
    }.start(wait = true)
}
