# OAuth2 Player Sign-In — Phase 4b: Login route + OIDC wiring growth

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Subagents MUST invoke `jvm-backend` at session start. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add `GET /v1/auth/{provider}/login` — the entry point that calls `BeginOidcLoginUseCase`, validates `return_to` against an allow-list, and 302-redirects to the provider's `authorize_url` with PKCE + state. Grow `Wiring` to construct adapters for both `BeginOidcLoginUseCase` and `CompleteOidcLoginUseCase` so the callback PRs (4c Google, 4d Apple) only add route handlers — no DI changes.

**Architecture:**
- New route `LoginRoute.kt` registered in `Module.kt`.
- New helper `ReturnToValidator.kt` — exact-origin match against `IdentityApiConfig.allowedReturnOrigins`. Reject open-redirect attempts with 400 + `problem+json`.
- `Wiring.forProduction(...)` grows to instantiate: `StaticOidcProviderConfigSource`, `JwksCache`, `JoseOidcVerifier`, `KtorOidcCodeExchanger`, `PostgresUserProviderRepository`, `PostgresAuthAttemptRepository`, `UuidV7IdGenerator`, `SecureRandomFactory`.
- `Wiring` exposes `beginOidcLogin` (used by login route in this PR) and `completeOidcLogin` (consumed in 4c/4d).
- `Wiring.forTesting(...)` extended; remains throw-on-default for unset slots.

**Tech stack unchanged:** Ktor 3.4.3, Konsist, assertk, JUnit 5.

**Note on `JwksCache.defaultProduction`:** PR #491 already added this factory to `:identity:infrastructure`. Confirm it's still in main; do NOT re-add or modify it.

---

## Worktree setup

```bash
cd /Users/isho/IdeaProjects/bliss/.claude/worktrees/feat+oauth2
git fetch origin main
git checkout worktree-feat+oauth2
git reset --hard origin/main
git checkout -b feat/oauth2-login-route
```

Confirm: `identity/api/.../Wiring.kt` currently wires only `WhoAmIUseCase`. `identity/application/.../usecases/BeginOidcLoginUseCase.kt` exists.

---

## File Structure

**Create:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/auth/ReturnToValidator.kt`
- `identity/api/src/main/kotlin/com/bliss/identity/api/routes/LoginRoute.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/auth/ReturnToValidatorTest.kt`
- `identity/api/src/test/kotlin/com/bliss/identity/api/routes/LoginRouteTest.kt`

**Modify:**
- `identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt` — grow factories, add `beginOidcLogin` + `completeOidcLogin` fields.
- `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt` — mount login route.
- `identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt` — pass the additional dependencies `Wiring.forProduction` now needs (probably an `HttpClientEngine`, depending on what was merged in #491).

---

## Task: PR 4b — Login route + Wiring growth

**Branch:** `feat/oauth2-login-route`.

### 0 — Recon

- [ ] **Step 0a:** Read `identity/application/src/main/kotlin/com/bliss/identity/application/usecases/BeginOidcLoginUseCase.kt`. Confirm: the command type (`BeginOidcLoginCommand` fields), the result type (`authorizeUrl` and other fields), the error subclasses.

- [ ] **Step 0b:** Read `CompleteOidcLoginUseCase.kt` constructor signature so wiring constructs it correctly.

- [ ] **Step 0c:** Read the merged `identity/api/.../Wiring.kt` and `Main.kt`. Understand what `forProduction(...)` takes today (config + DataSource + HttpClient or engine — verify) and how the slimmer companion is shaped.

- [ ] **Step 0d:** Verify `identity/api/openapi.yaml` for `GET /v1/auth/{provider}/login`: query params, success status, error shapes.

- [ ] **Step 0e:** Read `identity/infrastructure/.../JwksCache.kt` to confirm `defaultProduction(...)` is present in main.

### 1 — Return-to validator

- [ ] **Step 1:** Create `ReturnToValidator.kt`:

```kotlin
package com.bliss.identity.api.auth

import java.net.URI

/**
 * Open-redirect guard. Accepts only return_to URLs whose scheme+host(+port) match
 * one of the configured allowed origins exactly. Path and query are unrestricted.
 */
class ReturnToValidator(private val allowedOrigins: List<String>) {
    private val normalized: Set<String> = allowedOrigins.map(::normalize).toSet()

    fun isAllowed(returnTo: String): Boolean {
        val uri = runCatching { URI.create(returnTo) }.getOrNull() ?: return false
        if (!uri.isAbsolute) return false
        if (uri.userInfo != null) return false
        val scheme = uri.scheme ?: return false
        val host = uri.host ?: return false
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val origin = "$scheme://$host$port".lowercase()
        return origin in normalized
    }

