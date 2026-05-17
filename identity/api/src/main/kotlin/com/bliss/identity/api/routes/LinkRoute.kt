package com.bliss.identity.api.routes

import com.bliss.identity.api.REST_JSON
import com.bliss.identity.api.auth.ReturnToValidator
import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.api.dto.LinkRequest
import com.bliss.identity.api.dto.LinkResponse
import com.bliss.identity.application.usecases.BeginOidcLoginCommand
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.infrastructure.provider.toProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

// POST /v1/users/me/providers/{provider}/link — ADR-0044. Auth-gated; passes
// the current user as linkToUserId to BeginOidcLoginUseCase so the eventual
// callback (PR 4i) dispatches into CompleteProviderLinkUseCase. Returns the
// IdP authorize URL; the frontend navigates the browser to it.
fun Route.link(
    beginOidcLogin: BeginOidcLoginUseCase,
    whoAmI: WhoAmIUseCase,
    returnTo: ReturnToValidator,
    json: Json = REST_JSON,
) {
    post("/v1/users/me/providers/{provider}/link") {
        val auth = call.authenticated(whoAmI) ?: return@post
        val providerSlug =
            call.parameters["provider"] ?: return@post call.problem(
                json,
                HttpStatusCode.BadRequest,
                "invalid_provider",
                "Provider path parameter is missing.",
            )
        val provider: Provider =
            runCatching { providerSlug.toProvider() }.getOrNull() ?: return@post call.problem(
                json,
                HttpStatusCode.BadRequest,
                "invalid_provider",
                "Unknown provider: $providerSlug.",
            )

        val request =
            try {
                call.receive<LinkRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return@post call.problem(
                    json,
                    HttpStatusCode.BadRequest,
                    "invalid_body",
                    "Request body must be {\"returnTo\": string}.",
                )
            }

        if (!returnTo.isAllowed(request.returnTo)) {
            return@post call.problem(
                json,
                HttpStatusCode.BadRequest,
                "disallowed_return_to",
                "returnTo is not in the allow-list.",
            )
        }

        val result =
            beginOidcLogin.execute(
                BeginOidcLoginCommand(provider = provider, returnTo = request.returnTo),
                linkToUserId = auth.userId,
            )
        call.respond(HttpStatusCode.OK, LinkResponse(authorizeUrl = result.authorizeUrl))
    }
}
