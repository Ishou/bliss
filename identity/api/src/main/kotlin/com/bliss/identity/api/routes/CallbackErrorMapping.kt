package com.bliss.identity.api.routes

import com.bliss.identity.api.dto.ProblemDetails
import com.bliss.identity.application.usecases.CompleteOidcLoginError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import kotlinx.serialization.json.Json

// Shared problem-detail helpers for OIDC callback routes (ADR-0044). Used by
// GoogleCallbackRoute and AppleCallbackRoute so the error mapping stays in
// lockstep across providers.

internal fun CompleteOidcLoginError.toProblem(): Pair<HttpStatusCode, String> =
    when (this) {
        is CompleteOidcLoginError.UnknownState -> HttpStatusCode.BadRequest to "invalid_state"
        is CompleteOidcLoginError.StateExpired -> HttpStatusCode.BadRequest to "state_expired"
        is CompleteOidcLoginError.LinkingNotSupportedHere ->
            HttpStatusCode.Conflict to "linking_not_supported_here"
        is CompleteOidcLoginError.ExchangeRejected -> HttpStatusCode.ServiceUnavailable to "upstream_error"
        is CompleteOidcLoginError.OrphanedLink -> HttpStatusCode.InternalServerError to "internal_error"
    }

internal suspend fun RoutingCall.problem(
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
