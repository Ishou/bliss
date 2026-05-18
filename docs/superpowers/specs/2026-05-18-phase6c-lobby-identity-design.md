# Phase 6c — Game-api user-aware lobby identity

**Status:** Approved 2026-05-18.

**Context.** Phase 5 shipped the frontend sign-in surface. The identity-api is live; the frontend has an `AuthProvider`, an avatar menu, a `/compte` page, and a hint gate. The first user-visible gap exposed in production: when an authed user creates or joins a multiplayer lobby, the lobby still shows the localStorage-anon pseudonym (e.g. "Marmotte 900") instead of the user's chosen display name ("Isho"). Game-api has no notion of authenticated identity — `LobbyPlayer` records carry only `sessionId` + `pseudonym` from the request body.

This sub-phase ties lobby identity to the authenticated `userId` when present, fixes the live mismatch, and lays down the cross-context eventing infrastructure (NATS JetStream) needed for Phase 6 as a whole. It explicitly handles the GDPR case where a user deletes their account: every lobby trace of them must be anonymized synchronously.

**Related ADRs:** ADR-0044 (identity bounded context), ADR-0045 (data minimization — `openid` scope only), ADR-0009 (k3s deploy + cluster-internal services). A new ADR for the eventing pattern (NATS JetStream) ships with PR 6c.0.

Phase 4 spec referenced this work under "Out of scope (Phase 6) — anon ↔ authed state fusion".

## Scope

**In Phase 6c:**
- Cluster-internal NATS JetStream server (Helm chart, ADR, ops doc).
- Identity-api event publishers: `UserDeletedBroadcaster` (production NATS adapter replacing the in-memory stub) + new `UserRenamedBroadcaster` port and adapter.
- Game-api: `CookieVerifier` adapter (HTTP → identity-api `whoami` with 30s cache), `LobbyPlayer.userId` field, lobby endpoints learn the caller's user when authed, new internal endpoints `POST /v1/lobbies/players/rebind` + `/unbind`, NATS subscribers for `user.deleted` (anonymize) + `user.renamed` (refresh pseudonym).
- Frontend: `AuthProvider` triggers `rebind` on anon→authed transition; logout flow triggers `unbind` before clearing the cookie.

**Out of scope (Phase 6b, future):**
- Grid-api hint history per `userId`. Same cookie-verify pattern as game-api will reuse here, but the implementation lands in 6b.
- Grid-api consuming `user.deleted` / `user.renamed` events. The NATS infrastructure shipped in 6c.0 covers this when 6b builds on it.
- DLQ + monitoring for failed event deliveries. JetStream's age-based retention covers the alpha; a dead-letter pattern lands when production traffic warrants.
- Mid-game forced disconnect on session revocation. The open WebSocket survives a sign-out — acceptable for alpha, deferred.

## Decisions

| Decision | Choice |
|---|---|
| Phase 6 ordering | 6a skipped (FK cascade already handles session orphans; broadcaster contract already minimal). 6c first (most user-visible bug — Marmotte→Isho). 6b in parallel later. 6d folds into 6c since GDPR delete demands reliable delivery. |
| Cookie-verify pattern | HTTP call from game-api to identity-api `GET /v1/auth/whoami` with 30s LRU cache keyed on `sessionId`. Cache 401 results too. 5xx → fail closed (treat as anon). WebSocket connections verify at upgrade only. |
| Display name resolution in lobbies | Denormalize at join time + invalidate via `user.renamed` event. Lobby player records carry the snapshot at join (or rebind), and NATS-delivered rename events refresh them. |
| Anon → authed mid-game | Server rebinds the existing seat (set `userId`, refresh `pseudonym` from `whoami.displayName`). Same WebSocket connection, same lobby seat, new identity. Other clients see the rename on next state-update broadcast. |
| Authed → anon (logout) | Frontend calls `unbind` (cookie-authed) BEFORE `logout` clears the cookie. Game-api clears `userId` and resets `pseudonym` to the localStorage anon name supplied by the request body. Best-effort; if unbind fails, the next page refresh shows stale-named seats. |
| Account delete → lobby cleanup | NATS-delivered `user.deleted` event. Game-api consumer anonymizes (not deletes) `LobbyPlayer` rows: `userId = null`, `pseudonym = "Joueur supprimé"`. Anonymize over delete preserves lobby roster integrity (no silent disappearance mid-game) while removing identifiers (GDPR Art. 17). |
| Eventing transport | NATS JetStream (cluster-internal). Synchronous-ack on `user.deleted` (publish must succeed before `DeleteUserUseCase` commits — preserves the "delete failed, retry" UX from current behavior). Fire-and-forget with JetStream persistence on `user.renamed`. |
| `internal/` endpoint security | Cluster-internal NATS (no Ingress exposure); `/v1/lobbies/players/{rebind,unbind}` are public-routed but cookie-authed. No HMAC / shared-secret pattern needed — NATS handles producer/consumer authority; lobby endpoints rely on the cookie. |

