# OAuth2 Player Sign-In — Phase 4e: Logout route

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start.

**Goal:** Add `POST /v1/auth/logout` — auth-gated; calls `LogoutUseCase` to revoke the session server-side, clears the `__Host-ws_session` cookie, returns 204 No Content.

**Architecture:**
- New route `LogoutRoute.kt`. Auth-gated via the existing `RoutingCall.authenticated(whoAmI)` middleware.
- New `SessionCookies.clear(call)` helper signature is already present (from #491); use it.
- `LogoutUseCase` is already constructed in `Wiring.forProduction(...)`. Add the `logoutOrNull` peek accessor mirroring the pattern from #492/#493.
- Idempotent: clearing the cookie is safe regardless of the use case's success. The use case revokes the session row; if it doesn't exist or is already revoked, that's still success (204).

**Tech stack unchanged.**

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-logout-route
```

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/LogoutRoute.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/LogoutRouteTest.kt`

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt` — add `logoutOrNull` internal accessor.
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — mount.

---

## Task: PR 4e — Logout

**Branch:** `feat/oauth2-logout-route`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/LogoutUseCase.kt`. Confirm the command shape (`LogoutCommand(sessionId)`?), the result type, and whether it throws / returns errors.

- [ ] **Step 0b:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/auth/SessionCookies.kt` to confirm `clear(call)` signature.

- [ ] **Step 0c:** Read `identity/api/src/main/kotlin/com/bliss/identity/api/auth/AuthenticatedCall.kt` — `RoutingCall.authenticated(whoAmI)`. Confirm: returns the `WhoAmIResult` (which carries `userId` + `sessionId`?) or just user identity. If the result doesn't carry `sessionId`, read the cookie directly via `SessionCookies.read(call)`.

- [ ] **Step 0d:** Read `identity/api/openapi.yaml` for `POST /v1/auth/logout` — confirm 204 success, auth requirement.

### 1 — Wiring peek accessor

- [ ] **Step 1:** Add to `Wiring.kt`, in the existing block of `*OrNull` accessors:

```kotlin
internal val logoutOrNull: LogoutUseCase? get() = _logout
```

(Match the surrounding style.)

- [ ] **Step 2:** Commit:

```bash
./gradlew :identity:api:build --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt
git commit -s -m "feat(identity-api): expose logout peek accessor"
```

### 2 — Route

- [ ] **Step 3:** Create `LogoutRoute.kt`. The exact pattern depends on Step 0a/0c; choose between two patterns:

**Pattern A** — auth-gated, uses authenticated user's session id:

```kotlin
package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.auth.authenticated
import com.bliss.identity.application.usecases.LogoutCommand
import com.bliss.identity.application.usecases.LogoutUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.logout(logout: LogoutUseCase, whoAmI: WhoAmIUseCase) {
    post("/v1/auth/logout") {
        val result = call.authenticated(whoAmI) ?: return@post
        logout.execute(LogoutCommand(sessionId = result.sessionId))
        SessionCookies.clear(call)
        call.respond(HttpStatusCode.NoContent)
    }
}
```

**Pattern B** — if the auth middleware doesn't expose `sessionId` and reading via `SessionCookies.read(call)` is cleaner. Pick A if possible; fall back to B if `WhoAmIResult` doesn't expose `sessionId`.

If `LogoutUseCase` returns errors (not just successes), map exhaustively via the same `problem()` helper from #494's `CallbackErrorMapping.kt`. Idempotent semantics: "session not found" or "already revoked" → 204, not 4xx. Logout from an expired/revoked session is a no-op success.

Note: per the spec, logout requires authentication. If `authenticated(...)` returns null (no/invalid cookie), it already responds 401 + problem+json — the route short-circuits.

- [ ] **Step 4:** Update `Module.kt` to mount:

```kotlin
wiring.logoutOrNull?.let { logout ->
    wiring.whoAmIOrNull?.let { whoAmI ->
        logout(logout, whoAmI)
    }
}
```

(Match the existing `*OrNull?.let { ... }` style. If the existing routes do `wiring.whoAmIOrNull?.let { whoAmI(it) }` plus `wiring.beginOidcLoginOrNull?.let { login(it, returnToValidator) }`, replicate that pattern. The logout route needs both `LogoutUseCase` and `WhoAmIUseCase`.)

### 3 — Test

- [ ] **Step 5:** Create `LogoutRouteTest.kt`. 3 paths:

```kotlin
package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.auth.SessionCookies
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
// ... LogoutUseCase + WhoAmIUseCase + in-memory adapters + FixedClock
import io.ktor.client.request.cookie
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

class LogoutRouteTest {
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
        val (wiring, _, _) = newWiring()
        application { module(wiring, testConfig) }
        val response = client.post("/v1/auth/logout")
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `valid session returns 204 and clears cookie and revokes session`() = testApplication {
        val (wiring, sessionId, sessions) = newWiring()
        application { module(wiring, testConfig) }
        val response = client.post("/v1/auth/logout") {
            cookie(SessionCookies.NAME, sessionId.value.toString())
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
        val cleared = response.setCookie().firstOrNull { it.name == SessionCookies.NAME }
        assertThat(cleared).isNotNull()
        // Max-Age 0 (or expires in the past) signals deletion:
        assertThat(cleared!!.maxAge).isEqualTo(0)
        // Session should be revoked server-side:
        val persisted = runBlocking { sessions.findById(sessionId) }
        assertThat(persisted?.revokedAt).isNotNull()
    }

    @Test
    fun `already-revoked session is idempotent — still 204`() = testApplication {
        val (wiring, sessionId, sessions) = newWiring()
        runBlocking { sessions.revoke(sessionId, now) }
        application { module(wiring, testConfig) }
        val response = client.post("/v1/auth/logout") {
            cookie(SessionCookies.NAME, sessionId.value.toString())
        }
        // If the WhoAmI middleware rejects revoked sessions, this will be 401 instead.
        // Verify against the actual middleware behavior:
        //  - If 401: that's correct (revoked session can't authenticate to logout).
        //  - If 204: also correct (idempotent endpoint).
        // Pick whichever matches the WhoAmIUseCase + AuthenticatedCall implementation.
        // The test should assert the actually-correct status.
        assertThat(response.status).isIn(HttpStatusCode.NoContent, HttpStatusCode.Unauthorized)
    }

    private fun newWiring(): Triple<Wiring, SessionId, InMemorySessionRepository> = TODO(
        "Build a Wiring with real LogoutUseCase + WhoAmIUseCase backed by in-memory User/Session repos. " +
        "Seed one user + one active session. Return the session id + the repo so the test can inspect server-side state.",
    )
}
```

The implementer fills `newWiring()` by mirroring `WhoAmIRouteTest.kt`'s pattern (already in main). Verify which import of `assertk.assertions.isIn` exists — if assertk doesn't have `isIn` for HttpStatusCode, use `assertThat(response.status.value).isIn(204, 401)` over Int.

- [ ] **Step 6:** Run + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/routes/LogoutRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/LogoutRouteTest.kt
git commit -s -m "feat(identity-api): POST /v1/auth/logout route"
```

### 4 — PR

- [ ] **Step 7:** Size check + push + PR:

```bash
git diff origin/main --shortstat
git push -u origin feat/oauth2-logout-route
gh pr create --base main \
  --title "feat(identity-api): POST /v1/auth/logout" \
  --body "$(cat <<'BODY'
## Summary
- Adds `POST /v1/auth/logout` — auth-gated; calls `LogoutUseCase` to revoke the session server-side, clears the `__Host-ws_session` cookie, returns 204 No Content.
- Exposes `Wiring.logoutOrNull` peek accessor matching the existing pattern.

## Test plan
- [ ] `./gradlew :identity:api:test` — green; covers no-cookie (401), valid session (204 + revoked + cleared cookie), revoked-session idempotency.
- [ ] `./gradlew :identity:api:build` — green.
- [ ] `./gradlew :identity:api:check` — green (Spotless + Konsist).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 40 chars.

---

## Future plans

- **4f** — `GET /v1/users/me` (read user profile + linked providers).
- **4g** — `PATCH /v1/users/me` (update display name) + `DELETE /v1/users/me` (close account).
- **4h** — `POST /v1/users/me/providers/{provider}/link` + linking-mode dispatch in callbacks.