    private fun normalize(origin: String): String = origin.trim().lowercase().removeSuffix("/")
}
```

- [ ] **Step 2:** Create `ReturnToValidatorTest.kt`:

```kotlin
package com.bliss.identity.api.auth

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class ReturnToValidatorTest {
    private val v = ReturnToValidator(
        listOf("https://wordsparrow.io", "https://app.wordsparrow.io", "http://localhost:5173"),
    )

    @Test fun `exact origin allowed`() {
        assertThat(v.isAllowed("https://wordsparrow.io/play")).isTrue()
        assertThat(v.isAllowed("https://app.wordsparrow.io/account?x=1")).isTrue()
        assertThat(v.isAllowed("http://localhost:5173/")).isTrue()
    }

    @Test fun `wrong host rejected`() {
        assertThat(v.isAllowed("https://evil.com/")).isFalse()
        assertThat(v.isAllowed("https://wordsparrow.io.evil.com/")).isFalse()
    }

    @Test fun `wrong scheme rejected`() {
        assertThat(v.isAllowed("http://wordsparrow.io/")).isFalse()
    }

    @Test fun `wrong port rejected`() {
        assertThat(v.isAllowed("http://localhost:1234/")).isFalse()
    }

    @Test fun `userinfo rejected`() {
        assertThat(v.isAllowed("https://attacker@wordsparrow.io/")).isFalse()
    }

    @Test fun `relative url rejected`() {
        assertThat(v.isAllowed("/play")).isFalse()
    }

    @Test fun `malformed rejected`() {
        assertThat(v.isAllowed("ht!tps://wordsparrow.io")).isFalse()
        assertThat(v.isAllowed("")).isFalse()
    }
}
```

- [ ] **Step 3:** Run + commit:

```bash
./gradlew :identity:api:test --tests "com.bliss.identity.api.auth.ReturnToValidatorTest" --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/auth/ReturnToValidator.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/auth/ReturnToValidatorTest.kt
git commit -s -m "feat(identity-api): return_to allow-list validator"
```

### 2 — Wiring growth

- [ ] **Step 4:** Grow `Wiring.kt`. Read what's in main first to understand the current shape (PR 491 slimmed it to just `whoAmI`). Add `beginOidcLogin` + `completeOidcLogin` as additional fields:

```kotlin
class Wiring private constructor(
    val whoAmI: WhoAmIUseCase,
    val beginOidcLogin: BeginOidcLoginUseCase,
    val completeOidcLogin: CompleteOidcLoginUseCase,
) {
    companion object {
        fun forProduction(config: IdentityApiConfig, dataSource: DataSource, engine: HttpClientEngine): Wiring {
            val clock = SystemClock
            val idGen = UuidV7IdGenerator()
            val random = SecureRandomFactory()

            val users = PostgresUserRepository(dataSource)
            val userProviders = PostgresUserProviderRepository(dataSource)
            val sessions = PostgresSessionRepository(dataSource)
            val attempts = PostgresAuthAttemptRepository(dataSource)

            val providerConfigs = mapOf(
                Provider.GOOGLE to OidcProviderConfig(
                    provider = Provider.GOOGLE,
                    issuer = "https://accounts.google.com",
                    audience = config.google.clientId,
                    clientId = config.google.clientId,
                    authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
                    tokenUrl = "https://oauth2.googleapis.com/token",
                    jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
                    redirectUri = "https://${config.publicHost}/v1/auth/google/callback",
                    responseMode = OidcResponseMode.QUERY,
                    clientAuth = config.googleAuth,
                ),
                Provider.APPLE to OidcProviderConfig(
                    provider = Provider.APPLE,
                    issuer = "https://appleid.apple.com",
                    audience = config.apple.serviceId,
                    clientId = config.apple.serviceId,
                    authorizeUrl = "https://appleid.apple.com/auth/authorize",
                    tokenUrl = "https://appleid.apple.com/auth/token",
                    jwksUri = "https://appleid.apple.com/auth/keys",
                    redirectUri = "https://${config.publicHost}/v1/auth/apple/callback",
                    responseMode = OidcResponseMode.FORM_POST,
                    clientAuth = config.appleAuth,
                ),
            )
            val configSource = StaticOidcProviderConfigSource(providerConfigs)

            val jwksCache = JwksCache.defaultProduction(ttl = Duration.ofMinutes(5), clock = { clock.now() })
            val verifier: OidcVerifier = JoseOidcVerifier(jwksCache, clock = { clock.now() })
            val codeExchanger = KtorOidcCodeExchanger(configSource::get, engine, clock = { clock.now() })

            return Wiring(
                whoAmI = WhoAmIUseCase(users, sessions, clock, config.sessionMaxAge),
                beginOidcLogin = BeginOidcLoginUseCase(configSource, random, idGen, attempts, clock, config.attemptTtl),
                completeOidcLogin = CompleteOidcLoginUseCase(
                    attempts, codeExchanger, verifier, configSource,
                    users, userProviders, sessions, idGen, clock,
                ),
            )
        }

        fun forTesting(
            whoAmI: WhoAmIUseCase? = null,
            beginOidcLogin: BeginOidcLoginUseCase? = null,
            completeOidcLogin: CompleteOidcLoginUseCase? = null,
        ): Wiring = Wiring(
            whoAmI = whoAmI ?: throwUnset("whoAmI"),
            beginOidcLogin = beginOidcLogin ?: throwUnset("beginOidcLogin"),
            completeOidcLogin = completeOidcLogin ?: throwUnset("completeOidcLogin"),
        )

        private fun throwUnset(name: String): Nothing =
            error("Wiring.forTesting did not provide $name; the route under test must not call it.")
    }
}
```

If the merged `Wiring` uses nullable backing fields with non-null accessors (see PR 491 report), keep that pattern — just add `beginOidcLogin` and `completeOidcLogin` consistently. The shape above is illustrative; match what's in main.

- [ ] **Step 5:** Adjust `Main.kt` if needed. The `Wiring.forProduction(...)` signature now takes `HttpClientEngine` (or `HttpClient`, whatever PR 491 settled on). Make sure `Main.kt` still compiles.

- [ ] **Step 6:** Build + commit:

```bash
./gradlew :identity:api:build --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/Wiring.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Main.kt
git commit -s -m "feat(identity-api): wire BeginOidcLogin + CompleteOidcLogin use cases"
```

### 3 — Login route

- [ ] **Step 7:** Create `LoginRoute.kt`. The exact `BeginOidcLoginCommand` shape + error subclasses must match what Step 0a confirmed:

```kotlin
package com.bliss.identity.api.routes