## Architecture

### New eventing infrastructure

```
infra/nats/                                # New Helm chart
├── Chart.yaml
├── values.yaml
├── values-prod.yaml
└── templates/
    ├── _helpers.tpl
    ├── configmap.yaml                     # NATS server config (nats.conf)
    ├── statefulset.yaml                   # NATS server with JetStream + volumeClaimTemplates
    ├── service.yaml                       # ClusterIP only (no Ingress)
    ├── networkpolicy.yaml                 # Defense-in-depth ingress restriction
    └── stream-bootstrap-job.yaml          # post-install Job declaring WORDSPARROW_USER_EVENTS
```

- **NATS server:** official `nats:2.10` image, JetStream enabled, single replica (alpha; ADR notes the future upgrade path to 3 replicas). JetStream stream storage uses `volumeClaimTemplates` in the StatefulSet (per-replica PVC provisioning, no separate `pvc.yaml`).
- **Stream `WORDSPARROW_USER_EVENTS`:** subjects matching `wordsparrow.user.*`. Retention: `MaxAge=7d` (covers transient consumer downtime). Storage: `file` on the PVC. Replicas: 1. Discard policy: `old` (oldest message dropped when stream limit hit; not expected in alpha volume). Declared by a post-install Kubernetes `Job` (`stream-bootstrap-job.yaml`), not a ConfigMap.
- **Service:** `nats.wordsparrow:4222` (ClusterIP). No Ingress, no LoadBalancer. Cluster-internal only.
- **NetworkPolicy (optional, defense-in-depth):** restrict ingress to identity-api + game-api + grid-api service accounts only.

### Identity-api producer

New port (parallel to existing `UserDeletedBroadcaster`):

```kotlin
// identity/application/src/main/kotlin/com/bliss/identity/application/ports/UserRenamedBroadcaster.kt
fun interface UserRenamedBroadcaster {
    suspend fun broadcast(userId: UserId, newDisplayName: DisplayName, renamedAt: Instant)
}
```

Production adapters in `identity/infrastructure/events/`:

- `NatsUserDeletedBroadcaster` — publishes to `wordsparrow.user.deleted` with JSON payload `{ userId, deletedAt }`. JetStream **ack required** with 3s timeout. On ack timeout or NATS error: throws → `DeleteUserUseCase.BroadcastFailed` → user retries (existing UX).
- `NatsUserRenamedBroadcaster` — publishes to `wordsparrow.user.renamed` with `{ userId, newDisplayName, renamedAt }`. Fire-and-forget (NATS publish without waiting for ack). The publish API returns immediately; JetStream persists the message; consumer downtime is covered by the 7-day retention.

`UpdateMeUseCase` gains the `UserRenamedBroadcaster` dependency and calls it after `users.updateDisplayName` succeeds.

For tests: `InMemoryUserDeletedBroadcaster` (already exists, used by use-case tests) + new `InMemoryUserRenamedBroadcaster` with the same shape (captures events in a list).

### Game-api cookie-verifier

New port in `game/application/`:

```kotlin
interface CookieVerifier {
    /**
     * Resolves the session cookie to the authenticated user.
     * Returns null when no cookie / cookie invalid / identity-api returns 401.
     * Throws on network error after retries — caller decides fail-open / fail-closed.
     */
    suspend fun verify(rawCookieValue: String?): WhoAmI?
}

data class WhoAmI(val userId: UserId, val displayName: Pseudonym)
```

Production adapter `HttpCookieVerifier` in `game/infrastructure/`:
- Wraps a Ktor `HttpClient` configured for the in-cluster identity-api service DNS (`http://wordsparrow-identity-api.wordsparrow:8082/v1/auth/whoami`).
- 30s LRU cache (size: 10,000 entries) keyed on `sessionId`. Cache hits for both authed and 401 results. Cache miss → HTTP call.
- 5xx after one retry → returns null (fail closed, treats as anon). Logged at WARN.

