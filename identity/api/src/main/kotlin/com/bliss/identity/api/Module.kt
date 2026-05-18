package com.bliss.identity.api

import com.bliss.identity.api.auth.ReturnToValidator
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.dto.ProblemDetails
import com.bliss.identity.api.routes.appleCallback
import com.bliss.identity.api.routes.deleteMe
import com.bliss.identity.api.routes.googleCallback
import com.bliss.identity.api.routes.health
import com.bliss.identity.api.routes.link
import com.bliss.identity.api.routes.login
import com.bliss.identity.api.routes.logout
import com.bliss.identity.api.routes.me
import com.bliss.identity.api.routes.patchMe
import com.bliss.identity.api.routes.whoAmI
import com.bliss.identity.infrastructure.events.NatsConnectionFactory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID
import javax.sql.DataSource

// Production entry point: opens NATS connection, registers ApplicationStopped close hook (SIGTERM drain), delegates to wiring overload.
fun Application.module(
    config: IdentityApiConfig,
    dataSource: DataSource,
    httpClientEngine: HttpClientEngine,
    natsUrl: String,
) {
    val (natsConnection, jetStream) = NatsConnectionFactory(natsUrl).connect()
    monitor.subscribe(ApplicationStopped) { natsConnection.close() }
    module(Wiring.forProduction(config, dataSource, httpClientEngine, jetStream), config)
}

// Wires Ktor plugins + routes for the identity bounded context (ADR-0044).
// Plugin install order — CallId → CallLogging → DefaultHeaders → ContentNegotiation → StatusPages — mirrors
// game/api so correlation IDs flow into both the access log and error responses.
fun Application.module(
    wiring: Wiring,
    config: IdentityApiConfig,
) {
    val returnToValidator = ReturnToValidator(config.allowedReturnOrigins)

    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotEmpty() && it.length <= 128 }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("correlation_id")
    }

    install(DefaultHeaders) {
        header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("X-Frame-Options", "DENY")
        header(HttpHeaders.Server, "WordSparrow")
    }

    install(CORS) {
        allowHost("wordsparrow.io", schemes = listOf("https"))
        allowHost("www.wordsparrow.io", schemes = listOf("https"))
        allowHost("bliss-cb4.pages.dev", schemes = listOf("https"))
        allowHost("localhost:5173", schemes = listOf("http"))

        // Ktor's CORS default covers GET/POST/HEAD/OPTIONS.
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)

        // wildcard predicate (echoes request headers, not literal "*") — ADR-0048
        allowHeaders { true }

        // non-simple Content-Type (application/json on PATCH); mirrors grid/game
        allowNonSimpleContentTypes = true

        allowCredentials = true
        maxAgeInSeconds = 600
    }

    install(ContentNegotiation) {
        json(REST_JSON)
    }

    install(StatusPages) {
        // RFC 7807 catch-all per ADR-0003 §6. IllegalArgumentException maps to 400; everything else 500.
        exception<IllegalArgumentException> { call, cause ->
            val problem =
                ProblemDetails(
                    type = "https://wordsparrow.io/errors/invalid_request",
                    title = "Requête invalide",
                    status = HttpStatusCode.BadRequest.value,
                    detail = cause.message,
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = REST_JSON.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.BadRequest,
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log
                .error("unhandled_exception", cause)
            val problem =
                ProblemDetails(
                    type = "https://wordsparrow.io/errors/internal",
                    title = "Erreur interne du serveur",
                    status = HttpStatusCode.InternalServerError.value,
                    detail = "An unexpected error occurred.",
                    instance = call.request.local.uri,
                )
            call.respondText(
                text = REST_JSON.encodeToString(ProblemDetails.serializer(), problem),
                contentType = ContentType.parse("application/problem+json"),
                status = HttpStatusCode.InternalServerError,
            )
        }
    }

    routing {
        health()
        wiring.whoAmIOrNull?.let { whoAmI(it) }
        wiring.beginOidcLoginOrNull?.let { login(it, returnToValidator) }
        wiring.callbackDispatcherOrNull?.let { dispatcher ->
            googleCallback(dispatcher, config)
            appleCallback(dispatcher, config)
        }
        wiring.logoutOrNull?.let { logout ->
            wiring.whoAmIOrNull?.let { whoAmI ->
                logout(logout, whoAmI)
            }
        }
        wiring.getMeOrNull?.let { getMe ->
            wiring.whoAmIOrNull?.let { whoAmI ->
                me(getMe, whoAmI)
                wiring.updateMeOrNull?.let { updateMe ->
                    patchMe(updateMe, getMe, whoAmI)
                }
            }
        }
        wiring.deleteUserOrNull?.let { deleteUser ->
            wiring.whoAmIOrNull?.let { whoAmI ->
                deleteMe(deleteUser, whoAmI)
            }
        }
        wiring.beginOidcLoginOrNull?.let { begin ->
            wiring.whoAmIOrNull?.let { whoAmI ->
                link(begin, whoAmI, returnToValidator)
            }
        }
    }
}

// encodeDefaults invariant — ADR-0003 §6. Mirrors game/api's REST_JSON.
internal val REST_JSON: Json =
    Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
