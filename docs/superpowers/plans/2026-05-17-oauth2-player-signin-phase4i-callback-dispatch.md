# OAuth2 Player Sign-In — Phase 4i: Callback dispatch for linking

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start.

**Goal:** Make the Google/Apple callback routes branch on the persisted `AuthAttempt.linkToUserId`: if null, drive the existing login flow (`CompleteOidcLoginUseCase` → session + cookie + redirect); if non-null, drive the linking flow (`CompleteProviderLinkUseCase` → no session change, just redirect to `return_to`). After this PR, the link entry from PR #498 has a working end-to-end callback path.

**Architecture:**
- New helper `CallbackDispatcher.kt` in `identity/api/.../routes/`: takes `AuthAttemptRepository` + both use cases, exposes `dispatch(state, code): CallbackResult`. The sealed `CallbackResult` is either `LoggedIn(sessionId, returnTo)` or `Linked(returnTo)`.
- `CallbackDispatcher` peeks `attempts.findByState(state)` non-destructively to decide which use case to run. The use case itself then consumes the attempt as part of its existing read-and-delete flow.
- `Wiring` exposes `authAttemptRepositoryOrNull` (the port already exists in `identity/application/`; api importing application ports is allowed by the hexagonal rules).
- Google + Apple callback routes refactor to call `CallbackDispatcher.dispatch(...)` and `when` over the result: `LoggedIn` issues the `__Host-ws_session` cookie + redirect; `Linked` only redirects (the user already has a valid session).
- Error mapping is unchanged for `CompleteOidcLoginError`. Add mapping for `CompleteProviderLinkError` subclasses (verify the sealed hierarchy first).

**TOCTOU note:** Two concurrent requests with the same `state` could both observe `linkToUserId == null` and both call the login use case. The second one fails because the first deleted the row. Safe.

**Tech stack unchanged.**

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-callback-dispatch
```

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/CallbackDispatcher.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/CallbackDispatcherTest.kt`

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt` — add `authAttemptRepositoryOrNull` accessor + construct `CallbackDispatcher` in `forProduction(...)`. Or expose the dispatcher itself as `callbackDispatcherOrNull`. Picking the latter is cleaner — the dispatcher is the public surface; the repo doesn't need to be exposed.
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/GoogleCallbackRoute.kt` — refactor to call dispatcher + branch on result.
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/AppleCallbackRoute.kt` — same.
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/CallbackErrorMapping.kt` — extend `toProblem()` to cover `CompleteProviderLinkError`.
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — swap the use-case args on callback mounts for the dispatcher.
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/GoogleCallbackRouteTest.kt` — add a linking-mode valid path.
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/AppleCallbackRouteTest.kt` — same.

---

## Task: PR 4i — Callback dispatch

**Branch:** `feat/oauth2-callback-dispatch`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/application/.../usecases/CompleteProviderLinkUseCase.kt`. Confirm command shape (`CompleteProviderLinkCommand(state, code)`?), result fields (`returnTo`? `userId`?), and the `CompleteProviderLinkError` sealed-class subclasses.

- [ ] **Step 0b:** Read the `AuthAttemptRepository` port (in `identity/application/.../ports/`). Confirm `findByState(state): AuthAttempt?` exists and is non-destructive. (PR #498's implementer confirmed it does.) Read the `AuthAttempt` data class to know the `linkToUserId` field name + nullable type.

- [ ] **Step 0c:** Read the current `GoogleCallbackRoute.kt` + `AppleCallbackRoute.kt` (just refactored in #494) for the existing call signatures, error mapping shape, and the `CallbackErrorMapping.kt` helper.

- [ ] **Step 0d:** Read `identity/api/.../Wiring.kt` — note the current backing fields, accessor pattern, and the existing `forProduction` constructor.

- [ ] **Step 0e:** Read `identity/api/openapi.yaml` — confirm the callback response for linking mode. Is it the same redirect-only behavior, or does it return a different status?

### 1 — Dispatcher

- [ ] **Step 1:** Create `CallbackDispatcher.kt`:

```kotlin
package com.bliss.identity.api.routes

import com.bliss.identity.application.ports.AuthAttemptRepository
import com.bliss.identity.application.usecases.CompleteOidcLoginCommand
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.application.usecases.CompleteProviderLinkCommand
import com.bliss.identity.application.usecases.CompleteProviderLinkUseCase
import com.bliss.identity.domain.session.SessionId

/**
 * Routes the OIDC callback into either the login or linking flow based on the
 * persisted AuthAttempt. Non-destructive peek via `attempts.findByState(state)`;
 * the consuming use case still does its own read-and-delete.
 */
