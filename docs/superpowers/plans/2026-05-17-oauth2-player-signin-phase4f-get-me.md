# OAuth2 Player Sign-In — Phase 4f: GET /v1/users/me

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start.

**Goal:** Add `GET /v1/users/me` — auth-gated; returns the authenticated user's profile + linked providers.

**Architecture:**
- New route `MeRoute.kt` (will host PATCH + DELETE in PRs 4g/4h too — kept as one file so all `/v1/users/me` handlers live together).
- Auth-gated via the existing `RoutingCall.authenticated(whoAmI)` middleware.
- `GetMeUseCase` is already wired in `Wiring.forProduction(...)`. Add the `getMeOrNull` peek accessor.
- Response DTO `MeResponse.kt` matches the OpenAPI `MeResponse` schema.

**Tech stack unchanged.**

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-get-me
```

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/MeRoute.kt`
- `identity/api/src/main/kotlin/com/bliss/identity/api/dto/MeResponse.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/MeRouteTest.kt`

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt` — add `getMeOrNull` accessor.
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — mount.

---

## Task: PR 4f — GET /v1/users/me

**Branch:** `feat/oauth2-get-me`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/GetMeUseCase.kt`. Confirm the query shape (`GetMeQuery(userId)`?), the result type (likely `GetMeResult` with `user`, `providers: List<UserProvider>` or similar), any error subclasses.

- [ ] **Step 0b:** Read `identity/api/openapi.yaml` for `GET /v1/users/me` — confirm the response schema. Match the field names + nesting EXACTLY (e.g. `{ userId, displayName, createdAt, providers: [{ provider, linkedAt }] }`). Use `kotlinx-datetime` or `Instant.toString()` per the rest of the codebase's convention — read another DTO (e.g. the response from `GoogleCallbackRoute`'s result or a similar route) to see how timestamps are serialized.

- [ ] **Step 0c:** Read the `WhoAmIResult` field set — `authenticated(...)` returns `WhoAmIResult` which carries `userId`. Use that as input to `GetMeUseCase`.

- [ ] **Step 0d:** Read `identity/domain/.../user/User.kt` and `UserProvider.kt` to know what fields are available for the DTO.

### 1 — Wiring peek accessor

- [ ] **Step 1:** Add to `Wiring.kt`:

```kotlin
internal val getMeOrNull: GetMeUseCase? get() = _getMe
```

- [ ] **Step 2:** Commit:

```bash
./gradlew :identity:api:build --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt
git commit -s -m "feat(identity-api): expose getMe peek accessor"
```

### 2 — Response DTO

- [ ] **Step 3:** Create `MeResponse.kt`. Shape mirrors the OpenAPI schema from Step 0b. Example skeleton (adjust to match the spec exactly):

```kotlin
package com.bliss.identity.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class MeResponse(
    val userId: String,
    val displayName: String,
    val createdAt: String,
    val providers: List<LinkedProviderDto>,
)

@Serializable
data class LinkedProviderDto(
    val provider: String,    // "google" | "apple"
    val linkedAt: String,
)
```

Use the same `Provider.toWire()` / `toString()` pattern used elsewhere in the codebase — check `identity/domain/.../provider/ProviderMapper.kt` for the wire-name convention (likely lowercase "google" / "apple"). Timestamps serialize as `Instant.toString()` (ISO-8601) unless the rest of the api uses something else.

### 3 — Route

