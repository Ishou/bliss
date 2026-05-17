package com.bliss.identity.api.routes

import com.bliss.identity.api.REST_JSON
import com.bliss.identity.api.auth.ReturnToValidator
import com.bliss.identity.api.dto.ProblemDetails
import com.bliss.identity.application.usecases.BeginOidcLoginCommand
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.infrastructure.provider.toProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json

// GET /v1/auth/{provider}/login — ADR-0044. Validates provider + return_to,
// then delegates to BeginOidcLoginUseCase and 302-redirects to the IdP authorize URL.
fun Route.login(
    beginOidcLogin: BeginOidcLoginUseCase,
    returnTo: ReturnToValidator,
    json: Json = REST_JSON,
) {
    get("/v1/auth/{provider}/login") {
        val providerSlug =
            call.parameters["provider"] ?: return@get call.problem(
                json,
                HttpStatusCode.BadRequest,
                "invalid_provider",
                "Provider path parameter is missing.",
            )
        val provider: Provider =
            runCatching { providerSlug.toProvider() }.getOrNull() ?: return@get call.problem(
                json,
                HttpStatusCode.BadRequest,
                "invalid_provider",
                "Unknown provider: $providerSlug.",
            )
        val returnToParam =
            call.request.queryParameters["return_to"] ?: return@get call.problem(
                json,
                HttpStatusCode.BadRequest,
                "missing_return_to",
                "return_to query parameter is required.",
            )
        if (!returnTo.isAllowed(returnToParam)) {
            return@get call.problem(
                json,
                HttpStatusCode.BadRequest,
                "disallowed_return_to",
                "return_to is not in the allow-list.",
            )
        }

        val result =
            beginOidcLogin.execute(
                BeginOidcLoginCommand(provider = provider, returnTo = returnToParam),
            )

        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondRedirect(url = result.authorizeUrl, permanent = false)
    }
}

private suspend fun RoutingCall.problem(
    json: Json,
    status: HttpStatusCode,
    type: String,
    detail: String,
) {
    val problem =
        ProblemDetails(
            type = "https://wordsparrow.io/errors/$type",
            title = status.description,
            status = status.value,
            detail = detail,
            instance = request.local.uri,
        )
    respondText(
        text = json.encodeToString(ProblemDetails.serializer(), problem),
        contentType = ContentType.parse("application/problem+json"),
        status = status,
    )
}
