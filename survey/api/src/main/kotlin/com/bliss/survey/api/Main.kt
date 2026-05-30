package com.bliss.survey.api

import com.bliss.survey.api.config.SurveyApiConfig
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.RandomFactory
import com.bliss.survey.application.usecases.AnonymizeUserRatingsUseCase
import com.bliss.survey.application.usecases.GetCurrentCampaignUseCase
import com.bliss.survey.application.usecases.GetNextItemUseCase
import com.bliss.survey.application.usecases.GetNextPairUseCase
import com.bliss.survey.application.usecases.RecomputeTrainingWeightUseCase
import com.bliss.survey.application.usecases.SubmitPairRatingUseCase
import com.bliss.survey.application.usecases.SubmitRatingUseCase
import com.bliss.survey.domain.routing.StratifiedSampler
import com.bliss.survey.domain.routing.TierWeights
import com.bliss.survey.domain.weight.GoldWindowPolicy
import com.bliss.survey.infrastructure.identity.CachedSessionVerifier
import com.bliss.survey.infrastructure.identity.IdentityClient
import com.bliss.survey.infrastructure.language.LinguaLanguageDetector
import com.bliss.survey.infrastructure.nats.UserDeletedConsumer
import com.bliss.survey.infrastructure.nats.UserRoleChangedConsumer
import com.bliss.survey.infrastructure.persistence.PgCampaignRepository
import com.bliss.survey.infrastructure.persistence.PgMaintainerRoleRepository
import com.bliss.survey.infrastructure.persistence.PgPairRatingRepository
import com.bliss.survey.infrastructure.persistence.PgProposedByRepository
import com.bliss.survey.infrastructure.persistence.PgRatingRepository
import com.bliss.survey.infrastructure.persistence.PgSurveyItemRepository
import com.bliss.survey.infrastructure.persistence.PgUserProgressRepository
import com.bliss.survey.infrastructure.persistence.SurveyDatabase
import com.fasterxml.uuid.Generators
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.nats.client.Nats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.time.Instant
import kotlin.random.Random

// Production entry-point; tests use Application.surveyApiModule(wiring, config) directly.
fun main() {
    val config = SurveyApiConfig.load()
    val dataSource = SurveyDatabase.create(config.jdbcUrl, config.dbUser, config.dbPassword)

    val items = PgSurveyItemRepository(dataSource)
    val ratings = PgRatingRepository(dataSource)
    val pairRatings = PgPairRatingRepository(dataSource)
    val proposedBy = PgProposedByRepository(dataSource)
    val progress = PgUserProgressRepository(dataSource)
    val campaignRepository = PgCampaignRepository(dataSource)
    val maintainerRoles = PgMaintainerRoleRepository(dataSource)
    val goldPolicy = GoldWindowPolicy(config.goldCutoff, config.goldMultiplier)
    val recompute = RecomputeTrainingWeightUseCase(maintainerRoles, items, goldPolicy)

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
            campaigns = campaignRepository,
            recompute = recompute,
        )
    val getNextPair = GetNextPairUseCase(items)
    val submitPairRating =
        SubmitPairRatingUseCase(
            items = items,
            ratings = ratings,
            pairRatings = pairRatings,
            progress = progress,
            ids = ids,
            clock = clock,
            campaigns = campaignRepository,
        )
    val getCurrentCampaign = GetCurrentCampaignUseCase(campaignRepository)

    val identityClient = IdentityClient(config.identityBaseUrl)
    val sessionVerifier = CachedSessionVerifier(identityClient)

    // ADR-0049 — must start before Ktor serves so redelivery-on-boot events are captured.
    val anonymise = AnonymizeUserRatingsUseCase(ratings, proposedBy, items, progress, maintainerRoles)
    val natsConn = Nats.connect(config.natsUrl)
    val consumerScope = CoroutineScope(SupervisorJob())
    val userDeletedConsumer = UserDeletedConsumer(natsConn, anonymise, consumerScope)
    userDeletedConsumer.start()
    val userRoleChangedConsumer = UserRoleChangedConsumer(natsConn, recompute, consumerScope)
    userRoleChangedConsumer.start()

    val wiring =
        Wiring(
            verifyCookie = { cookie -> sessionVerifier.verify(cookie) },
            getNextItem = getNextItem,
            submitRating = { cmd -> submitRating.execute(cmd) },
            getNextPair = getNextPair,
            submitPairRating = { cmd -> submitPairRating.execute(cmd) },
            getCurrentCampaign = getCurrentCampaign,
            items = items,
            proposedBy = proposedBy,
            userProgress = progress,
            userDeletedConsumer = userDeletedConsumer,
            userRoleChangedConsumer = userRoleChangedConsumer,
            closeNats = { natsConn.close() },
        )

    embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
        surveyApiModule(wiring, config)
    }.start(wait = true)
}