- [ ] **Step 4:** Create `MeRoute.kt`. Pattern A (use auth result's userId):

```kotlin
package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.api.dto.LinkedProviderDto
import com.bliss.identity.api.dto.MeResponse
import com.bliss.identity.application.usecases.GetMeQuery
import com.bliss.identity.application.usecases.GetMeUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.me(getMe: GetMeUseCase, whoAmI: WhoAmIUseCase) {
    get("/v1/users/me") {
        val auth = call.authenticated(whoAmI) ?: return@get
        val result = getMe.execute(GetMeQuery(userId = auth.userId))
        call.respond(
            HttpStatusCode.OK,
            MeResponse(
                userId = result.user.id.value.toString(),
                displayName = result.user.displayName.value,
                createdAt = result.user.createdAt.toString(),
                providers = result.providers.map {
                    LinkedProviderDto(
                        provider = it.provider.toWire(),     // verify the wire-name extension exists
                        linkedAt = it.linkedAt.toString(),
                    )
                },
            ),
        )
    }
}
```

The exact field names on `GetMeResult`, `User`, and `UserProvider` must match Step 0a/0d. Adjust accordingly. If `GetMeUseCase` returns a single flattened object (not nested `user + providers`), adapt.

Errors: `GetMeUseCase` should not throw user-facing errors for an authenticated user (the user exists by virtue of authenticating). If it does throw something (e.g. `UserNotFound`), map to 500 — that's a server bug (session points to a deleted user), not a client error. The `StatusPages` catch-all handles uncaught throwables.

- [ ] **Step 5:** Update `Module.kt` to mount. Same pattern as logout:

```kotlin
wiring.getMeOrNull?.let { getMe ->
    wiring.whoAmIOrNull?.let { whoAmI ->
        me(getMe, whoAmI)
    }
}
```

### 4 — Test

- [ ] **Step 6:** Create `MeRouteTest.kt`. 3 paths:

```kotlin
package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
// ... GetMeUseCase + WhoAmIUseCase + in-memory adapters + FixedClock
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

class MeRouteTest {
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
        val response = client.get("/v1/users/me")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `valid session with no linked providers returns 200 with empty providers`() = testApplication {
        val (wiring, sessionId) = newWiring(linkProviders = false)
        application { module(wiring, testConfig) }
        val response = client.get("/v1/users/me") {
            cookie(SessionCookies.NAME, sessionId.value.toString())
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.bodyAsText()
        assertThat(body).contains("\"displayName\":\"Alice\"")
        assertThat(body).contains("\"providers\":[]")
    }

    @Test
    fun `valid session with linked Google provider returns 200 with provider entry`() = testApplication {
        val (wiring, sessionId) = newWiring(linkProviders = true)
        application { module(wiring, testConfig) }
        val response = client.get("/v1/users/me") {
            cookie(SessionCookies.NAME, sessionId.value.toString())
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.bodyAsText()
        assertThat(body).contains("\"provider\":\"google\"")    // adjust to actual wire name
    }

    private fun newWiring(linkProviders: Boolean = false): Pair<Wiring, SessionId> = TODO(
        "Build a Wiring with real GetMeUseCase + WhoAmIUseCase backed by InMemoryUserRepository + " +
        "InMemorySessionRepository + InMemoryUserProviderRepository. Seed a user + session and " +
        "optionally a UserProvider with provider=GOOGLE and a known linkedAt.",
    )
}
```

The implementer fills `newWiring()` — pattern is established in `WhoAmIRouteTest.kt` / `LogoutRouteTest.kt`. Adjust provider wire-name + body assertions to match real serialization.

- [ ] **Step 7:** Run + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/dto/MeResponse.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/routes/MeRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/MeRouteTest.kt
git commit -s -m "feat(identity-api): GET /v1/users/me route"
```

### 5 — PR

- [ ] **Step 8:** Size check + push + PR:

```bash
git diff origin/main --shortstat
git push -u origin feat/oauth2-get-me
gh pr create --base main \
  --title "feat(identity-api): GET /v1/users/me" \
  --body "$(cat <<'BODY'
## Summary
- Adds `GET /v1/users/me` — auth-gated; returns the authenticated user's profile (id, displayName, createdAt) plus linked providers (provider name + linkedAt timestamp).
- Adds `MeResponse` + `LinkedProviderDto` matching the OpenAPI schema.
- Exposes `Wiring.getMeOrNull` peek accessor matching the existing pattern.

## Test plan
- [ ] `./gradlew :identity:api:test` — green; covers no-cookie (401), valid session with no linked providers (200 + empty array), valid session with Google linked (200 + provider entry).
- [ ] `./gradlew :identity:api:build` — green.
- [ ] `./gradlew :identity:api:check` — green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 37 chars.

---

## Future plans

- **4g** — `PATCH /v1/users/me` (update display name) + `DELETE /v1/users/me` (close account).
- **4h** — `POST /v1/users/me/providers/{provider}/link` + linking-mode callback dispatch.