Wiring: route handlers call `cookieVerifier.verify(sessionId)`. WebSocket upgrade handler calls it once on connection establish; the resulting `WhoAmI?` is stored on the connection state.

### Game-api `LobbyPlayer` model change

```kotlin
// game/domain/src/main/kotlin/com/bliss/game/domain/lobby/LobbyPlayer.kt
data class LobbyPlayer(
    val sessionId: SessionId,
    val pseudonym: Pseudonym,
    val userId: UserId? = null,   // NEW: null for anon, set when authed
    val joinedAt: Instant,
    // ... existing fields
)
```

In-memory only (ADR-0018 §3). No migration. The field defaults to null so existing serialization (REST responses, WebSocket frames) remain backward-compatible — clients that ignore unknown fields keep working.

### Game-api lobby endpoints

**Existing endpoints** (`POST /v1/lobbies`, `POST /v1/lobbies/by-code/{code}/join`, etc.):
- Each handler now extracts the cookie, calls `cookieVerifier.verify(sessionId)`.
- If verify returns a `WhoAmI`: `LobbyPlayer.userId = whoami.userId`, `pseudonym = whoami.displayName`. The `pseudonym` in the request body is **ignored when authed** (server is source of truth).
- If verify returns null: behavior unchanged from today.

**New `POST /v1/lobbies/players/rebind`** (cookie-authed):
- Body: `{ anonSessionId: string }`.
- Server calls `cookieVerifier.verify(cookie.sessionId)` → `WhoAmI`.
- Finds all `LobbyPlayer` rows where `sessionId == anonSessionId` AND `userId == null`. For each: set `userId = whoami.userId`, set `pseudonym = whoami.displayName`. Schedule WebSocket roster broadcasts for affected lobbies.
- Returns 204. Idempotent (re-running with rows already bound is a no-op).

**New `POST /v1/lobbies/players/unbind`** (cookie-authed — called BEFORE logout):
- Body: `{ anonPseudonym: string }`.
- Server calls `cookieVerifier.verify` → `WhoAmI`.
- Finds all `LobbyPlayer` rows where `userId == whoami.userId`. For each: set `userId = null`, set `pseudonym = anonPseudonym`. Schedule WebSocket broadcasts.
- Returns 204.

### Game-api NATS consumers

Wired in `Module.kt` at startup. Each consumer runs as a long-lived suspend job on the application's coroutine scope.

**`user.deleted` consumer:**
- Subscribe to `wordsparrow.user.deleted` with **explicit ack** (manual acknowledgement, JetStream redelivers on failure).
- On message: find all `LobbyPlayer` rows where `userId == X`. For each: set `userId = null`, set `pseudonym = "Joueur supprimé"`. Broadcast roster updates.
- ACK after the in-memory updates commit. On exception: NAK → JetStream redelivers (alpha: 5 retries, then dead-letter to log).

**`user.renamed` consumer:**
- Subscribe to `wordsparrow.user.renamed` with explicit ack.
- On message: find `LobbyPlayer` rows where `userId == X`. For each: set `pseudonym = newDisplayName`. Broadcast.
- ACK on success.

### Frontend wiring

- `AuthProvider` already observes the anon→authed transition (the first-sign-in carry-over check). Add a sibling effect: when transitioning to authed, call `gameClient.rebindLobbySessions(localStorageSessionId)`. Single call per transition (latched by `useRef`).
- The avatar menu's "Se déconnecter" handler: existing code calls `authClient.logout()` then `refresh()`. New shape: call `gameClient.unbindLobbySessions(localStoragePseudonym)` FIRST (while the cookie is still valid), then `authClient.logout()`, then `refresh()`, then `navigate({ to: '/' })`. If unbind fails (network, 5xx): swallow + log; the logout still proceeds.
- `AppRouterContext` already carries `gameClient`. Just expose two new methods on the `LobbyClient` port: `rebindLobbySessions` + `unbindLobbySessions`.

## Data flows

### Authed user creates a lobby
1. `POST /v1/lobbies` with `__Secure-ws_session` cookie.
2. `cookieVerifier.verify(rawCookieValue)` → cache miss → HTTP `GET /v1/auth/whoami` → cache write → returns `{ userId, displayName }`.
3. Owner record: `LobbyPlayer(sessionId, pseudonym = displayName, userId = userId, joinedAt = now)`.
4. Response unchanged from today.