class CallbackDispatcher(
    private val attempts: AuthAttemptRepository,
    private val completeOidcLogin: CompleteOidcLoginUseCase,
    private val completeProviderLink: CompleteProviderLinkUseCase,
) {
    sealed class Result {
        data class LoggedIn(val sessionId: SessionId, val returnTo: String) : Result()
        data class Linked(val returnTo: String) : Result()
    }

    suspend fun dispatch(state: String, code: String): Result {
        val attempt = attempts.findByState(state)
        return if (attempt?.linkToUserId == null) {
            // Unknown state (attempt == null) OR plain login attempt:
            // let CompleteOidcLogin handle it. It will throw UnknownState for missing attempts.
            val r = completeOidcLogin.execute(CompleteOidcLoginCommand(state = state, code = code))
            Result.LoggedIn(sessionId = r.sessionId, returnTo = r.returnTo)
        } else {
            val r = completeProviderLink.execute(CompleteProviderLinkCommand(state = state, code = code))
            Result.Linked(returnTo = r.returnTo)
        }
    }
}
```

Verify exact field names against Step 0a + 0b. `CompleteProviderLinkResult` may have a different field for `returnTo` — match it.

- [ ] **Step 2:** Test the dispatcher in isolation. Create `CallbackDispatcherTest.kt`:

```kotlin
package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
// imports for in-memory adapters + real use cases + FixedClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant

class CallbackDispatcherTest {
    private val now = Instant.parse("2026-05-17T12:00:00Z")

    @Test
    fun `dispatches plain login attempt to CompleteOidcLogin`() = runBlocking {
        val fixtures = seed(linkToUserId = null)
        val result = fixtures.dispatcher.dispatch(state = fixtures.state, code = fixtures.code)
        assertThat(result).isInstanceOf(CallbackDispatcher.Result.LoggedIn::class)
    }

    @Test
    fun `dispatches linking attempt to CompleteProviderLink`() = runBlocking {
        val fixtures = seed(linkToUserId = fixtures.userId)
        val result = fixtures.dispatcher.dispatch(state = fixtures.state, code = fixtures.code)
        assertThat(result).isInstanceOf(CallbackDispatcher.Result.Linked::class)
        assertThat((result as CallbackDispatcher.Result.Linked).returnTo).isEqualTo(fixtures.returnTo)
    }

    @Test
    fun `unknown state propagates UnknownState from CompleteOidcLogin`() = runBlocking {
        val fixtures = seed(linkToUserId = null)
        // Don't actually seed — pass a state that isn't in the repo.
        try {
            fixtures.dispatcher.dispatch(state = "missing-state", code = "x")
            error("expected UnknownState")
        } catch (e: com.bliss.identity.application.usecases.CompleteOidcLoginError.UnknownState) {
            // ok
        }
    }

    private fun seed(linkToUserId: UserId?): Fixtures = TODO(
        "Build real CompleteOidcLoginUseCase + CompleteProviderLinkUseCase from in-memory repos + " +
        "stub OidcVerifier + OidcCodeExchanger. Seed one AuthAttempt with the given linkToUserId, " +
        "return its state + the code/id_token combo the stubs will accept.",
    )

    private data class Fixtures(
        val dispatcher: CallbackDispatcher,
        val state: String,
        val code: String,
        val returnTo: String,
        val userId: UserId,
    )
}
```

The implementer fills `seed()` by reusing the pattern from `GoogleCallbackRouteTest.kt`'s valid-path fixtures.

### 2 — Wiring

- [ ] **Step 3:** Modify `Wiring.kt`. Construct `CallbackDispatcher` in `forProduction(...)` using the already-instantiated `attempts`, `completeOidcLogin`, `completeProviderLink`. Add a backing field `_callbackDispatcher` + a non-null accessor + a `callbackDispatcherOrNull` peek accessor:

```kotlin
// In the private constructor params:
private val _callbackDispatcher: CallbackDispatcher? = null,

// In the body, alongside other `*OrNull` accessors:
internal val callbackDispatcherOrNull: CallbackDispatcher? get() = _callbackDispatcher

// In forProduction(...):
val callbackDispatcher = CallbackDispatcher(attempts, completeOidcLoginUseCase, completeProviderLinkUseCase)
return Wiring(
    // ... existing args
    _callbackDispatcher = callbackDispatcher,
)

