# Phase 6b — Hint history per user_id Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Grid-api keys hint usage by `user_id` (from `__Secure-ws_session` cookie) instead of `X-Session-Id`, with a race-free write path and a NATS consumer for GDPR cleanup on `user.deleted`.

**Architecture:** Mirror Phase 6c's shape in grid-api: `CookieVerifier` port + `HttpCookieVerifier` adapter (cached read path + new cache-bypassing `verifyFresh` for writes), Flyway V6 cutover migration, `pg_advisory_xact_lock(hashtext('user:' || user_id))` + fresh re-verify under lock on every hint INSERT, JetStream durable consumer `grid-user-deleted` for cascade-delete.

**Tech Stack:** Kotlin 2.3.21, Ktor 3.4.3 server + client, PostgreSQL + Flyway, NATS JetStream (jnats), testcontainers (Postgres + NATS), assertk, JUnit 5, React 19 + TanStack Router (frontend).

**Spec:** `docs/superpowers/specs/2026-05-19-phase6b-hint-history-per-user-design.md`. Read it first.

**Sub-PR phasing** (one branch per sub-PR; **6b.1 exceeds the ≤400-line cap by design** — see rationale below):

| # | Branch | Title |
| - | --- | --- |
| 6b.0 | `feat/grid-cookie-verifier` | feat(grid-api): CookieVerifier port + HttpCookieVerifier adapter |
| 6b.1 | `feat/grid-hint-history-user-id` | feat(grid-api): hint history per user_id + race-free write |
| 6b.2 | `feat/grid-user-deleted-consumer` | feat(grid-events): NATS user.deleted consumer cascade-deletes hint rows |
| 6b.3 | `feat/frontend-hint-history-server` | feat(frontend-grid): read hintsRemaining from puzzle GET |

Dispatch order: 6b.0 first. Once 6b.0 merges, dispatch 6b.1 (combined schema + backend impl + minimal frontend caller migration). 6b.2 starts after 6b.1 merges. 6b.3 (frontend domain `hintsRemaining` plumbing + button state) runs after 6b.1.

### Why 6b.1 bundles schema + impl + caller migration (un-split)

The original plan split 6b.1 into a schema-only PR (`6b.1-schema`) and a Kotlin-impl PR (`6b.1-impl`) per ADR-0001 §3 "schema-first" guidance. We tried that — PR #536 was the schema-only attempt. It does not work in this codebase:

1. **ADR-0003 §8.2 + `.github/workflows/openapi-typescript-drift.yml`** require `frontend/src/infrastructure/api/grid/types.ts` to stay in lockstep with `grid/api/openapi.yaml`. A schema-only PR that touches the YAML must include the regenerated TS types or the drift gate fails.
2. **`frontend/package.json` build script** = `pnpm panda:codegen && tsc -b && vite build && pnpm seo:postbuild`. The regenerated `types.ts` drops `X-Session-Id` from the hints POST `params.header`; the runtime caller `frontend/src/infrastructure/api/grid/HttpPuzzleSolver.ts` still passes it. `tsc -b` fails → `pnpm build` fails → `deploy-frontend.yml` fails on push to main.

A schema-only PR has no clean path: revert TS regen (drift CI fails), shim with `@ts-expect-error` (ships dead code to prod), or drop X-Session-Id from the caller (breaks live hint POSTs at runtime until 6b.1-impl lands). The honest call: bundle schema + backend impl + the minimal frontend caller migration into one PR. The trade-off is a single oversized PR; the alternative is a sequence of PRs each of which leaves the tree broken or the build red.

---

## Sub-PR 6b.0 — CookieVerifier port + HttpCookieVerifier adapter

**Branch:** `feat/grid-cookie-verifier` off `main`.

**Goal:** Bring grid-api the same cookie-verify contract as game-api, plus the new `verifyFresh` method that bypasses the cache (required by §5 of the spec). No route changes in this PR — DI wired, no callers yet.

**Files:**
- Create: `grid/application/src/main/kotlin/com/bliss/grid/application/auth/CookieVerifier.kt`
- Create: `grid/application/src/main/kotlin/com/bliss/grid/application/auth/WhoAmI.kt`
- Create: `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/auth/HttpCookieVerifier.kt`
- Create: `grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/auth/HttpCookieVerifierTest.kt`
- Modify: `grid/api/src/main/kotlin/com/bliss/grid/api/Module.kt` (wire `HttpCookieVerifier` into DI; not used by any route yet)