### Anon user creates a lobby
1. Same `POST /v1/lobbies`. No cookie or cookie cleared.
2. `verify` → null. Owner record built with `userId = null`, `pseudonym` from request body (localStorage animal name).
3. No change from today.

### Anon user signs in mid-game
1. Browser returns from Google → `AuthProvider.whoami()` → 200 → state anon → authed.
2. `AuthProvider` effect: `gameClient.rebindLobbySessions(localStorageSessionId)`.
3. Game-api: verifies cookie → `whoami`. Finds `LobbyPlayer` rows where `sessionId == anonSessionId AND userId == null`. Updates each.
4. Next regular WebSocket state-update (roster) carries new pseudonyms; clients re-render.

### User renames display name on /compte
1. `PATCH /v1/users/me` with `{ displayName }`. `UpdateMeUseCase` updates `users.display_name`.
2. After success: `userRenamedBroadcaster.broadcast(userId, newName, now)` → publishes to NATS `wordsparrow.user.renamed`.
3. JetStream persists.
4. Game-api's subscriber receives (synchronously on subscribe-loop), updates `LobbyPlayer` rows where `userId == X`, ACKs.
5. WebSocket broadcasts to affected lobbies push new name.

### User logs out (with active lobby seat)
1. User clicks "Se déconnecter".
2. Frontend: `gameClient.unbindLobbySessions(localStoragePseudonym)`. Cookie still valid; game-api `verify` → `whoami`. Updates rows where `userId == X`: set `userId = null`, `pseudonym = anonPseudonym`. WebSocket roster updates dispatched.
3. Frontend: `authClient.logout()` → cookie cleared.
4. Frontend: `AuthProvider.refresh()` → whoami → 401 → state anon.
5. Frontend: navigate to `/`.

### User deletes their account
1. `/compte` danger zone → typed confirm → `authClient.deleteMe()`.
2. `DeleteUserUseCase`:
   - `users.findById(userId)` (existence check).
   - `userDeletedBroadcaster.broadcast(userId, now)` → NATS publish with ack.
   - If ack times out (3s): throw → `DeleteUserError.BroadcastFailed` → 503 to frontend → user retries.
   - If ack succeeds: `users.delete(userId)` → cascade removes sessions.
3. JetStream persists the event.
4. Game-api subscriber: anonymize all matching `LobbyPlayer` rows.
5. Frontend: cookie cleared on the response, navigate to `/`.
6. **GDPR property:** publish-ack-required means the event WILL be delivered to subscribers (the broadcaster does not return success without an ack from JetStream). A subsequent consumer outage cannot lose the event — JetStream retains it for 7 days and redelivers.

## Edge cases + risks

**WebSocket vs cookie lifetime.** Cookie verified once at upgrade. If the user signs out or deletes their account mid-game, the open WebSocket stays up with the user_id snapshot in memory. Acceptable for alpha — tighter "kick on revoke" requires session-revoked events from identity-api + per-connection registry on game-api. Deferred.

**Cookie-verify cache stale window.** 30s TTL. An authed user whose session is revoked could still appear authed in game-api for up to 30s. Same caveat — for alpha, identity-api itself rejects subsequent whoami calls; game-api sees stale `userId` only until cache expiry. Lobby state stays consistent.

**Rename event in transit during a join.** A user renames at T=0, immediately joins a lobby at T=1ms (before the event has been delivered to the consumer). The join's cookie-verify queries identity-api, gets the NEW name fresh from `users.display_name`. The subsequent rename event arrives, finds the row already named the new value — idempotent no-op. Safe.

**Two concurrent rebinds (two tabs).** Both tabs trigger rebind on the same anon→authed transition. Rebind handler is idempotent (set `userId = X`, refresh pseudonym). Last write wins on identical values. Safe.

**JetStream consumer lag visibility.** No DLQ + no monitoring in 6c.0. If game-api's subscriber dies mid-deploy, JetStream queues events for up to 7 days. On reconnect, consumer catches up. Manual observability via `nats stream report` for now. Real monitoring (Prometheus exporter, alerting on consumer lag) lands in a follow-up phase.

