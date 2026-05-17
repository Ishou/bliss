package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.api.dto.LinkedProviderDto
import com.bliss.identity.api.dto.MeResponse
import com.bliss.identity.api.dto.UpdateMeRequest
import com.bliss.identity.application.usecases.DeleteUserCommand
import com.bliss.identity.application.usecases.DeleteUserError
import com.bliss.identity.application.usecases.DeleteUserUseCase
import com.bliss.identity.application.usecases.GetMeQuery
import com.bliss.identity.application.usecases.GetMeResult
import com.bliss.identity.application.usecases.GetMeUseCase
import com.bliss.identity.application.usecases.UpdateMeCommand
import com.bliss.identity.application.usecases.UpdateMeError
import com.bliss.identity.application.usecases.UpdateMeUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.infrastructure.provider.toWire
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

fun Route.me(
    getMe: GetMeUseCase,
    whoAmI: WhoAmIUseCase,
) {
    get("/v1/users/me") {
        val auth = call.authenticated(whoAmI) ?: return@get
        val result = getMe.execute(GetMeQuery(userId = auth.userId))
        call.respond(HttpStatusCode.OK, result.toMeResponse())
    }
}

fun Route.patchMe(
    updateMe: UpdateMeUseCase,
    getMe: GetMeUseCase,
    whoAmI: WhoAmIUseCase,
    json: Json = ME_PROBLEM_JSON,
) {
    patch("/v1/users/me") {
        val auth = call.authenticated(whoAmI) ?: return@patch
        val request =
            try {
                call.receive<UpdateMeRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return@patch call.problem(
                    json,
                    HttpStatusCode.BadRequest,
                    "invalid_body",
                    "Request body must be a JSON object matching UserUpdate.",
                )
            }
        try {
            updateMe.execute(
                UpdateMeCommand(
                    userId = auth.userId,
                    displayName = request.displayName,
                    emailOptIn = request.emailOptIn,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateMeError) {
            return@patch when (e) {
                is UpdateMeError.InvalidDisplayName ->
                    call.problem(
                        json,
                        HttpStatusCode.BadRequest,
                        "invalid_display_name",
                        e.message ?: "Display name is invalid.",
                    )
                is UpdateMeError.UserNotFound ->
                    call.problem(
                        json,
                        HttpStatusCode.Unauthorized,
                        "user_not_found",
                        "User does not exist.",
                    )
            }
        }
        val result = getMe.execute(GetMeQuery(userId = auth.userId))
        call.respond(HttpStatusCode.OK, result.toMeResponse())
    }
}

fun Route.deleteMe(
    deleteUser: DeleteUserUseCase,
    whoAmI: WhoAmIUseCase,
    json: Json = ME_PROBLEM_JSON,
) {
    delete("/v1/users/me") {
        val auth = call.authenticated(whoAmI) ?: return@delete
        try {
            deleteUser.execute(DeleteUserCommand(userId = auth.userId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeleteUserError) {
            return@delete when (e) {
                is DeleteUserError.UserNotFound ->
                    call.problem(
                        json,
                        HttpStatusCode.Unauthorized,
                        "user_not_found",
                        "User does not exist.",
                    )
                is DeleteUserError.BroadcastFailed ->
                    call.problem(
                        json,
                        HttpStatusCode.ServiceUnavailable,
                        "broadcast_failed",
                        "User-deleted broadcast failed; the account is not deleted.",
                    )
            }
        }
        SessionCookies.clear(call)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun GetMeResult.toMeResponse(): MeResponse =
    MeResponse(
        id = userId.value.toString(),
        displayName = displayName.value,
        createdAt = createdAt.toString(),
        providers =
            linkedProviders.map { lp ->
                LinkedProviderDto(
                    provider = lp.provider.toWire(),
                    linkedAt = lp.linkedAt.toString(),
                    emailOptIn = lp.emailOptIn,
                )
            },
    )

private val ME_PROBLEM_JSON: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
