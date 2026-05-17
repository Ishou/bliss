# OAuth2 Player Sign-In — Phase 4h: POST /link route

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start.

**Goal:** Add `POST /v1/users/me/providers/{provider}/link` — auth-gated; calls `BeginOidcLoginUseCase` with `linkToUserId = current user`, returns the `authorize_url` that the frontend will redirect to. End-to-end linking only works after PR 4i lands the callback dispatch — this PR lands the entry point in isolation to stay within the 400-line cap.

**Architecture:**
- New route `LinkRoute.kt`.
- Auth-gated via `authenticated(whoAmI)`.
- Reuses `BeginOidcLoginUseCase` (the same use case the unauth login flow uses). The use case takes an optional `linkToUserId`; passing the auth user's id flips it into linking mode.
- Body parses `{ "return_to": "..." }` (verify against OpenAPI). Validated by `ReturnToValidator` (same allow-list as login).
- Returns 200 with `{ "authorize_url": "..." }` (the frontend explicitly navigates; we don't 302 from this route because the trigger is a button click, not a top-level navigation).

**Tech stack unchanged.**

**Followup:** PR 4i extends the existing Google/Apple callback routes to peek at the persisted attempt and dispatch into `CompleteProviderLinkUseCase` when `linkToUserId != null`. Until then, linking attempts that reach the callback fail with `LinkingNotSupportedHere` (409) — known limitation, not a defect of this PR.

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-link-route
```

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/dto/LinkRequest.kt`
- `identity/api/src/main/kotlin/com/bliss/identity/api/dto/LinkResponse.kt`
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/LinkRoute.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/LinkRouteTest.kt`

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — mount.

(No `Wiring` change — `beginOidcLogin` accessor is already exposed since PR #492.)

---

## Task: PR 4h — POST /link route

**Branch:** `feat/oauth2-link-route`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/application/.../usecases/BeginOidcLoginUseCase.kt`. Confirm: does `execute(...)` take `linkToUserId` as a command field, a separate parameter, or via a different command type? PR 4b's implementer noted it's a separate optional parameter to `execute(...)`. Verify the signature.

- [ ] **Step 0b:** Read `identity/api/openapi.yaml` for `POST /v1/users/me/providers/{provider}/link`. Confirm: request body shape (`return_to`?), response shape (`authorize_url` or `Location` header?), error responses. The response is likely 200 with `{authorize_url}` — but verify.

- [ ] **Step 0c:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/routes/LoginRoute.kt` (from #492) for the established begin-login flow + `BeginOidcLoginCommand` shape. The link route mirrors it closely.

- [ ] **Step 0d:** Read `identity/domain/.../provider/ProviderMapper.kt` for the `String.toProvider()` extension (throws on unknown — wrap in `runCatching` like LoginRoute does).

### 1 — DTOs

- [ ] **Step 1:** Create `LinkRequest.kt`:

```kotlin
package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LinkRequest(
    val returnTo: String,
)
```

(Field name might be `return_to` snake_case on the wire — verify OpenAPI. If so, add `@SerialName("return_to")` annotation.)

- [ ] **Step 2:** Create `LinkResponse.kt`:

```kotlin
package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LinkResponse(
    val authorizeUrl: String,
)
```

(Same — verify wire naming: `authorize_url` likely.)

### 2 — Route

- [ ] **Step 3:** Create `LinkRoute.kt`:

```kotlin
package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.ReturnToValidator
import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.api.dto.LinkRequest
import com.bliss.identity.api.dto.LinkResponse
import com.bliss.identity.application.usecases.BeginOidcLoginCommand
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.provider.toProvider
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

fun Route.link(
    beginOidcLogin: BeginOidcLoginUseCase,
    whoAmI: WhoAmIUseCase,
    returnTo: ReturnToValidator,
    json: Json = Json,
) {
    post("/v1/users/me/providers/{provider}/link") {
        val auth = call.authenticated(whoAmI) ?: return@post
        val providerSlug = call.parameters["provider"] ?: return@post call.problem(
            json, HttpStatusCode.BadRequest, "invalid_provider", "Provider path parameter is missing.",
        )
        val provider = runCatching { providerSlug.toProvider() }.getOrNull() ?: return@post call.problem(
            json, HttpStatusCode.BadRequest, "invalid_provider", "Unknown provider: $providerSlug.",
        )

        val request = try {
            call.receive<LinkRequest>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@post call.problem(
                json, HttpStatusCode.BadRequest, "invalid_body",
                "Request body must be {\"returnTo\": string}.",
            )
        }

        if (!returnTo.isAllowed(request.returnTo)) {
            return@post call.problem(
                json, HttpStatusCode.BadRequest, "disallowed_return_to",
                "return_to is not in the allow-list.",
            )
        }

        val result = beginOidcLogin.execute(
            BeginOidcLoginCommand(provider = provider, returnTo = request.returnTo),
            linkToUserId = auth.userId,
        )
        call.respond(HttpStatusCode.OK, LinkResponse(authorizeUrl = result.authorizeUrl))
    }
}
```

If `BeginOidcLoginUseCase.execute(...)` has a different signature (e.g. `linkToUserId` is a command field, not a separate parameter), adapt accordingly. PR 4b's implementer noted it was a separate parameter — verify against the actual source.

`call.problem(...)` lives in `CallbackErrorMapping.kt` (extracted in #494). Import + reuse.

- [ ] **Step 4:** Update `Module.kt` to mount. The link route needs `beginOidcLogin` + `whoAmI` + `returnToValidator`. Mirror the existing `login(...)` mount style:

```kotlin
wiring.beginOidcLoginOrNull?.let { begin ->
    wiring.whoAmIOrNull?.let { whoAmI ->
        link(begin, whoAmI, returnToValidator)
    }
}
```

### 3 — Test

- [ ] **Step 5:** Create `LinkRouteTest.kt`. 5 paths:

```kotlin
package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.startsWith
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
// ... BeginOidcLoginUseCase + WhoAmIUseCase + in-memory adapters + FixedClock
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.cookie
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant

class LinkRouteTest {
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
    fun `no cookie returns 401`() = testApplication {
        val (wiring, _) = newWiring()
        application { module(wiring, testConfig) }
        val response = client.post("/v1/users/me/providers/google/link") {
            contentType(ContentType.Application.Json)
            setBody("""{"returnTo":"https://wordsparrow.example/account"}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `unknown provider returns 400`() = testApplication { /* ... */ }

    @Test
    fun `missing body returns 400`() = testApplication { /* ... */ }

    @Test
    fun `disallowed return_to returns 400`() = testApplication {
        val (wiring, sessionId) = newWiring()
        application { module(wiring, testConfig) }
        val response = client.post("/v1/users/me/providers/google/link") {
            cookie(SessionCookies.NAME, sessionId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"returnTo":"https://evil.example/"}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `valid request returns 200 with authorize_url containing linkToUserId-bound state`() = testApplication {
        val (wiring, sessionId, attemptsRepo) = newWiringWithAttemptsAccess()
        application { module(wiring, testConfig) }
        val response = client.post("/v1/users/me/providers/google/link") {
            cookie(SessionCookies.NAME, sessionId.value.toString())
            contentType(ContentType.Application.Json)
            setBody("""{"returnTo":"https://wordsparrow.example/account"}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.bodyAsText()
        assertThat(body).contains("authorizeUrl")
        assertThat(body).contains("accounts.google.com")
        // The use case should have persisted an attempt with linkToUserId set:
        // val attempt = attemptsRepo.findAll() ... — assert linkToUserId == userId.
        // (Verify against the actual repository API; adjust to whatever inspection method exists.)
    }

    private fun newWiring(): Pair<Wiring, SessionId> = TODO("seed user + session, real BeginOidcLogin + WhoAmI")
    private fun newWiringWithAttemptsAccess(): Triple<Wiring, SessionId, InMemoryAuthAttemptRepository> =
        TODO("same as newWiring, but expose the InMemoryAuthAttemptRepository so test can inspect linkToUserId")
}
```

The valid-path test should ideally inspect the persisted attempt to confirm `linkToUserId` was set. If `InMemoryAuthAttemptRepository` doesn't have a list/find-all method, just assert the response shape and skip the persistence check (PR 4i's tests will cover the linkToUserId round-trip).

- [ ] **Step 6:** Run + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/dto/LinkRequest.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/dto/LinkResponse.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/routes/LinkRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/LinkRouteTest.kt
git commit -s -m "feat(identity-api): POST /v1/users/me/providers/{provider}/link route"
```

### 4 — PR

- [ ] **Step 7:** Size check + push + PR:

```bash
git diff origin/main --shortstat
git push -u origin feat/oauth2-link-route
gh pr create --base main \
  --title "feat(identity-api): POST /v1/users/me/providers/{provider}/link" \
  --body "$(cat <<'BODY'
## Summary
- Adds `POST /v1/users/me/providers/{provider}/link` — auth-gated; calls `BeginOidcLoginUseCase` with `linkToUserId = current user`, validates `returnTo` via the existing `ReturnToValidator`, returns 200 with `{authorizeUrl}`.
- The frontend redirects the user to `authorize_url` to authenticate with the new provider. The callback dispatcher that completes the link (`CompleteProviderLinkUseCase`) lands in PR 4i.
- No `Wiring` change — `beginOidcLogin` accessor is already exposed since PR #492.

## Test plan
- [ ] `./gradlew :identity:api:test` — green; covers no-cookie (401), unknown provider (400), missing body (400), disallowed return_to (400), valid (200 + authorize_url shape).
- [ ] `./gradlew :identity:api:build` — green.
- [ ] `./gradlew :identity:api:check` — green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 60 chars.

---

## Future plans

- **4i** — Callback dispatch: callbacks peek `AuthAttempt.linkToUserId`; if non-null, route into `CompleteProviderLinkUseCase` instead of `CompleteOidcLoginUseCase`. Requires adding `peek(state)` (or equivalent non-destructive read) to `AuthAttemptRepository`.
- **Phase 4.5** — Helm chart + ingress for `auth.wordsparrow.io` + Cloudflare DNS + Google/Apple OAuth client registration + secrets bootstrap + `docs/deploy.md`.
- **Phase 5** — Frontend.
- **Phase 6** — grid/game gating + production `UserDeletedBroadcaster` HTTP fan-out.
