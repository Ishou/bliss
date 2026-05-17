# OAuth2 Player Sign-In — Phase 4c: Google callback route

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add `GET /v1/auth/google/callback` — receives `code` + `state` from Google's redirect, calls `CompleteOidcLoginUseCase`, issues the `__Host-ws_session` cookie, and 302-redirects to the user's `return_to`.

**Architecture:**
- New route `GoogleCallbackRoute.kt` in `identity/api/.../routes/`.
- `CompleteOidcLoginUseCase` is already wired in `Wiring.forProduction(...)` (#491). This PR adds a `completeOidcLoginOrNull` peek accessor to `Wiring.kt` mirroring the pattern from #492, and mounts the route in `Module.kt`.
- Session cookie issued via the existing `SessionCookies.issue(...)` helper (no `Domain` attribute per `__Host-` prefix RFC).
- Error mapping (read the actual `CompleteOidcLoginError` sealed hierarchy first):
  - `UnknownState` / `StateExpired` → 400 `invalid_state`
  - `LinkingNotSupportedHere` → 409 `linking_not_supported_here`
  - `TokenExchangeFailed` / `IdTokenVerificationFailed` → 502 `upstream_error`
  - Anything else: map exhaustively, 502 for upstream failures, 500 for internal.
- `return_to` is recovered from the persisted `AuthAttempt` (the use case result includes it) — do not re-validate here since it was validated at `BeginOidcLogin` time.

**Tech stack unchanged.**

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-google-callback
```

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/GoogleCallbackRoute.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/GoogleCallbackRouteTest.kt`

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt` — add `completeOidcLoginOrNull` internal peek accessor.
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — mount `googleCallback(...)`.

---

## Task: PR 4c — Google callback

**Branch:** `feat/oauth2-google-callback`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/CompleteOidcLoginUseCase.kt` — confirm `CompleteOidcLoginCommand` shape, `CompleteOidcLoginResult` fields (must include `sessionId`, `returnTo`, ideally `userId`), and the `CompleteOidcLoginError` sealed-class subclasses.

- [ ] **Step 0b:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/auth/SessionCookies.kt` to confirm `issue(call, sessionId, maxAge)` signature (no `cookieDomain` per `__Host-` RFC fix in #491).

- [ ] **Step 0c:** Read `identity/api/.../Wiring.kt` — confirm `_completeOidcLogin` backing field exists and how the existing `*OrNull` accessors are written.

- [ ] **Step 0d:** Read `identity/api/openapi.yaml` for `/v1/auth/google/callback` — confirm query params (`code`, `state`, optional `error`) and response/error shapes.

### 1 — Wiring peek accessor

- [ ] **Step 1:** Add to `Wiring.kt`, next to the existing `beginOidcLoginOrNull` / `whoAmIOrNull`:

```kotlin
internal val completeOidcLoginOrNull: CompleteOidcLoginUseCase? get() = _completeOidcLogin
```

(Or in alphabetical/structural order to match the existing file. Match the surrounding style.)

- [ ] **Step 2:** Commit:

```bash
./gradlew :identity:api:build --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt
git commit -s -m "feat(identity-api): expose completeOidcLogin peek accessor"
```

### 2 — Callback route

- [ ] **Step 3:** Create `GoogleCallbackRoute.kt`. The exact `CompleteOidcLoginCommand`/`CompleteOidcLoginError` shapes must match Step 0a; the `when` over the error sealed class MUST be exhaustive:

```kotlin
package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.dto.ProblemDetails
import com.bliss.identity.application.usecases.CompleteOidcLoginCommand
import com.bliss.identity.application.usecases.CompleteOidcLoginError
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.domain.provider.Provider
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

fun Route.googleCallback(
    completeOidcLogin: CompleteOidcLoginUseCase,
    config: IdentityApiConfig,
    json: Json = Json,
) {
    get("/v1/auth/google/callback") {
        val params = call.request.queryParameters
        params["error"]?.let { error ->
            return@get call.problem(
                json, HttpStatusCode.BadRequest, "provider_error",
                "Provider returned error: $error",
            )
        }
        val code = params["code"] ?: return@get call.problem(
            json, HttpStatusCode.BadRequest, "missing_code", "code query parameter is required.",
        )
        val state = params["state"] ?: return@get call.problem(
            json, HttpStatusCode.BadRequest, "missing_state", "state query parameter is required.",
        )

        val result = try {
            completeOidcLogin.execute(
                CompleteOidcLoginCommand(provider = Provider.GOOGLE, code = code, state = state),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompleteOidcLoginError) {
            val (status, type) = e.toProblem()
            return@get call.problem(json, status, type, e.message ?: status.description)
        }

        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        SessionCookies.issue(call, result.sessionId, config.sessionMaxAge)
        call.respondRedirect(url = result.returnTo, permanent = false)
    }
}

private suspend fun RoutingCall.problem(
    json: Json,
    status: HttpStatusCode,
    type: String,
    detail: String,
) {
    val problem = ProblemDetails(
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

private fun CompleteOidcLoginError.toProblem(): Pair<HttpStatusCode, String> = when (this) {
    is CompleteOidcLoginError.UnknownState -> HttpStatusCode.BadRequest to "invalid_state"
    is CompleteOidcLoginError.StateExpired -> HttpStatusCode.BadRequest to "state_expired"
    is CompleteOidcLoginError.LinkingNotSupportedHere -> HttpStatusCode.Conflict to "linking_not_supported_here"
    // Add the remaining exhaustive branches from the real sealed hierarchy.
    // Default rule: upstream failures (token exchange / id-token verification / network) → 502 "upstream_error".
    //               anything else (persistence) → 500 "internal_error".
}
```

The `when` skeleton above lists guesses; the implementer must read Step 0a's enumeration and add every remaining subclass with a sensible mapping. If unsure, lean 502 for upstream errors and 500 for persistence errors.

Note: `CompleteOidcLoginCommand`'s field names may differ (`authorizationCode`? `oidcState`?) — match exactly.

- [ ] **Step 4:** Update `Module.kt` to mount:

```kotlin
import com.bliss.identity.api.routes.googleCallback
// ...
routing {
    health()
    wiring.whoAmIOrNull?.let { whoAmI(it) }
    wiring.beginOidcLoginOrNull?.let { login(it, returnToValidator) }
    wiring.completeOidcLoginOrNull?.let { googleCallback(it, config) }
}
```

- [ ] **Step 5:** Create `GoogleCallbackRouteTest.kt`. 5 paths:

```kotlin
package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
// ... CompleteOidcLoginUseCase + in-memory adapters + FixedClock
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant

class GoogleCallbackRouteTest {
    private val now = Instant.parse("2026-05-17T12:00:00Z")
    private val testConfig = IdentityApiConfig(
        port = 0,
        publicHost = "auth.wordsparrow.example",
        google = GoogleClientConfig("g-client", "g-secret"),
        apple = AppleClientConfig(
            "a-svc", "a-team", "a-key",
            "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----",
        ),
        allowedReturnOrigins = listOf("https://wordsparrow.example"),
    )

    @Test
    fun `provider error returns 400`() = testApplication {
        val wiring = Wiring.forTesting(completeOidcLogin = stubUseCase())
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.get("/v1/auth/google/callback?error=access_denied")
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `missing code returns 400`() = testApplication { /* ... */ }

    @Test
    fun `missing state returns 400`() = testApplication { /* ... */ }

    @Test
    fun `unknown state maps to 400`() = testApplication {
        // Wiring with real use case + in-memory attempts repo containing no state.
        val (wiring, _) = newWiringWithRealCompleteUseCase()
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.get("/v1/auth/google/callback?code=x&state=does-not-exist")
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `valid callback issues session cookie and 302s to return_to`() = testApplication {
        val (wiring, seeded) = newWiringWithRealCompleteUseCase()
        // Seed: pre-create an AuthAttempt + stub the verifier to accept the test code, returning a known id_token.
        // Then invoke /v1/auth/google/callback?code=...&state=seeded.state
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.get("/v1/auth/google/callback?code=${seeded.code}&state=${seeded.state}")
        assertThat(response.status).isEqualTo(HttpStatusCode.Found)
        assertThat(response.headers["Location"]).isEqualTo(seeded.returnTo)
        val cookies = response.setCookie()
        val session = cookies.firstOrNull { it.name == SessionCookies.NAME }
        assertThat(session).isNotNull()
        assertThat(session!!.value).isEqualTo(seeded.expectedSessionId.toString())
        assertThat(response.headers["Cache-Control"] ?: "").contains("no-store")
    }

    // The implementer fills these helpers from the application-layer test pattern:
    //   identity/application/src/test/kotlin/.../usecases/CompleteOidcLoginUseCaseTest.kt
    // shows how to wire the use case with in-memory repos + stub OidcVerifier/OidcCodeExchanger.
    private fun newWiringWithRealCompleteUseCase(): Pair<Wiring, SeededFixtures> = TODO()
    private fun stubUseCase(): CompleteOidcLoginUseCase = TODO()

    private data class SeededFixtures(
        val code: String,
        val state: String,
        val returnTo: String,
        val expectedSessionId: java.util.UUID,
    )
}
```

The implementer fills the `TODO`s by reading `CompleteOidcLoginUseCaseTest.kt` for the in-memory pattern. The valid-path test is the load-bearing assertion (Set-Cookie header has the right name + value, 302 Location matches the persisted attempt's return_to, Cache-Control no-store).

- [ ] **Step 6:** Run + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/routes/GoogleCallbackRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/GoogleCallbackRouteTest.kt
git commit -s -m "feat(identity-api): GET /v1/auth/google/callback route"
```

### 3 — PR

- [ ] **Step 7:** Size check + push + PR:

```bash
git diff origin/main --shortstat
git push -u origin feat/oauth2-google-callback
gh pr create --base main \
  --title "feat(identity-api): GET /v1/auth/google/callback" \
  --body "$(cat <<'BODY'
## Summary
- Adds `GET /v1/auth/google/callback` — receives `code` + `state` from Google's redirect, calls `CompleteOidcLoginUseCase`, issues the `__Host-ws_session` cookie, and 302-redirects to the user's `return_to`.
- Exposes `Wiring.completeOidcLoginOrNull` peek accessor so `Module` mounts the route only when wired (matches the pattern from PR #492).
- Error mapping: `UnknownState`/`StateExpired` → 400, `LinkingNotSupportedHere` → 409, token-exchange/id-token-verification failures → 502, everything else → 500. All errors return `application/problem+json`.

## Test plan
- [ ] `./gradlew :identity:api:test` — green; covers 5 callback-route paths (provider error, missing code, missing state, unknown state, valid).
- [ ] `./gradlew :identity:api:build` — green.
- [ ] `./gradlew :identity:api:check` — green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 47 chars.

---

## Future plans

- **4d** — `POST /v1/auth/apple/callback` (form-post body parsing; Apple uses `response_mode=form_post`).
- **4e** — `POST /v1/auth/logout` + `GET/PATCH/DELETE /v1/users/me` (all auth-gated).
- **4f** — `POST /v1/users/me/providers/{provider}/link` + linking-mode callback dispatch.
