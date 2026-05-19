# Phase 6b — Hint history per user_id (grid-api)

**Date:** 2026-05-19
**Status:** Approved (brainstorming pass complete)
**Depends on:** Phase 6c (PRs #523–#531) — cookie-verify pattern, `__Secure-ws_session`, NATS JetStream, `wordsparrow.user.deleted` subject.

## 1. Motivation

Today, `puzzle_hint_usage` keys hint spends on `(puzzle_id, session_id)` — a UUID v7 the frontend stores in `localStorage`. When a player signs in across two devices, two sign-outs, or one cache clear, their hint budget resets. The session id is also unrelated to identity-api's account model, so account deletion (RGPD Art. 17) cannot reliably wipe a user's hint history.

Phase 6c established the cookie-verify adapter pattern in game-api and the NATS `wordsparrow.user.deleted` event bus. Phase 6b extends the same shape to grid-api so that:

1. An authenticated player's hint spends accumulate against their `user_id`, surviving session id rotation.
2. Account deletion in identity-api propagates to grid-api and removes the player's hint history.
3. The hint endpoint is authenticated-only (the UI gate from Phase 5 already hides the button from anon users; the server now enforces it).

## 2. Pre-set decisions (carried from brainstorm)

| Decision | Value | Rationale |
| --- | --- | --- |
| Identity key | `user_id` (UUID) only — `session_id` column dropped | Sessions rotate; user identity is the durable bucket. |
| PK | `(puzzle_id, user_id)` | One row per (puzzle, player). |
| Anon access | Server returns 401 on hint POST without valid cookie | Phase 5 already gates the button UI-side; server enforces. |
| Migration policy | Hard cutover — `TRUNCATE puzzle_hint_usage` | Pre-alpha; no users to preserve; avoids dual-key transitional rows. |
| Hint budget scope | Per puzzle (mirrors current schema) | No new mechanic — only the key changes. |
| HTTP endpoint shape | Reuse `POST /v1/puzzles/{puzzleId}/hints` | No new route; behavior change is server-side keying. |
| Read-path exposure | Embed `hintsRemaining` in `GET /v1/puzzles/{puzzleId}` when authed | Frontend renders the hint button state on first load instead of probing. |
| Race-free write | Advisory lock on `user:$user_id` + fresh cookie-verify under lock (no tombstone) | See §5. |

## 3. Architecture

Bounded context: `grid/`. Layers:

```
grid/api/             ← PuzzleRoute reads cookie, calls verifier, threads userId
grid/application/     ← CookieVerifier port + RevealCellHintUseCase (now user-keyed)
grid/infrastructure/  ← HttpCookieVerifier (mirrors game-api), NatsUserDeletedSubscriber
grid/api/db/migration/V6__puzzle_hint_usage_user_id.sql
```

### 3.1 Ports

```kotlin
// grid/application/.../auth/CookieVerifier.kt
interface CookieVerifier {
    /** Cached verify (≤30 s TTL). Use on read paths. Returns null on missing/invalid cookie or upstream 5xx. */
    suspend fun verify(rawCookieValue: String?): WhoAmI?

    /** Cache-bypassing verify. Use on write paths under an advisory lock; never trust the cache when mutating user-keyed rows. */
    suspend fun verifyFresh(rawCookieValue: String?): WhoAmI?
}
data class WhoAmI(val userId: UUID, val displayName: String)
```

Identical shape to game-api's `CookieVerifier`, plus the `verifyFresh` sibling required by the under-lock re-verify pattern (§5). Each context owns its own copy of the port — no cross-context import (CLAUDE.md "Architecture": never import from another bounded context's domain or application). The game-api port should grow the same `verifyFresh` method as the Phase 6c.1 follow-up — same race, same fix.

### 3.2 Adapters

- `HttpCookieVerifier` — mirror of `game/infrastructure/.../auth/HttpCookieVerifier.kt`: 30 s LRU cache for the **read path**, immediate fail-closed on 5xx (no retry).
- `PostgresHintUsageRepository` — new signature `trySpend(puzzleId, userId, hintsAllowed)` and `deleteByUser(userId)`. The single-statement upsert keeps its atomicity guarantee.
- `NatsUserDeletedSubscriber` — JetStream consumer on `wordsparrow.user.deleted` with `AckExplicit`. On each message: acquires advisory lock for `user:$userId`, runs `deleteByUser`, acks. Mirrors `game/infrastructure/.../events/UserEventSubscribers.kt`.

### 3.3 Schema

Flyway `V6__puzzle_hint_usage_user_id.sql`:

```sql
-- Hard cutover: pre-alpha, no users to preserve.
TRUNCATE TABLE puzzle_hint_usage;

ALTER TABLE puzzle_hint_usage DROP CONSTRAINT puzzle_hint_usage_pkey;
ALTER TABLE puzzle_hint_usage DROP COLUMN session_id;
ALTER TABLE puzzle_hint_usage ADD COLUMN user_id UUID NOT NULL;
ALTER TABLE puzzle_hint_usage ADD PRIMARY KEY (puzzle_id, user_id);
CREATE INDEX puzzle_hint_usage_user_id_idx ON puzzle_hint_usage (user_id);
```