**Reference:** game-api's `HttpCookieVerifier.kt` (read it in full) — port the same code; the only delta is the additional `verifyFresh` method.

### Task 1: WhoAmI data class

Grid-domain doesn't have `UserId` or `Pseudonym` yet. Either reuse `java.util.UUID` directly for `UserId` (acceptable — grid's domain is thin and doesn't need a value class for what it just passes through), or mint local value classes mirroring game's. **Decision:** keep it lightweight — use `UUID` directly and a `String` for displayName. The application port surfaces a `WhoAmI(userId: UUID, displayName: String)`.

No dedicated test — `WhoAmI` is a Kotlin `data class`, so field round-trip is language-guaranteed (CLAUDE.md "Testing": don't test trivial getters/delegation). Behaviour is covered implicitly by `HttpCookieVerifierTest` (Task 3).

- [ ] **Step 1: Implement WhoAmI**

`grid/application/src/main/kotlin/com/bliss/grid/application/auth/WhoAmI.kt`:
```kotlin
package com.bliss.grid.application.auth

import java.util.UUID

data class WhoAmI(
    val userId: UUID,
    val displayName: String,
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :grid:application:compileKotlin` → PASS.

### Task 2: CookieVerifier port

- [ ] **Step 1: Define the interface**

`grid/application/src/main/kotlin/com/bliss/grid/application/auth/CookieVerifier.kt`:
```kotlin
package com.bliss.grid.application.auth

interface CookieVerifier {
    /** Cached verify (≤30 s TTL). Use on read paths. */
    suspend fun verify(rawCookieValue: String?): WhoAmI?

    /** Cache-bypassing verify. Use on write paths under an advisory lock. */
    suspend fun verifyFresh(rawCookieValue: String?): WhoAmI?
}
```

No test for this — it's an interface; behavior is tested via adapters.

### Task 3: HttpCookieVerifier — cached `verify`

Port the game-api adapter behavior 1:1. Write tests first.

- [ ] **Step 1: Write failing tests**

`grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/auth/HttpCookieVerifierTest.kt` — port from `game/infrastructure/src/test/kotlin/com/bliss/game/infrastructure/auth/HttpCookieVerifierTest.kt`. Mirror these test cases verbatim, swapping the package and the `UserId`/`Pseudonym` types for `UUID`/`String`:
1. `null or blank cookie short-circuits without hitting the wire`
2. `200 response is parsed and cached`
3. `401 response is cached as anon (null) without re-hitting the wire`
4. `cache TTL expires and triggers a second HTTP call`
5. `5xx fails closed and is NOT cached so the next call retries`
6. **NEW:** `verifyFresh bypasses the cache even on a hot key` — call `verify` to populate cache, then call `verifyFresh` and assert two HTTP requests were made.
7. **NEW:** `verifyFresh returns null when identity-api returns 401`
8. **NEW:** `verifyFresh fails closed on 5xx without caching`

- [ ] **Step 2: Run, expect compile errors / failures**

Run: `./gradlew :grid:infrastructure:test --tests "HttpCookieVerifierTest"` → FAIL.

- [ ] **Step 3: Implement adapter**

`grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/auth/HttpCookieVerifier.kt` — port game-api's implementation; add `verifyFresh`:

```kotlin
override suspend fun verifyFresh(rawCookieValue: String?): WhoAmI? {
    val cookie = rawCookieValue?.takeIf { it.isNotBlank() } ?: return null
    val response = try {
        http.get("$identityApiBaseUrl/v1/auth/whoami") {
            header("Cookie", "__Secure-ws_session=$cookie")
        }
    } catch (cause: Throwable) {
        log.warn("identity-api whoami unreachable (verifyFresh); failing closed", cause); return null
    }
    return try {
        when (response.status) {
            HttpStatusCode.OK -> {
                val parsed = json.decodeFromString(WhoAmIResponse.serializer(), response.body<String>())
                val result = WhoAmI(UUID.fromString(parsed.userId), parsed.displayName)
                // Refresh the cache opportunistically — a fresh 200 supersedes any stale entry.
                cache[cookie] = Entry(result, now().plus(cacheTtl))
                result
            }
            HttpStatusCode.Unauthorized -> {
                // Invalidate any stale positive cache.
                cache[cookie] = Entry(null, now().plus(cacheTtl)); null
            }
            else -> { log.warn("identity-api whoami returned {} (verifyFresh); failing closed", response.status.value); null }
        }
    } catch (cause: Throwable) {
        log.warn("identity-api whoami response unparseable (verifyFresh); failing closed", cause); null
    }
}
```

- [ ] **Step 4: Run, expect green**

Run: `./gradlew :grid:infrastructure:test --tests "HttpCookieVerifierTest"` → PASS (all 8 tests).

### Task 4: DI wiring

- [ ] **Step 1: Add binding in Module.kt**

`grid/api/src/main/kotlin/com/bliss/grid/api/Module.kt`: construct `HttpCookieVerifier` from the existing HttpClient + a new `IDENTITY_API_BASE_URL` env var (default `http://identity-api:8080` in k8s, `http://localhost:8081` locally). Expose as a module-scoped val; no route uses it yet.

```kotlin
val cookieVerifier: CookieVerifier = HttpCookieVerifier(
    http = httpClient,
    identityApiBaseUrl = environment.config.propertyOrNull("identity.apiBaseUrl")?.getString()
        ?: System.getenv("IDENTITY_API_BASE_URL")
        ?: "http://identity-api:8080",
)
```

Mirror how game-api's `Module.kt` does it.

- [ ] **Step 2: Compile + test the module**

Run: `./gradlew :grid:api:test` → PASS (existing tests; no new route consumes `cookieVerifier` yet).

### Task 5: Konsist arch test guard

- [ ] **Step 1: Verify existing Konsist suite is green**

Run: `./gradlew :grid:application:test :grid:infrastructure:test :grid:api:test`. Konsist tests live under each module; ensure none flag `CookieVerifier` for being in the wrong layer. If a new Konsist rule for "infrastructure must not be referenced from application" trips, fix the wiring (the port is in application, adapter in infrastructure — should be clean).

### Task 6: Commit + open PR

- [ ] **Step 1: Stage and commit**

```bash
git add grid/application grid/infrastructure grid/api/src/main/kotlin/com/bliss/grid/api/Module.kt
git commit -m "$(cat <<'EOF'
feat(grid-api): CookieVerifier port + HttpCookieVerifier adapter

Phase 6b.0 — bring grid-api the same cookie-verify contract as game-api.
Adds verifyFresh() for cache-bypassing checks required by under-lock
re-verify on write paths. No route consumes it yet.
EOF
)"
```

- [ ] **Step 2: Push + open PR**

```bash
git push -u origin feat/grid-cookie-verifier
gh pr create --title "feat(grid-api): CookieVerifier port + HttpCookieVerifier adapter (Phase 6b.0)" --body "<spec link + description per template>"
```

---

## Sub-PR 6b.1 — Hint history per user_id + race-free write (combined)

**Branch:** `feat/grid-hint-history-user-id` off `main` (after 6b.0 merges).

**Goal:** OpenAPI bump (drop `X-Session-Id` on hints POST, add `__Secure-ws_session` cookie auth + 401 response, add required `hintsRemaining` to `Puzzle`) + Kotlin implementation (Flyway V6 cutover, repository signature change to `userId`, race-free write coordinator, route cookie-verify with under-lock fresh re-verify, read-path embeds `hintsRemaining`) + minimal frontend caller migration to keep the build green. See the "Why 6b.1 bundles schema + impl + caller migration (un-split)" rationale at the top of this plan.

**Files:**
- Create: `grid/api/src/main/resources/db/migration/V6__puzzle_hint_usage_user_id.sql`
- Modify: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/HintUsageRepository.kt`
- Modify: `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresHintUsageRepository.kt`
- Modify: `grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/persistence/PostgresHintUsageRepositoryTest.kt`
- Modify: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/RevealCellHintUseCase.kt`
- Modify: `grid/api/src/main/kotlin/com/bliss/grid/api/routes/PuzzleRoute.kt`
- Modify: `grid/api/src/main/kotlin/com/bliss/grid/api/dto/PuzzleResponse.kt`
- Modify: `grid/api/src/main/kotlin/com/bliss/grid/api/mapper/GridToPuzzleMapper.kt`
- Modify: `grid/api/src/test/kotlin/com/bliss/grid/api/routes/HintsRouteTest.kt`

### Task 1: Flyway migration

- [ ] **Step 1: Write the migration**

`grid/api/src/main/resources/db/migration/V6__puzzle_hint_usage_user_id.sql`:
```sql
-- Hard cutover: no production rows to preserve.
TRUNCATE TABLE puzzle_hint_usage;

ALTER TABLE puzzle_hint_usage DROP CONSTRAINT puzzle_hint_usage_pkey;
ALTER TABLE puzzle_hint_usage DROP COLUMN session_id;
ALTER TABLE puzzle_hint_usage ADD COLUMN user_id UUID NOT NULL;
ALTER TABLE puzzle_hint_usage ADD PRIMARY KEY (puzzle_id, user_id);
CREATE INDEX puzzle_hint_usage_user_id_idx ON puzzle_hint_usage (user_id);
```

- [ ] **Step 2: Verify migration applies cleanly in testcontainers**

Run any existing repository test that boots the Flyway schema: `./gradlew :grid:infrastructure:test --tests "PostgresHintUsageRepositoryTest"` — expect failure (the test still references `sessionId`). The point is to confirm Flyway accepts V6 before signature changes.

### Task 2: Repository signature change

- [ ] **Step 1: Update the port**

`grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/HintUsageRepository.kt`:
```kotlin
package com.bliss.grid.application.puzzle

import java.util.UUID

interface HintUsageRepository {
    /** Atomic spend. Returns the new hints_used, or null when the cap is already reached. */
    fun trySpend(puzzleId: UUID, userId: UUID, hintsAllowed: Int): Int?

    /** Returns hints_used for (puzzleId, userId), or 0 if no row. Used by the read path to embed hintsRemaining. */
    fun usedFor(puzzleId: UUID, userId: UUID): Int

    /** RGPD Art. 17. Removes every hint row tied to [userId]. Returns rows deleted. Idempotent. */
    fun deleteByUser(userId: UUID): Int
}
```

- [ ] **Step 2: Update tests first (TDD)**

`grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/persistence/PostgresHintUsageRepositoryTest.kt` — rewrite each test to use `userId: UUID` instead of `sessionId`. Add a new test:

```kotlin
@Test
fun `trySpend concurrent with deleteByUser leaves no surviving row`() {
    // Spawns two threads: one calls trySpend in a loop, the other deletes.
    // Acquires pg_advisory_xact_lock around each operation to mirror prod behavior.
    // Asserts: after both threads settle, count of rows for that user_id is 0
    // (delete wins) or 1 (delete ran before any spend); never produces stale orphans.
}
```

Run: `./gradlew :grid:infrastructure:test --tests "PostgresHintUsageRepositoryTest"` → FAIL (compile error: signature changed).

- [ ] **Step 3: Update the adapter**

`grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresHintUsageRepository.kt`: rename parameter and column; add `usedFor` and `deleteByUser`. The `trySpend` SQL is unchanged except for the column name swap. NOTE: `trySpend` does NOT acquire the advisory lock itself — the lock is acquired by the route handler one level up, around the transaction. (`deleteByUser`, implemented in 6b.2, does acquire the lock internally — that is safe because the route handler never calls `deleteByUser`.) Document this in the KDoc.

```kotlin
// SQL templates
private const val SPEND_SQL = """
    INSERT INTO puzzle_hint_usage (puzzle_id, user_id, hints_used)
    SELECT ?, ?, 1 WHERE ? > 0
    ON CONFLICT (puzzle_id, user_id) DO UPDATE
        SET hints_used = puzzle_hint_usage.hints_used + 1,
            updated_at = now()
        WHERE puzzle_hint_usage.hints_used < ?
    RETURNING hints_used
"""

private const val USED_FOR_SQL =
    "SELECT hints_used FROM puzzle_hint_usage WHERE puzzle_id = ? AND user_id = ?"

private const val DELETE_BY_USER_SQL =
    "DELETE FROM puzzle_hint_usage WHERE user_id = ?"
```

- [ ] **Step 4: Run, expect green**

Run: `./gradlew :grid:infrastructure:test --tests "PostgresHintUsageRepositoryTest"` → PASS.

### Task 3: Use case signature

- [ ] **Step 1: Update RevealCellHintUseCase**

Change the use case signature from `execute(puzzleId, sessionId, row, column)` to `execute(puzzleId, userId, row, column)`. Rename internal variables.

`grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/RevealCellHintUseCase.kt`: parameter rename `sessionId: UUID` → `userId: UUID`; passes through to `repository.trySpend(puzzleId, userId, hintsAllowed)`.

- [ ] **Step 2: Run application tests**

Run: `./gradlew :grid:application:test` → PASS (after fixing any test compile errors from the rename).

### Task 4: Route — cookie-verify, lock, fresh re-verify, write

This is the load-bearing change. Read §4.2 and §5 of the spec carefully.

- [ ] **Step 1: Write failing route test**

`grid/api/src/test/kotlin/com/bliss/grid/api/routes/HintsRouteTest.kt`:
- Delete the `X-Session-Id` tests (no longer applicable).
- Add tests that inject a fake `CookieVerifier` into the test module:
  - 401 when cookie missing.
  - 401 when `verify` returns null (no cookie).
  - 401 when `verifyFresh` returns null even though `verify` cached a positive (simulates session revoked between read and write — the under-lock fresh check catches it).
  - 200 happy path returns `hintsRemaining` decremented.
  - 429 budget exhausted.

Run: `./gradlew :grid:api:test --tests "HintsRouteTest"` → FAIL.

- [ ] **Step 2: Update PuzzleRoute.kt — `POST /v1/puzzles/{puzzleId}/hints`**

Replace the X-Session-Id parsing block with a cookie read + verify, then wrap the use case call in a transaction with the advisory lock and fresh re-verify:

```kotlin
post("/v1/puzzles/{puzzleId}/hints") {
    val rawId = call.parameters["puzzleId"].orEmpty()
    val puzzleId = parseUuid(rawId) ?: run {
        call.respondProblem(HttpStatusCode.BadRequest, "Identifiant de grille invalide", INVALID_PUZZLE_ID_TYPE, "Le paramètre puzzleId doit être un UUID, reçu : '$rawId'.")
        return@post
    }

    val rawCookie = call.request.cookies["__Secure-ws_session"]
    val cached = cookieVerifier.verify(rawCookie) ?: run {
        call.respondProblem(HttpStatusCode.Unauthorized, "Authentification requise", AUTH_REQUIRED_TYPE, "Cette action nécessite une session valide.")
        return@post
    }

    val body = try {
        call.receive<RevealCellHintRequest>()
    } catch (e: SerializationException) {
        call.respondProblem(HttpStatusCode.BadRequest, "Corps de requête invalide", INVALID_REQUEST_BODY_TYPE, e.message ?: "request body could not be deserialized")
        return@post
    }

    // Coordinator owns the connection, advisory lock, and commit — route stays free of JDBC types.
    val outcome = hintWriteCoordinator.withUserLock(cached.userId) {
        cookieVerifier.verifyFresh(rawCookie)?.takeIf { it.userId == cached.userId }
            ?.let { fresh -> revealCellHint.execute(puzzleId, fresh.userId, body.row, body.column) }
            ?: RevealCellHintOutcome.SessionRevoked
    }

    when (outcome) {
        is RevealCellHintOutcome.Granted -> call.respond(RevealCellHintResult(outcome.row, outcome.column, outcome.letter.toString(), outcome.hintsRemaining))
        is RevealCellHintOutcome.PuzzleNotFound -> call.respondProblem(HttpStatusCode.NotFound, "Grille introuvable", PUZZLE_NOT_FOUND_TYPE, "Aucune grille pour l'identifiant '$puzzleId'.")
        is RevealCellHintOutcome.BudgetExhausted -> call.respondProblem(HttpStatusCode.TooManyRequests, "Quota d'indices atteint", BUDGET_EXHAUSTED_TYPE, "Vous avez utilisé tous vos indices pour cette grille.")
        is RevealCellHintOutcome.SessionRevoked -> call.respondProblem(HttpStatusCode.Unauthorized, "Session expirée", AUTH_REQUIRED_TYPE, "Votre session a été invalidée.")
    }
}
```

Add the new outcome variant in the use case's sealed class: `data object SessionRevoked : RevealCellHintOutcome()`. Add `AUTH_REQUIRED_TYPE = "https://bliss.example/errors/auth-required"` per ADR-0003 §5 — every existing problem type in `PuzzleRoute.kt` uses the `https://bliss.example/errors/<slug>` pattern.

The route + use case + repository need to share one transaction so the advisory lock, the fresh re-verify, and the INSERT all see the same locked state (`pg_advisory_xact_lock` releases on commit). Implementer's choice on layering — two reasonable options:

1. **Connection-threaded:** route opens connection, repository methods accept `Connection` as first arg, use case forwards it.
2. **Closure coordinator:** introduce `HintWriteCoordinator.withUserLock(userId, verifyFn): Outcome` in application/infrastructure that opens the connection, takes the lock, runs the verify closure, calls `trySpend`, commits. Route hands it `verifyFn = { cookieVerifier.verifyFresh(rawCookie)?.userId == cached.userId }`.

Prefer option 2 — keeps the use case free of JDBC types. Repository keeps its existing `trySpend` signature; the coordinator passes its own `Connection` to a new `trySpend(conn, ...)` overload.

- [ ] **Step 3: Run, expect green**

Run: `./gradlew :grid:api:test --tests "HintsRouteTest"` → PASS.

### Task 5: Read path — embed hintsRemaining

- [ ] **Step 1: Write failing test**

In `HintsRouteTest` or a new `PuzzleRouteTest`: assert that `GET /v1/puzzles/{id}` with a valid cookie returns `hintsRemaining = hintsAllowed - usedFor(...)`, and without a cookie returns `hintsRemaining = hintsAllowed`.

- [ ] **Step 2: Update PuzzleResponse DTO**

`grid/api/src/main/kotlin/com/bliss/grid/api/dto/PuzzleResponse.kt`: add `val hintsRemaining: Int` field (always populated — anon = hintsAllowed).

- [ ] **Step 3: Update GET handler**

In `PuzzleRoute.kt` GET handler: after fetching the puzzle, read the cookie, call `cookieVerifier.verify(rawCookie)`; if non-null, compute `hintsRemaining = stored.hintsAllowed - repository.usedFor(puzzleId, who.userId)`; else `hintsRemaining = stored.hintsAllowed`. Pass to the mapper.

- [ ] **Step 4: Update mapper**

`GridToPuzzleMapper.toApi(...)`: add `hintsRemaining: Int` parameter; pass through.

- [ ] **Step 5: Run, expect green**

Run: `./gradlew :grid:api:test` → PASS.

### Task 6: Commit + open PR

- [ ] **Step 1: Stage and commit**

```bash
git add grid/ docs/
git commit -m "$(cat <<'EOF'
feat(grid-api): hint history per user_id with race-free write path

Phase 6b.1-impl — re-key puzzle_hint_usage from session_id to user_id (hard
cutover, pre-alpha). Hint POST is now authed-only; the write path
acquires pg_advisory_xact_lock(user:$user_id) and re-verifies the
cookie freshly under the lock (cannot trust the 30 s LRU cache for
mutations). Read path embeds hintsRemaining in PuzzleResponse.
EOF
)"
```

- [ ] **Step 2: Push + open PR**

```bash
git push -u origin feat/grid-hint-history-user-id
gh pr create --title "feat(grid-api): hint history per user_id + race-free write (Phase 6b.1-impl)" --body "..."
```

---

## Sub-PR 6b.2 — NATS user.deleted consumer

**Branch:** `feat/grid-user-deleted-consumer` off `main` (after 6b.1-impl merges).

**Goal:** Grid-infrastructure subscribes to `wordsparrow.user.deleted` JetStream subject, durable consumer `grid-user-deleted`, deletes hint rows for the user, acks. Acquires the same advisory lock so it serializes against in-flight writes.

**Files:**
- Create: `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/events/NatsConnectionFactory.kt` (port from game-api)
- Create: `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/events/UserEventSubscribers.kt`
- Create: `grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/events/UserEventSubscribersIT.kt`
- Modify: `grid/api/src/main/kotlin/com/bliss/grid/api/Module.kt` (wire JetStream subscription on boot, close on shutdown)
- Modify: `grid/infrastructure/build.gradle.kts` (add `io.nats:jnats` dep if not already present)
- Modify: `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresHintUsageRepository.kt` — `deleteByUser(userId)` (the port method, defined in 6b.1) acquires the advisory lock then deletes in a single transaction. The lock is an adapter implementation detail; the port surface stays clean.

### Task 1: NatsConnectionFactory + UserEventSubscribers

- [ ] **Step 1: Port NatsConnectionFactory from game-api 1:1**

Copy `game/infrastructure/src/main/kotlin/com/bliss/game/infrastructure/events/NatsConnectionFactory.kt` to grid-infrastructure, change the package.

- [ ] **Step 2: Write the integration test first**

`grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/events/UserEventSubscribersIT.kt`:

Use testcontainers `NatsContainer` (port from game-api's test util) + `PostgreSQLContainer`. Boot a JetStream stream with subject `wordsparrow.user.deleted`. Pre-populate `puzzle_hint_usage` with rows for two distinct user_ids. Publish a `user.deleted` message for one of the user_ids. Subscribe with the subject-under-test. Assert (with polling, ≤2 s):
- Rows for the deleted user_id are gone.
- Rows for the other user_id remain.
- The message is acked (no redelivery within the test window).

Also: test the lock contention. Spawn a writer thread that holds the advisory lock with a 500 ms sleep while a delete event is published; assert the delete waits and runs after the writer commits, and the writer's row gets deleted.

- [ ] **Step 3: Run, expect compile failures**

Run: `./gradlew :grid:infrastructure:test --tests "UserEventSubscribersIT"` → FAIL.

- [ ] **Step 4: Implement the subscriber**

`grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/events/UserEventSubscribers.kt`: mirror game-api's structure. `suspend (Message) -> Unit` handler type — never `runBlocking`. Handler:

```kotlin
class GridUserDeletedHandler(
    private val repository: HintUsageRepository,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend operator fun invoke(msg: Message) {
        val payload = json.decodeFromString(UserDeletedEvent.serializer(), msg.data.decodeToString())
        val userId = UUID.fromString(payload.userId)
        repository.deleteByUser(userId)   // port method; adapter takes the advisory lock internally
        msg.ack()
    }
}

@Serializable
private data class UserDeletedEvent(val userId: String, val occurredAt: String)
```

In 6b.1 the adapter's `deleteByUser` was a single-statement DELETE. In 6b.2 we strengthen it to acquire the advisory lock first, in the same transaction, so it serializes against in-flight `trySpend` calls. The port signature does not change — the lock is purely an adapter implementation detail.

`PostgresHintUsageRepository.deleteByUser(userId)` implementation:

```kotlin
override fun deleteByUser(userId: UUID): Int =
    dataSource.connection.use { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('user:' || ?::text))").use {
                it.setString(1, userId.toString()); it.execute()
            }
            val rows = conn.prepareStatement("DELETE FROM puzzle_hint_usage WHERE user_id = ?").use {
                it.setObject(1, userId); it.executeUpdate()
            }
            conn.commit(); rows
        } catch (t: Throwable) { conn.rollback(); throw t }
    }
```

- [ ] **Step 5: Run, expect green**

Run: `./gradlew :grid:infrastructure:test --tests "UserEventSubscribersIT"` → PASS.

### Task 2: Module wiring

- [ ] **Step 1: Subscribe on boot, close on shutdown**

`grid/api/src/main/kotlin/com/bliss/grid/api/Module.kt`: after constructing the repository + handler, open a JetStream subscription with durable name `grid-user-deleted` on subject `wordsparrow.user.deleted`, `AckExplicit`. Register a shutdown hook to close the subscription cleanly.

Mirror game-api's wiring in `Module.kt` precisely; the only delta is the durable consumer name.

- [ ] **Step 2: Boot smoke test**

Run: `./gradlew :grid:api:test` (existing smoke tests) → PASS. If a smoke test boots the module without NATS available, the subscriber must degrade gracefully (log a warning, skip subscription) — mirror game-api's behavior.

### Task 3: ADR-0049 update

- [ ] **Step 1: Add consumer to the table**

`docs/adr/0049-nats-jetstream-cross-context-events.md`: add a row to the consumer table — durable `grid-user-deleted`, subject `wordsparrow.user.deleted`, owner grid.

### Task 4: Commit + open PR

- [ ] **Step 1: Stage and commit**

```bash
git add grid/ docs/adr/0049-nats-jetstream-cross-context-events.md
git commit -m "feat(grid-events): NATS user.deleted consumer cascade-deletes hint rows (Phase 6b.2)"
```

- [ ] **Step 2: Push + open PR**

```bash
git push -u origin feat/grid-user-deleted-consumer
gh pr create --title "feat(grid-events): NATS user.deleted consumer cascade-deletes hint rows (Phase 6b.2)" --body "..."
```

---

## Sub-PR 6b.3 — Frontend reads `hintsRemaining` from puzzle GET

**Branch:** `feat/frontend-hint-history-server` off `main` (after 6b.1-schema merges; OpenAPI types must be regenerated).

**Goal:** The hint button now shows the canonical remaining count from the server on first load instead of computing locally from `hintsAllowed`.

**Files:**
- Modify: `frontend/openapi/` (regenerate types)
- Modify: `frontend/src/ui/components/grid/HintButton.tsx` (or wherever the button lives)
- Modify: `frontend/src/ui/hooks/usePuzzle.ts` (or equivalent — extract `hintsRemaining` from the GET response)
- Modify: `frontend/e2e/hint-flow.spec.ts` (touch-up — assert label uses `hintsRemaining`)

### Task 1: Regenerate OpenAPI types

- [ ] **Step 1: Pull latest spec + regenerate**

```bash
pnpm --filter frontend run openapi:generate
```

Verify `PuzzleResponse` type now includes `hintsRemaining: number`.

### Task 2: Update the hook

- [ ] **Step 1: Add hintsRemaining to the hook return**

Wherever the hook surfaces puzzle data, expose `hintsRemaining` to the component tree.

### Task 3: Update HintButton

- [ ] **Step 1: Write a failing component test**

`frontend/src/ui/components/grid/__tests__/HintButton.test.tsx`: render with `hintsRemaining={0}` → assert button is disabled and tooltip text matches "vous avez utilisé tous vos indices pour cette grille". Render with `hintsRemaining={2}, hintsAllowed={3}` → assert label is `Indice (2 / 3)`.

- [ ] **Step 2: Update the component**

Switch from local-computed remaining (`hintsAllowed - localCount`) to the `hintsRemaining` prop sourced from the puzzle response. On a successful hint POST, override with the server's authoritative `hintsRemaining` from the response.

- [ ] **Step 3: Run unit tests**

Run: `pnpm --filter frontend test HintButton` → PASS.

### Task 4: E2E touch-up

- [ ] **Step 1: Run existing hint-flow spec**

```bash
pnpm --filter frontend exec playwright test hint-flow
```

Expect PASS (the spec was written against the existing POST response shape, which still returns `hintsRemaining`). Update only if a label assertion changes.

### Task 5: Commit + open PR

- [ ] **Step 1: Stage and commit**

```bash
git add frontend/
git commit -m "feat(frontend-grid): read hintsRemaining from puzzle GET (Phase 6b.3)"
```

- [ ] **Step 2: Push + open PR**

```bash
git push -u origin feat/frontend-hint-history-server
gh pr create --title "feat(frontend-grid): read hintsRemaining from puzzle GET (Phase 6b.3)" --body "..."
```

---

## Cross-cutting verification (after all four PRs merge)

- [ ] Manually flow: sign in → reveal a hint → sign out → sign in on second device → reveal a hint → confirm same `hintsRemaining` count.
- [ ] Manually flow: sign in → spend hints → delete account from `/compte` → re-sign-up → confirm hint budget reset (DB rows cascade-deleted via NATS consumer).
- [ ] Check the Grafana dashboard for the new `grid-user-deleted` JetStream consumer — lag should sit at 0; redeliveries should be rare.

## Out of scope

See spec §9. Notably: no per-user lifetime quota, no `/compte` hint history surface, no cookie-verify pattern in other grid endpoints.