**NATS server outage.** If NATS is down:
- `user.deleted` publish fails ack → `DeleteUserUseCase` rolls back → user retries when NATS is back. GDPR-OK (the delete didn't happen, no inconsistency).
- `user.renamed` publish hangs/fails → fire-and-forget swallows the error → rename succeeds locally → lobby names stay stale → next rename or join refreshes them. Acceptable.

**`UserRenamedBroadcaster` fire-and-forget vs publish failure semantics.** JetStream publish-without-ack still queues locally in the NATS client; if the local client crashes before the queue flushes, the message is lost. The risk is bounded (rename frequency is low; alpha replicas are 1 so a crash is recoverable). If this becomes an issue, switch to ack-required + best-effort retry in a follow-up.

**Frontend race: rebind during initial page load.** AuthProvider's initial `whoami` call fires on mount. If it returns 200 (cached cookie still valid), the anon→authed transition is observed immediately on first render. The rebind effect fires. Correct, but the user may not even be in a lobby. The rebind endpoint handles empty-result cleanly (no rows match → 204).

**Logout race: unbind fires before logout completes.** If the user mashes the logout button twice, the second unbind call has a still-valid cookie (logout hasn't run yet). The handler finds the previously-updated (already-anonymized) rows, no-ops. Safe.

**`anonPseudonym` body field on unbind.** Frontend reads `localStorageSession.getPseudonym()` and sends it. If localStorage has been cleared (rare — e.g. user wiped browser data while signed in), the pseudonym is regenerated to a fresh animal name on read. Acceptable — they get a new anon identity for the lobby.

## Security model

- **NATS server:** ClusterIP only. No Ingress, no LoadBalancer. Reachable from any pod in the cluster network. NetworkPolicy (optional, ships in 6c.0) restricts ingress to the three service accounts (identity, game, grid).
- **Lobby `/v1/lobbies/players/{rebind,unbind}` endpoints:** public-routed via Ingress but cookie-authed. `cookieVerifier.verify` must return a valid `WhoAmI`; null result → 401. The same security perimeter as every other authed endpoint.
- **No HMAC / shared-secret between identity-api and game-api:** NATS handles the trust boundary. Authority to publish on `wordsparrow.user.*` is restricted by NATS auth (TLS + user accounts ship in 6c.0's ADR; for alpha v1, anonymous publish from any pod in the network is acceptable because no untrusted code runs in the cluster).
- **Cookie cross-subdomain — REQUIRES PREFIX CHANGE (decided).** Phase 4 shipped `__Host-ws_session`, host-locked to `auth.wordsparrow.io` per RFC 6265bis §4.1.3. That prefix FORBIDS the `Domain` attribute, so the browser will not send the cookie to `game.wordsparrow.io` (or `api.wordsparrow.io` for the future 6b grid integration). Game-api cannot cookie-verify what it cannot receive.

  **Decision:** rename the cookie to `__Secure-ws_session` with `Domain=wordsparrow.io; Secure; HttpOnly; SameSite=Lax; Path=/; Max-Age=604800`. The `__Secure-` prefix keeps the HTTPS-only requirement while permitting `Domain`. With `Domain=wordsparrow.io`, the cookie travels to every subdomain (`auth.`, `game.`, `api.`, plus the apex/`www.` frontend), so game-api receives it on cross-origin fetches with `credentials: 'include'` and forwards it as-is to identity-api on `verify` calls.

  **Trade-off:** lose the host-locking property — a sub-subdomain like `attacker.wordsparrow.io` could read the cookie if anything ever runs there. We control the entire `.wordsparrow.io` namespace (Cloudflare DNS via terraform), so the practical risk is the same as for `*.google.com` (which uses the same pattern). Documented in the new ADR.

  **Migration:** existing `__Host-ws_session` cookies in users' browsers are silently invalidated — the new server reads `__Secure-ws_session`, doesn't find it, treats the user as anon. They re-sign-in on next visit. Brief one-time disruption for users authed before the deploy.

  **Cross-cutting consequence:** this change lives in identity-api's `SessionCookies` (PR 491's helper). Lands as a small precursor PR to 6c.2, or folded into 6c.2 itself. **Captured in the implementation phasing below as a sub-step.**

## Testing strategy

**Unit tests:**
- `NatsUserDeletedBroadcaster` + `NatsUserRenamedBroadcaster`: integration tests against an embedded NATS server (via `nats-server` JVM test util or testcontainers).
- `HttpCookieVerifier`: MSW-equivalent (Ktor `mockEngine`) covering happy path, 401, cache hits/misses, 5xx fail-closed.
- Use-case tests for `UpdateMeUseCase` + `DeleteUserUseCase`: in-memory broadcasters capture events; assert payload contents.

**Integration tests:**
- Game-api full slice with embedded NATS: publish `user.deleted` → assert `LobbyPlayer` rows anonymized.
- Game-api lobby endpoints: cookie present → `LobbyPlayer.userId` set; absent → `userId = null`.
- Game-api rebind: anon seat → call rebind → row updated.

**End-to-end (Playwright):**
- Anon user creates lobby + signs in → header avatar reflects new name + lobby member list shows new name (after a beat for the WebSocket roster refresh).
- Authed user renames on `/compte` → lobby they're already in reflects the new name (after NATS delivery — Playwright `expect.toHaveText` with default timeout handles the eventual consistency).
- Authed user deletes account → lobby member list shows "Joueur supprimé" within seconds.

**No-test cases:**
- WebSocket forced disconnect on revoke — out of scope.
- DLQ + monitoring — out of scope.

## Implementation phasing

Four sub-PRs, decomposed at writing-plans time. Each independently shippable; 6c.0 must land first.

### 6c.0 — NATS infrastructure + ADR
- `infra/nats/` Helm chart (`StatefulSet` with `volumeClaimTemplates`, `Service`, stream-bootstrap `Job`, `NetworkPolicy`).
- `infra/nats/values.yaml` + `values-prod.yaml`.
- `infra/nats/README.md` covering install / stream report / dump+restore.
- ADR-00XX (next free number): "Lightweight eventing via NATS JetStream for cross-context user events".
- Wire chart into `helm-lint.yml` + `api-chart-lint.yml` (or skip if those workflows are scoped to api charts only).
- No application code. Deployable in isolation; verified via `nats stream info WORDSPARROW_USER_EVENTS`.

### 6c.1 — Identity-api producer + game-api log-only consumer
- New port `UserRenamedBroadcaster` in `identity/application/`.
- Production NATS adapters: `NatsUserDeletedBroadcaster` (replaces in-memory), `NatsUserRenamedBroadcaster`.
- `UpdateMeUseCase` calls the new broadcaster.
- Identity-api `Wiring.forProduction` wires the NATS connection.
- Game-api: NATS Kotlin client added; two log-only subscribers (consume + log + ack, no behavior change to lobby state). Proves end-to-end wiring.
- Tests: embedded NATS for adapter tests; in-memory broadcasters for use-case tests.

### 6c.2 — Cookie prefix change + cookie verifier + lobby endpoints + frontend rebind/unbind
- **Cookie rename**: identity-api's `SessionCookies` issues `__Secure-ws_session` with `Domain=wordsparrow.io` (replacing `__Host-ws_session`). Frontend reads the new name (no change needed — cookie is HttpOnly, never read in JS). Game-api + grid-api can now receive it cross-subdomain. Update `CorsTest`, integration tests, prod values where the cookie name is mentioned. Existing in-flight cookies invalidated on deploy (one-time re-sign-in for affected users). Documented in the new ADR.
- New port `CookieVerifier` in `game/application/`.
- `HttpCookieVerifier` adapter with 30s cache.
- `LobbyPlayer.userId` field added.
- Existing lobby create/join handlers learn the caller's user.
- New endpoints `POST /v1/lobbies/players/{rebind,unbind}`.
- Frontend: `AuthProvider` gains `getLocalSessionId` prop; triggers rebind on transition; logout flow calls unbind.
- E2E test stubs the OAuth round-trip via MSW (Phase 5 pattern).

### 6c.3 — Lobby consumers act on events
- Game-api `user.deleted` subscriber: anonymize rows.
- Game-api `user.renamed` subscriber: refresh pseudonym.
- Replace log-only subscribers from 6c.1 with handlers.
- WebSocket roster refresh on each event-driven update.
- Integration test: end-to-end NATS delivery → lobby state update.

## Open questions to resolve in writing-plans

1. **NATS auth model for v1:** the new ADR chooses between (a) anonymous publish/subscribe (alpha, simplest, relies on `NetworkPolicy` for the trust boundary); (b) NATS user accounts per service. Plan-time decision.
2. **ADR number assignment:** ship a new ADR for the NATS pattern AND likely amend the existing identity-cookie ADR (ADR-0045 or wherever the `__Host-` choice was originally documented) to record the `__Secure-` switch. Numbers picked at writing-plans time.
3. **6c.2 vs 6c.2a/6c.2b split:** the cookie prefix change is a small mechanical edit but it's a backward-incompat user-visible change (everyone re-signs-in once). May be cleaner as its own micro-PR ahead of the cookie-verifier work. Plan-time decision based on PR-cap math.