The `user_id` index supports the consumer's `DELETE WHERE user_id = $1` and a future "my hint history" read endpoint.

The `ON DELETE CASCADE` from `puzzles(puzzle_id)` stays.

## 4. Data flow

### 4.1 Read path — `GET /v1/puzzles/{puzzleId}`

1. Route reads `__Secure-ws_session` cookie.
2. `cookieVerifier.verify(rawCookie)` — cache OK on this path.
3. If `WhoAmI` is non-null: `hintsRemaining = hintsAllowed − repository.usedFor(puzzleId, userId)` (cheap SELECT).
4. If null (anon): `hintsRemaining = hintsAllowed` (the UI never enables the button, so this is informational only).
5. Response embeds `hintsRemaining` alongside the existing puzzle fields.

OpenAPI: add `hintsRemaining: integer`, always required, to the `PuzzleResponse` schema (anon callers receive `hintsAllowed` as the value).

### 4.2 Write path — `POST /v1/puzzles/{puzzleId}/hints`

```kotlin
// inside the route handler
val cached = cookieVerifier.verify(rawCookie) ?: return@post call.respond(401)

// Coordinator owns the connection, advisory lock, and commit — route stays free of JDBC types.
val outcome = hintWriteCoordinator.withUserLock(cached.userId) {
    cookieVerifier.verifyFresh(rawCookie)?.takeIf { it.userId == cached.userId }
        ?.let { fresh -> revealCellHint.execute(puzzleId, fresh.userId, body.row, body.column) }
        ?: RevealCellHintOutcome.SessionRevoked
}
```

`hintWriteCoordinator.withUserLock(userId, block)` (application/infrastructure adapter) opens a connection, acquires `pg_advisory_xact_lock('user:$userId')`, runs the closure, and commits — keeping JDBC types out of the application and use-case layers. `CookieVerifier.verifyFresh(rawCookieValue)` calls identity-api's `/v1/auth/whoami` directly, bypassing the adapter's 30 s LRU cache. The lock + fresh-verify pair is the load-bearing pattern from the feedback memory: **modifying queries must not trust the cookie-verify cache**.

### 4.3 GDPR delete — NATS consumer

JetStream subscription `wordsparrow.user.deleted`, durable consumer `grid-user-deleted`. On each message:

```kotlin
suspend fun handle(msg: Message) {
    val payload = json.decodeFromString<UserDeletedPayload>(msg.data.decodeToString())
    val userId = UUID.fromString(payload.userId)
    repository.deleteByUser(userId)  // port method; adapter acquires advisory lock internally
    msg.ack()  // explicit ack only on success — redelivery on crash is safe (idempotent DELETE).
}
```

The advisory lock is acquired inside `PostgresHintUsageRepository.deleteByUser` (the adapter); the subscriber only sees the port interface. The lock guarantees that any in-flight `trySpend` either (a) committed before this consumer started — its row is deleted here — or (b) executes after this consumer commits and its fresh re-verify returns 401 (session was cascade-revoked by identity-api).

## 5. Race-free write — proof sketch

Claim: no orphan hint row survives a `user.deleted` event.

The two transactions that contend on `user:$user_id`:

- **W (write):** lock → fresh-verify → INSERT → commit.
- **D (delete):** lock → DELETE → commit.

Postgres `pg_advisory_xact_lock` serializes the two. Three orderings:

1. **W commits first, then D runs.** D acquires the lock after W releases; D's `DELETE WHERE user_id = $userId` removes W's just-inserted row. Net: no surviving row. ✅
2. **D commits first, then W runs.** W acquires the lock after D releases. W's fresh re-verify hits identity-api, where the cascade has already revoked the session (identity-api deletes sessions with `ON DELETE CASCADE` on user delete). Whoami returns 401. W rolls back. Net: no surviving row. ✅
3. **W and D acquire the lock concurrently.** Impossible — `pg_advisory_xact_lock` is mutually exclusive within the Postgres instance.

The critical step is W's fresh re-verify **under the lock**. A cached verify before the lock can be stale by up to 30 s. The fresh call hits identity-api after D (if D ran first) has had its cascade committed, so the session is gone and the verify fails closed.

This pattern is now durable architectural guidance — see [feedback memory: "Modifying queries must not trust cookie cache"]. Same pattern applies retroactively to game-api's `anonymizeUserSeats` consumer (tracked as Phase 6c.1 follow-up in `FOLLOW_UP.md`).

## 6. Frontend

`PuzzleResponse` now always includes `hintsRemaining`; for anonymous callers it equals `hintsAllowed`. The hint button state machine — already gated on `isAuthed` in Phase 5 — switches its label from "Indice (n / hintsAllowed)" to "Indice (hintsRemaining / hintsAllowed)" and disables when `hintsRemaining === 0`:

```
disabled + tooltip "vous avez utilisé tous vos indices pour cette grille"
```