// In forTesting(...) — accept an optional CallbackDispatcher param so tests can inject.
```

(Match the exact backing-field + accessor style already in the file.)

- [ ] **Step 4:** Commit:

```bash
./gradlew :identity:api:build --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/routes/CallbackDispatcher.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/CallbackDispatcherTest.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt
git commit -s -m "feat(identity-api): CallbackDispatcher routes login vs link by attempt"
```

### 3 — Refactor callbacks

- [ ] **Step 5:** Refactor `GoogleCallbackRoute.kt`. The signature changes from `(completeOidcLogin, config)` to `(dispatcher, config)`. The handler now:

```kotlin
fun Route.googleCallback(dispatcher: CallbackDispatcher, config: IdentityApiConfig, json: Json = Json) {
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
            dispatcher.dispatch(state = state, code = code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompleteOidcLoginError) {
            val (status, type) = e.toProblem()
            return@get call.problem(json, status, type, e.message ?: status.description)
        } catch (e: CompleteProviderLinkError) {
            val (status, type) = e.toProblem()
            return@get call.problem(json, status, type, e.message ?: status.description)
        } catch (e: OidcVerificationError) {
            return@get call.problem(json, HttpStatusCode.ServiceUnavailable, "upstream_error", e.message ?: "Upstream verification failed.")
        }

        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        when (result) {
            is CallbackDispatcher.Result.LoggedIn -> {
                SessionCookies.issue(call, result.sessionId, config.sessionMaxAge)
                call.respondRedirect(url = result.returnTo, permanent = false)
            }
            is CallbackDispatcher.Result.Linked -> {
                // No cookie change — the user already has a valid session.
                call.respondRedirect(url = result.returnTo, permanent = false)
            }
        }
    }
}
```

- [ ] **Step 6:** Mirror in `AppleCallbackRoute.kt` — same refactor, just POST + `receiveParameters`.

- [ ] **Step 7:** Extend `CallbackErrorMapping.kt`. Add an extension on `CompleteProviderLinkError`:

```kotlin
internal fun CompleteProviderLinkError.toProblem(): Pair<HttpStatusCode, String> = when (this) {
    is CompleteProviderLinkError.UnknownState -> HttpStatusCode.BadRequest to "invalid_state"
    is CompleteProviderLinkError.StateExpired -> HttpStatusCode.BadRequest to "state_expired"
    is CompleteProviderLinkError.ProviderAlreadyLinkedToOtherUser ->
        HttpStatusCode.Conflict to "provider_linked_to_other_user"
    is CompleteProviderLinkError.ExchangeRejected ->
        HttpStatusCode.ServiceUnavailable to "upstream_error"
    // Add the remaining exhaustive branches from the actual sealed hierarchy (Step 0a).
}
```

Verify the subclass names from Step 0a — the list above is a guess.

- [ ] **Step 8:** Update `Module.kt`. Swap the callback mounts:

```kotlin
wiring.callbackDispatcherOrNull?.let { dispatcher ->
    googleCallback(dispatcher, config)
    appleCallback(dispatcher, config)
}
```

(Remove the old `wiring.completeOidcLoginOrNull?.let { ... }` callback mounts. Those use cases are still wired and accessible via `Wiring`, but the callbacks now go through the dispatcher.)

### 4 — Route tests

- [ ] **Step 9:** Update `GoogleCallbackRouteTest.kt`:
  - Existing tests need their `Wiring.forTesting(completeOidcLogin = ...)` calls swapped to `Wiring.forTesting(callbackDispatcher = ...)`. Construct the dispatcher with real adapters in the test helper.
  - Add one new test: linking-mode valid → 302, no Set-Cookie header, redirected to attempt.returnTo. Assert the persisted `UserProvider` was created server-side (read via the in-memory user-provider repo).

- [ ] **Step 10:** Mirror in `AppleCallbackRouteTest.kt`.

- [ ] **Step 11:** Build + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/routes/GoogleCallbackRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/routes/AppleCallbackRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/routes/CallbackErrorMapping.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/GoogleCallbackRouteTest.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/AppleCallbackRouteTest.kt
git commit -s -m "feat(identity-api): callbacks dispatch via CallbackDispatcher"
```

### 5 — PR

- [ ] **Step 12:** Size check + push + PR:

```bash
git diff origin/main --shortstat
git push -u origin feat/oauth2-callback-dispatch
gh pr create --base main \
  --title "feat(identity-api): callback dispatch for linking flow" \
  --body "$(cat <<'BODY'
## Summary
- Adds `CallbackDispatcher` — peeks the persisted `AuthAttempt` via `findByState(state)` and routes the OIDC callback to either `CompleteOidcLoginUseCase` (login) or `CompleteProviderLinkUseCase` (linking) based on `linkToUserId`.
- Refactors `GoogleCallbackRoute` + `AppleCallbackRoute` to call the dispatcher and `when` over the sealed result: `LoggedIn` issues the `__Host-ws_session` cookie + 302; `Linked` only 302s (the user already has a valid session).
- Adds `CompleteProviderLinkError.toProblem()` to `CallbackErrorMapping` with exhaustive status mapping.
- Exposes `Wiring.callbackDispatcherOrNull`; the dispatcher is constructed in `forProduction(...)` from `AuthAttemptRepository` + both use cases.

## Test plan
- [ ] `./gradlew :identity:api:test` — green; `CallbackDispatcherTest` covers login/link/unknown-state branches; Google + Apple route tests gain a linking-mode valid path (302, no Set-Cookie, persisted UserProvider).
- [ ] `./gradlew :identity:api:build` — green.
- [ ] `./gradlew :identity:api:check` — green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 54 chars.

---

## Future plans

After 4i merges, the OAuth2 sign-in + linking flow is feature-complete on the backend.

- **OpenAPI cleanup** — schema-only PR to add the `requestBody` for `POST /v1/users/me/providers/{provider}/link` (gap flagged in PR #498).
- **Phase 4.5** — Helm chart + ingress for `auth.wordsparrow.io` + Cloudflare DNS + Google/Apple OAuth client registration + `kubectl create secret` bootstrap + `docs/deploy.md`.
- **Phase 5** — Frontend sign-in route + account screen.
- **Phase 6** — grid/game gating + production `UserDeletedBroadcaster` HTTP fan-out + (likely) session revocation on user delete.
