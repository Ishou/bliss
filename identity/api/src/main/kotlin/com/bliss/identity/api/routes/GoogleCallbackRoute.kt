package com.bliss.identity.api.routes

import com.bliss.identity.api.REST_JSON
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.application.usecases.CompleteOidcLoginError
import com.bliss.identity.application.usecases.CompleteProviderLinkError
import com.bliss.identity.domain.oidc.OidcVerificationError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.bliss.identity.api.routes.GoogleCallbackRoute")

// GET /v1/auth/google/callback - ADR-0044. Dispatches to login or linking flow
// based on the persisted AuthAttempt. LoggedIn issues __Host-ws_session + 302;
// Linked only 302s (the user already has a valid session).
fun Route.googleCallback(
    dispatcher: CallbackDispatcher,
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
                dispatcher.dispatch(state = state, code = code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompleteOidcLoginError) {
                val (status, type) = e.toProblem()
                val detail =
                    if (status == HttpStatusCode.InternalServerError) "Internal error." else e.message ?: status.description
                return@get call.problem(json, status, type, detail)
            } catch (e: CompleteProviderLinkError) {
                val (status, type) = e.toProblem()
                return@get call.problem(json, status, type, e.message ?: status.description)
            } catch (e: OidcVerificationError) {
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
        when (result) {
            is CallbackDispatcher.Result.LoggedIn -> {
                SessionCookies.issue(call, result.sessionId, config.sessionMaxAge)
                call.respondRedirect(url = result.returnTo, permanent = false)
            }
            is CallbackDispatcher.Result.Linked -> {
                call.respondRedirect(url = result.returnTo, permanent = false)
            }
        }
    }
}