No new endpoints called from the frontend. The hint POST already returns `hintsRemaining` in its 200 body; the only frontend change is reading the initial value from the puzzle GET instead of assuming `hintsAllowed`.

## 7. Sub-PR phasing

All sub-PRs target the 400-line cap per CLAUDE.md "Code Quality / Small PRs". Conventional commits per branch type.

| # | Branch | Scope | Lines (est.) |
| - | --- | --- | --- |
| **6b.0** | `feat/grid-cookie-verifier` | Application port `CookieVerifier` + `HttpCookieVerifier` adapter + tests (mirror of game-api). Wires into DI. **No route changes yet.** | ~300 |
| **6b.1-schema** | `chore/grid-api-openapi-hint-history` | OpenAPI bump: add `hintsRemaining: integer` (required) to `PuzzleResponse`; remove `X-Session-Id` from hint POST security. Schema-only — no Kotlin changes. | ~10 |
| **6b.1-impl** | `feat/grid-hint-history-user-id` | Flyway V6 migration. `HintUsageRepository` signature change to `userId`. `RevealCellHintUseCase` accepts `userId`. Route uses cookie-verify with advisory-lock + fresh re-verify. Updates `HintsRouteTest` + `PostgresHintUsageRepositoryTest`. Embeds `hintsRemaining` in `GET /v1/puzzles/{id}`. Blocked on 6b.1-schema. | ~370 |
| **6b.2** | `feat/grid-user-deleted-consumer` | `NatsUserDeletedSubscriber` in grid-infrastructure. JetStream wiring in `Module.kt`. `deleteByUser` repository method with advisory lock. Integration test with testcontainers + embedded NATS. | ~350 |
| **6b.3** | `feat/frontend-hint-history-server` | Frontend reads `hintsRemaining` from puzzle GET response. Button label + disabled state. E2E touch-up. | ~150 |

Dependencies: 6b.0 → 6b.1-schema → (6b.1-impl and 6b.3 in parallel) → 6b.2.

## 8. Testing strategy

- **Domain:** N/A — no domain-layer changes. `HintUsageRepository` is an application port; `RevealCellHintUseCase` stays a thin orchestrator.
- **Application:** integration tests for `RevealCellHintUseCase` against `PostgresHintUsageRepository` + testcontainers Postgres. Two-transaction race test: simulate concurrent `trySpend` + `deleteByUser` using separate threads, assert no orphan row survives.
- **Infrastructure:**
  - `HttpCookieVerifierTest` — port from game-api unchanged (cache TTL, 5xx fail-closed, 401-cached-as-anon).
  - `PostgresHintUsageRepositoryTest` — covers `trySpend` cap, `deleteByUser` idempotency, advisory-lock serialization.
  - `NatsUserDeletedSubscriberTest` — testcontainers NATS + Postgres; publishes a `user.deleted` message and asserts hint rows are deleted, message is acked.
- **API:** `HintsRouteTest` extends to cover 401 on missing cookie, 401 on revoked session (mock `cookieVerifier.verifyFresh` returns null even though cache returned a WhoAmI).
- **E2E (frontend):** existing Playwright spec for the hint flow gets one new assertion — the `hintsRemaining` label updates after a hint spend.

## 9. Out of scope

- Per-user lifetime hint quota (today's cap is per-puzzle; no aggregate).
- Hint history surfaced in the user's account page (`/compte`). Tracked as Phase 7.
- Migrating anon `puzzle_hint_usage` rows — hard cutover, intentionally none.
- Reusing the cookie-verify pattern in grid-api's other endpoints (clue cooldown, etc.). Out of scope for Phase 6b; revisit if the read paths grow user-keyed behavior.
- Tombstone table for deleted users — explicitly rejected; the advisory-lock + fresh re-verify under lock pattern achieves race-free without it.

## 10. ADR touch-ups

- ADR-0049 (NATS JetStream cross-context events) — add `wordsparrow.user.deleted` consumer: `grid-user-deleted` to the consumer table. No new subject.
- No new ADR required. The race-free pattern is documented in the feedback memory and this spec; it's a reusable engineering pattern, not a cross-cutting architectural decision.

## 11. Risks

- **Identity-api whoami latency on the write path.** The fresh re-verify adds ~10–50 ms per hint POST (network + DB query in identity-api). Hint POSTs are low traffic and user-initiated; acceptable. Mitigation if measured latency spikes: switch the fresh check to a lighter `GET /v1/auth/session-exists?sessionId=…` endpoint that skips the user lookup.
- **NATS consumer redelivery.** `DELETE FROM puzzle_hint_usage WHERE user_id = ?` is idempotent; re-delivery is safe. The advisory lock prevents redelivery from racing with concurrent writes.
- **Frontend caching of `hintsRemaining`.** The puzzle GET response is cached for the page lifetime today (React Query default `staleTime`). If `hintsRemaining` changes server-side (someone signs in on another device), the local count stays stale until the page reloads. Acceptable — the next hint POST returns the canonical value and the UI corrects.