import com.bliss.identity.api.auth.ReturnToValidator
import com.bliss.identity.api.dto.ProblemDetails
import com.bliss.identity.application.usecases.BeginOidcLoginCommand
import com.bliss.identity.application.usecases.BeginOidcLoginError
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.domain.provider.toProvider
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

fun Route.login(beginOidcLogin: BeginOidcLoginUseCase, returnTo: ReturnToValidator, json: Json = Json) {
    get("/v1/auth/{provider}/login") {
        val providerSlug = call.parameters["provider"] ?: return@get call.problem(
            json, HttpStatusCode.BadRequest, "invalid_provider", "Provider path parameter is missing.",
        )
        val provider: Provider = providerSlug.toProvider() ?: return@get call.problem(
            json, HttpStatusCode.BadRequest, "invalid_provider", "Unknown provider: $providerSlug.",
        )
        val returnToParam = call.request.queryParameters["return_to"] ?: return@get call.problem(
            json, HttpStatusCode.BadRequest, "missing_return_to", "return_to query parameter is required.",
        )
        if (!returnTo.isAllowed(returnToParam)) {
            return@get call.problem(
                json, HttpStatusCode.BadRequest, "disallowed_return_to",
                "return_to is not in the allow-list.",
            )
        }

        val result = try {
            beginOidcLogin.execute(BeginOidcLoginCommand(provider = provider, returnTo = returnToParam))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BeginOidcLoginError) {
            return@get call.problem(
                json, HttpStatusCode.BadGateway, e.problemType(),
                e.message ?: "Failed to begin login.",
            )
        }

        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondRedirect(url = result.authorizeUrl, permanent = false)
    }
}

