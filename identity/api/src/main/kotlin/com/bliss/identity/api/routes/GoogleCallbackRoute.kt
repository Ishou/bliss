package com.bliss.identity.api.routes

import com.bliss.identity.api.REST_JSON
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.dto.ProblemDetails
import com.bliss.identity.application.usecases.CompleteOidcLoginCommand
import com.bliss.identity.application.usecases.CompleteOidcLoginError
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.domain.oidc.OidcVerificationError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.bliss.identity.api.routes.GoogleCallbackRoute")

// GET /v1/auth/google/callback — ADR-0044. Google posts `code` + `state` as query
// parameters. Validates inputs, delegates to CompleteOidcLoginUseCase, issues the
// `__Host-ws_session` cookie via SessionCookies, and 302-redirects to the
// AuthAttempt's previously validated return_to.
fun Route.googleCallback(
    completeOidcLogin: CompleteOidcLoginUseCase,
    config: IdentityApiConfig,
    json: Json = REST_JSON,
) {
    get("/v1/auth/google/callback") {
        val params = call.request.queryParameters
        params["error"]?.let { providerError ->
            return@get call.problem(
                json,
                HttpStatusCode.BadRequest,
                "provider_error",
                "Provider returned error: $providerError.",
            )
        }
        val code =
            params["code"] ?: return@get call.problem(
                json,
                HttpStatusCode.BadRequest,
                "missing_code",
                "code query parameter is required.",
            )
        val state =
            params["state"] ?: return@get call.problem(
                json,
                HttpStatusCode.BadRequest,
                "missing_state",
                "state query parameter is required.",
            )

        val result =
            try {
                completeOidcLogin.execute(CompleteOidcLoginCommand(state = state, code = code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompleteOidcLoginError) {
                val (status, type) = e.toProblem()
                val detail =
                    if (status == HttpStatusCode.InternalServerError) "Internal error." else e.message ?: status.description
                return@get call.problem(json, status, type, detail)
            } catch (e: OidcVerificationError) {
                // when-as-expression on sealed hierarchy: compiler flags any new subclass.
                return@get when (e) {
                    is OidcVerificationError.JwksUnavailable,
                    is OidcVerificationError.Malformed,
                    ->
                        call.problem(
                            json,
                            HttpStatusCode.ServiceUnavailable,
                            "upstream_error",
                            e.message ?: "Token endpoint unreachable.",
                        )
                    is OidcVerificationError.InvalidSignature,
                    is OidcVerificationError.IssuerMismatch,
                    is OidcVerificationError.AudienceMismatch,
                    is OidcVerificationError.TokenExpired,
                    is OidcVerificationError.MissingSubject,
                    -> {
                        log.warn("OIDC token verification rejected (security signal): {}", e.message)
                        call.problem(
                            json,
                            HttpStatusCode.InternalServerError,
                            "internal_error",
                            "ID token verification failed.",
                        )
                    }
                }
            }

        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        SessionCookies.issue(call, result.sessionId, config.sessionMaxAge)
        call.respondRedirect(url = result.returnTo, permanent = false)
    }
}

private fun CompleteOidcLoginError.toProblem(): Pair<HttpStatusCode, String> =
    when (this) {
        is CompleteOidcLoginError.UnknownState -> HttpStatusCode.BadRequest to "invalid_state"
        is CompleteOidcLoginError.StateExpired -> HttpStatusCode.BadRequest to "state_expired"
        is CompleteOidcLoginError.LinkingNotSupportedHere ->
            HttpStatusCode.Conflict to "linking_not_supported_here"
        is CompleteOidcLoginError.ExchangeRejected -> HttpStatusCode.ServiceUnavailable to "upstream_error"
        is CompleteOidcLoginError.OrphanedLink -> HttpStatusCode.InternalServerError to "internal_error"
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
