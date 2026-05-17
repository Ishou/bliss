# OAuth2 Player Sign-In — Phase 4d: Apple callback route

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start.

**Goal:** Add `POST /v1/auth/apple/callback` — receives `code` + `state` from Apple via `response_mode=form_post` (`application/x-www-form-urlencoded`), calls `CompleteOidcLoginUseCase`, issues the `__Host-ws_session` cookie, and 302-redirects to `return_to`. Logic mirrors the Google callback (PR #493) — the only differences are the HTTP method, body parsing, and the Apple-specific `user` JSON field which is **ignored** (we only use `openid` scope per ADR-0045).

**Architecture:**
- New route `AppleCallbackRoute.kt`.
- Reuses `CompleteOidcLoginUseCase`, `SessionCookies.issue`, and the same `CompleteOidcLoginError`/`OidcVerificationError` mapping as Google. Centralize the error mapping if it makes the diff cleaner — but only if the helper fits without bloating the PR.
- Mounted via the existing `Wiring.completeOidcLoginOrNull` peek accessor.
- Apple may send an `error` field on cancellation (`error=user_cancelled_authorize`) — handle exactly like Google.
- Apple sends `user` on first sign-in: a JSON blob with `{name: {firstName, lastName}, email}`. **Read once, ignore** — ADR-0045 says no PII storage. Don't validate or store it.

**Tech stack unchanged.**

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-apple-callback
```

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/AppleCallbackRoute.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/AppleCallbackRouteTest.kt`

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — mount `appleCallback(...)`.

**Maybe modify** (only if it reduces duplication without bloating the PR):
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/GoogleCallbackRoute.kt` — extract `CompleteOidcLoginError.toProblem()` + the `problem(...)` helper into a shared `CallbackErrorMapping.kt` if both files would otherwise re-declare them. Use judgment; if extraction adds >20 lines net, skip.

---

## Task: PR 4d — Apple callback

**Branch:** `feat/oauth2-apple-callback`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/routes/GoogleCallbackRoute.kt` (just merged in #493). Verify:
  - The exact `CompleteOidcLoginCommand` shape (#493 found it's `(state, code)`, no provider).
  - Which `CompleteOidcLoginError` subclasses exist and their status codes.
  - Whether `OidcVerificationError` is caught separately and how it maps.
  - The exact `SessionCookies.issue(...)` and `respondRedirect` calls.

- [ ] **Step 0b:** Read `identity/api/openapi.yaml` for `POST /v1/auth/apple/callback` — confirm the content type, body fields (`code`, `state`, optional `error`, optional `user`), response/error shapes (likely 503 for upstream errors, matching Google).

- [ ] **Step 0c:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` to see the current routing block and the conditional-mount pattern.

### 1 — Route

- [ ] **Step 1:** Decide whether to extract shared error mapping from `GoogleCallbackRoute.kt` into a helper file. If yes, do that first as its own commit. If no (the Google route's private helpers are small enough to duplicate), proceed.

- [ ] **Step 2:** Create `AppleCallbackRoute.kt`. Mirror Google's structure, swapping query parameters for form parameters:

```kotlin
package com.bliss.identity.api.routes

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
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

fun Route.appleCallback(
    completeOidcLogin: CompleteOidcLoginUseCase,
    config: IdentityApiConfig,
    json: Json = Json,
) {
    post("/v1/auth/apple/callback") {
        val params = call.receiveParameters()

        params["error"]?.let { error ->
            return@post call.problem(
                json, HttpStatusCode.BadRequest, "provider_error",
                "Provider returned error: $error",
            )
        }
        val code = params["code"] ?: return@post call.problem(
            json, HttpStatusCode.BadRequest, "missing_code", "code form parameter is required.",
        )
        val state = params["state"] ?: return@post call.problem(
            json, HttpStatusCode.BadRequest, "missing_state", "state form parameter is required.",
        )
        // Apple's `user` field (first-sign-in only, JSON object with name/email) is intentionally
        // ignored — ADR-0045 mandates no PII retention beyond the `sub` claim.

        val result = try {
            completeOidcLogin.execute(CompleteOidcLoginCommand(state = state, code = code))
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompleteOidcLoginError) {
            val (status, type) = e.toProblem()
            return@post call.problem(json, status, type, e.message ?: status.description)
        } catch (e: OidcVerificationError) {
            return@post call.problem(
                json, HttpStatusCode.ServiceUnavailable, "upstream_error",
                e.message ?: "Upstream verification failed.",
            )
        }

        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        SessionCookies.issue(call, result.sessionId, config.sessionMaxAge)
        call.respondRedirect(url = result.returnTo, permanent = false)
    }
}

// If the Google route's helpers were extracted in Step 1, import them instead of re-declaring.
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
    is CompleteOidcLoginError.ExchangeRejected -> HttpStatusCode.ServiceUnavailable to "upstream_error"
    is CompleteOidcLoginError.OrphanedLink -> HttpStatusCode.InternalServerError to "internal_error"
}
```

The `when` and helpers above should mirror EXACTLY what `GoogleCallbackRoute.kt` already does. If the merged version has different mappings, copy those.

- [ ] **Step 3:** Update `Module.kt` to mount `appleCallback`:

```kotlin
import com.bliss.identity.api.routes.appleCallback
// ...
routing {
    health()
    wiring.whoAmIOrNull?.let { whoAmI(it) }
    wiring.beginOidcLoginOrNull?.let { login(it, returnToValidator) }
    wiring.completeOidcLoginOrNull?.let { googleCallback(it, config) }
    wiring.completeOidcLoginOrNull?.let { appleCallback(it, config) }
}
```

### 2 — Test

- [ ] **Step 4:** Create `AppleCallbackRouteTest.kt`. Mirror the Google test's structure but POST with form body. 5 paths (provider error, missing code, missing state, unknown state, valid):

```kotlin
package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
// ... CompleteOidcLoginUseCase + in-memory adapters + FixedClock — copy from GoogleCallbackRouteTest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant

class AppleCallbackRouteTest {
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
        // Stub-only wiring is enough — the route returns before calling the use case.
        val wiring = newWiring()
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response: HttpResponse = client.submitForm(
            url = "/v1/auth/apple/callback",
            formParameters = Parameters.build { append("error", "user_cancelled_authorize") },
        )
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `missing code returns 400`() = testApplication { /* form with only state */ }

    @Test
    fun `missing state returns 400`() = testApplication { /* form with only code */ }

    @Test
    fun `unknown state maps to 400`() = testApplication { /* form code+state, attempts repo empty */ }

    @Test
    fun `valid callback issues session cookie and 302s to return_to`() = testApplication {
        val (wiring, seeded) = newWiringWithSeededAttempt(provider = "APPLE")
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.submitForm(
            url = "/v1/auth/apple/callback",
            formParameters = Parameters.build {
                append("code", seeded.code)
                append("state", seeded.state)
                // Include the optional Apple "user" field to verify it's silently ignored:
                append("user", """{"name":{"firstName":"X","lastName":"Y"},"email":"x@y.com"}""")
            },
        )
        assertThat(response.status).isEqualTo(HttpStatusCode.Found)
        assertThat(response.headers["Location"]).isEqualTo(seeded.returnTo)
        val session = response.setCookie().firstOrNull { it.name == SessionCookies.NAME }
        assertThat(session).isNotNull()
        assertThat(response.headers["Cache-Control"] ?: "").contains("no-store")
    }

    private fun newWiring(): Wiring = TODO("stub Wiring for paths that don't reach the use case")
    private fun newWiringWithSeededAttempt(provider: String): Pair<Wiring, SeededFixtures> =
        TODO("Mirror GoogleCallbackRouteTest's helper — seed an AuthAttempt with provider=APPLE")

    private data class SeededFixtures(
        val code: String,
        val state: String,
        val returnTo: String,
    )
}
```

The implementer fills the helpers by copying from the merged `GoogleCallbackRouteTest.kt`. The valid-path test must seed the attempt with `provider = Provider.APPLE` so the use case picks the Apple config when exchanging.

- [ ] **Step 5:** Build + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/routes/AppleCallbackRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/AppleCallbackRouteTest.kt
git commit -s -m "feat(identity-api): POST /v1/auth/apple/callback route"
```

### 3 — PR

- [ ] **Step 6:** Size check + push + PR:

```bash
git diff origin/main --shortstat
git push -u origin feat/oauth2-apple-callback
gh pr create --base main \
  --title "feat(identity-api): POST /v1/auth/apple/callback" \
  --body "$(cat <<'BODY'
## Summary
- Adds `POST /v1/auth/apple/callback` — receives `code` + `state` from Apple's `response_mode=form_post` (`application/x-www-form-urlencoded`), calls `CompleteOidcLoginUseCase`, issues the `__Host-ws_session` cookie, and 302-redirects to the user's `return_to`.
- Mounts the route via the existing `Wiring.completeOidcLoginOrNull` accessor (no DI changes).
- Apple's optional `user` JSON field (first-sign-in name/email) is silently ignored per ADR-0045's no-PII-retention rule.
- Error mapping mirrors `GoogleCallbackRoute`: `UnknownState`/`StateExpired` → 400, `LinkingNotSupportedHere` → 409, `ExchangeRejected`/`OidcVerificationError` → 503, `OrphanedLink` → 500.

## Test plan
- [ ] `./gradlew :identity:api:test` — green; covers 5 callback-route paths (provider error, missing code, missing state, unknown state, valid).
- [ ] `./gradlew :identity:api:build` — green.
- [ ] `./gradlew :identity:api:check` — green (Spotless + Konsist).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 48 chars.

---

## Future plans

- **4e** — `POST /v1/auth/logout` + `GET/PATCH/DELETE /v1/users/me`.
- **4f** — `POST /v1/users/me/providers/{provider}/link` + linking-mode dispatch in callbacks.