private suspend fun RoutingCall.problem(
    json: Json, status: HttpStatusCode, type: String, detail: String,
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

private fun BeginOidcLoginError.problemType(): String = when (this) {
    is BeginOidcLoginError.UnknownProvider -> "unknown_provider"
    is BeginOidcLoginError.AttemptPersistFailed -> "attempt_persist_failed"
    // Add exhaustive `else` branches matching the actual sealed hierarchy from Step 0a.
}
```

If `BeginOidcLoginError` has different subclasses than these two, mirror them exhaustively. The `when` MUST be exhaustive.

- [ ] **Step 8:** Update `Module.kt`:

```kotlin
// Inside Application.module(wiring, config), before `routing { ... }`:
val returnToValidator = ReturnToValidator(config.allowedReturnOrigins)

routing {
    health()
    whoAmI(wiring.whoAmI)
    login(wiring.beginOidcLogin, returnToValidator)
}
```

- [ ] **Step 9:** Create `LoginRouteTest.kt` — 4 paths (unknown provider, missing return_to, disallowed return_to, valid):

```kotlin
package com.bliss.identity.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import com.bliss.identity.api.Wiring
import com.bliss.identity.api.config.AppleClientConfig
import com.bliss.identity.api.config.GoogleClientConfig
import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.api.module
// ... in-memory adapters + FixedClock + deterministic random
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.time.Instant

class LoginRouteTest {
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
    fun `unknown provider returns 400`() = testApplication {
        val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.get("/v1/auth/facebook/login?return_to=https://wordsparrow.example/play")
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `missing return_to returns 400`() = testApplication {
        val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.get("/v1/auth/google/login")
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `disallowed return_to returns 400`() = testApplication {
        val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.get("/v1/auth/google/login?return_to=https://evil.example/")
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `valid request returns 302 with Location and Cache-Control no-store`() = testApplication {
        val wiring = Wiring.forTesting(beginOidcLogin = newBeginUseCase())
        application { module(wiring, testConfig) }
        val client = createClient { followRedirects = false }
        val response = client.get("/v1/auth/google/login?return_to=https://wordsparrow.example/play")
        assertThat(response.status).isEqualTo(HttpStatusCode.Found)
        assertThat(response.headers["Location"]).isNotNull()
            .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
        assertThat(response.headers["Cache-Control"] ?: "").contains("no-store")
    }

    private fun newBeginUseCase(): BeginOidcLoginUseCase {
        // Construct a real BeginOidcLoginUseCase with in-memory adapters using the
        // pattern from identity/application/.../BeginOidcLoginUseCaseTest.kt.
        // Provider configs should mirror production: GOOGLE issuer/authorizeUrl/etc.
        TODO("Use the application-layer test as a template")
    }
}
```

The implementer must fill in `newBeginUseCase()` — see `identity/application/src/test/kotlin/.../BeginOidcLoginUseCaseTest.kt` for the exact in-memory wiring (adapters + `FixedClock` + deterministic random). Provider configs must include Google with `authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth"` so the redirect assertion holds.

- [ ] **Step 10:** Run + commit:

```bash
./gradlew :identity:api:test --quiet
./gradlew spotlessCheck --quiet
git add identity/api/src/main/kotlin/com/bliss/identity/api/routes/LoginRoute.kt \
        identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt \
        identity/api/src/test/kotlin/com/bliss/identity/api/routes/LoginRouteTest.kt
git commit -s -m "feat(identity-api): GET /v1/auth/{provider}/login route"
```

### 4 — Size check + PR

- [ ] **Step 11:** Check diff size:

```bash
git diff origin/main --shortstat
```

If over 400 added lines excluding blanks, consider deferring `CompleteOidcLogin` wiring to PR 4c (callbacks). The login route only needs `BeginOidcLogin`.

- [ ] **Step 12:** Push + open PR:

```bash
git push -u origin feat/oauth2-login-route
gh pr create --base main \
  --title "feat(identity-api): GET /v1/auth/{provider}/login + OIDC wiring" \
  --body "$(cat <<'BODY'
## Summary
- Adds `GET /v1/auth/{provider}/login` — entry point that calls `BeginOidcLoginUseCase` and 302-redirects to the provider's authorize URL with PKCE + state.
- Adds `ReturnToValidator` — exact-origin allow-list check against `IdentityApiConfig.allowedReturnOrigins`. Rejects open-redirect attempts (wrong host/scheme/port, user-info, malformed, relative URLs) with 400 + `problem+json`.
- Grows `Wiring.forProduction(...)` to construct adapters for both `BeginOidcLoginUseCase` and `CompleteOidcLoginUseCase` (config source, JwksCache, JoseOidcVerifier, KtorOidcCodeExchanger, attempt/user-provider repos, id-gen, random). Pre-wiring the complete-login adapters here keeps callback PRs small.

## Test plan
- [ ] `./gradlew :identity:api:test --quiet` — green; covers 4 login-route paths and 8 return-to-validator paths.
- [ ] `./gradlew :identity:api:build --quiet` — green.
- [ ] `./gradlew spotlessCheck --quiet` — green.
- [ ] ApiArchitectureTest still passes (no Nimbus import added to api).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"
```

Title is 67 chars.

---

## Future plans

When 4b merges:
- **4c** — `GET /v1/auth/google/callback` (query-string `code` + `state` → `CompleteOidcLoginUseCase` → issue session cookie + redirect to `returnTo`).
- **4d** — `POST /v1/auth/apple/callback` (form-post body parsing).
- **4e** — Logout + `/v1/users/me` routes.
- **4f** — Link routes.
