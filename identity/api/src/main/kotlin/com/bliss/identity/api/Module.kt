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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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
        // Cookie-bearing requests require allowCredentials = true + explicit
        // origins; browsers reject Access-Control-Allow-Origin: * with
        // credentials.
        allowHost("wordsparrow.io", schemes = listOf("https"))
        allowHost("www.wordsparrow.io", schemes = listOf("https"))
        allowHost("bliss-cb4.pages.dev", schemes = listOf("https"))
        allowHost("localhost:5173", schemes = listOf("http"))

        // Ktor's CORS default covers GET/POST/HEAD/OPTIONS.
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)

        // Headers: wildcard-predicate per ADR-0034. The explicit `allowHeader`
        // approach this replaces caused two production incidents within hours
        // of the identity-api going live: X-Request-Id missing (#513) and
        // traceparent / tracestate missing (this PR) — the exact same shape
        // ADR-0034 documents for grid + game (PR-F.2). Each one a frontend
        // middleware silently attaching an outbound header.
        //
        // Credential-CORS safety: Ktor's `allowHeaders { true }` is a
        // PREDICATE — the plugin echoes back the specific headers requested
        // via Access-Control-Request-Headers, NOT a literal `*`. Browsers
        // accept the response with `Access-Control-Allow-Credentials: true`
        // because the emitted `Access-Control-Allow-Headers` enumerates each
        // header by name. The ADR-0034 warning about wildcard-incompatibility
        // with credentials applies to the literal `*` form, not Ktor's
        // predicate variant.
        //
        // The actual security perimeter remains origin allow-list + per-IP
        // rate limit at ingress (mirrors grid/game). ADR-0034 amendment to
        // cover identity-api lands in a follow-up doc PR.
        allowHeaders { true }

        // PATCH /v1/users/me sends `Content-Type: application/json`, which
        // the CORS spec classifies as non-simple. Without this flag, Ktor
        // rejects the actual (post-preflight) request with 403 + no
        // Access-Control-Allow-Origin, surfacing as the same misleading
        // "No 'Access-Control-Allow-Origin' header" error in the browser.
        // Mirrors grid/api Module.kt + game/api Module.kt.
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
