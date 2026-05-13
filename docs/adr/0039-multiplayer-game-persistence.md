# ADR-0039: Multiplayer game persistence (Postgres adapter, GC matrix, RGPD erasure cascade)

## Status

Accepted — 2026-05-11

## Context

ADR-0018 introduced the `game` bounded context (lobbies, players,
WebSocket realtime, last-write-wins cell entries) and explicitly
deferred durable persistence to "Wave D+". That deferral has run its
course:

- The only `LobbyRepository` adapter today is `InMemoryLobbyRepository`.
  Every `game-api` restart wipes every lobby. The port already promises
  "a Postgres adapter when it lands" in its KDoc.
- Users want to fill one large grid across several sessions ("start
  tonight, come back tomorrow"). The current 30-minute WAITING TTL on
  `LobbyGarbageCollector` and the absence of any IN_PROGRESS or
  COMPLETED retention rule make multi-day play impossible even on a
  single, never-restarted process.
- The frontend already has a stable identifier for "the same browser":
  `bliss.session.id` in `localStorage` — a UUID v7 minted on first
  visit (ADR-0021 chose UUID v7 for session identifiers; the frontend
  writes it via `getOrCreateSessionId`). This `SessionId` is
  sufficient to reattach a returning player to their lobbies. OAuth
  remains explicitly out of scope.
- Persisting lobby state in Postgres turns the existing "Effacer mes
  données" flow into a real GDPR Article 17 obligation. The current
  grid-api `DELETE /v1/sessions/{sessionId}` deletes
  `puzzle_hint_usage` rows; game-api needs the equivalent surface, with
  cascade semantics that do not destroy other players' state.

## Decision

### a. Postgres adapter for game-api

`game/infrastructure` gains `PostgresLobbyRepository`, implementing the
existing `LobbyRepository` port (including the `mutate(id, mutator)`
contract whose `null` return atomically deletes — preserved). Stack
mirrors grid-api's `PostgresPuzzleRepository`: HikariCP for
the pool, raw JDBC for queries (no JPA, no Exposed), Flyway for
migrations under `game/infrastructure/src/main/resources/db/migration/`,
Testcontainers Postgres for the contract tests. Schema is a single
forward-only migration introducing `lobbies`, `lobby_players`,
`lobby_cell_entries`, with `ON DELETE CASCADE` on the children. The
`lobbies.id` column is `TEXT` to carry the base58 nanoid format from
ADR-0020.

### b. `BlissDatabase` is copied into game/, not shared

The grid context already ships a `BlissDatabase` helper (Hikari pool
lifecycle + Flyway invocation + structured logging). The natural urge
is to extract a shared `infra-common` module and reuse it.

We reject that. Konsist's `InfrastructureArchitectureTest` enforces
"no cross-context imports" (ADR-0001 §1). Reusing `BlissDatabase` from
`grid/infrastructure` inside `game/infrastructure` would require
`com.bliss.game.infrastructure.*` to import `com.bliss.grid.*`, which
the rule forbids. Creating a third module (`infra-common`) to host it
would force a Konsist allowance we do not want to grant — once we open
that door, future "small shared helper" PRs will widen it.

The class is ~50 lines, depends only on HikariCP + Flyway, and is
stable (no churn since it landed). Duplication is the chosen
trade-off. If the two copies diverge, each copy is the canonical
answer for its own context. This is recorded as a risk in the
Consequences section.

### c. Garbage-collector relaxation matrix

`LobbyGarbageCollector` is broadened from "evict WAITING after 30 min"
to a per-state matrix:

| State        | TTL              | Notes                                                          |
|--------------|------------------|----------------------------------------------------------------|
| IN_PROGRESS  | never evicted    | Players can return any time; this is the whole point of the change. |
| WAITING      | 24 hours         | Long enough for an owner to share a join code overnight; replaces today's 30 min. |
| COMPLETED    | 7 days           | Leaves a window for players to review the finished puzzle.     |

Implementation lives in `LobbyGarbageCollector`; the port gains
`findIdleCompleted(cutoff)` alongside the existing `findIdleWaiting`,
and both in-memory and Postgres adapters implement it. TTLs are
constructor-injected so tests do not have to wait wall-clock time. The
cutover sequencing is documented in the rollout plan §12.

### d. Query path: `sessionId → lobbyIds`

The `LobbyRepository` port gains:

```kotlin
suspend fun findBySessionId(sessionId: SessionId): List<Lobby>
```

It returns every lobby the session is or was a member of, in **every
lifecycle state** (WAITING, IN_PROGRESS, COMPLETED), ordered by
`lastActivityAt` descending. WAITING and COMPLETED are included so a
returning player can resume a not-yet-started game or review a
finished one — the matrix in (c) keeps both around long enough to be
useful. The Postgres adapter implements this as a single
`lobbies JOIN lobby_players ON lobby_id` query, indexed via
`lobby_players(session_id)`.

A new REST surface `GET /v1/sessions/{sessionId}/lobbies` exposes this
on game-api. The "My games" card on Accueil consumes it. No
pagination — see Consequences.

### e. OAuth migration path

When OAuth lands, `SessionId` becomes a deterministic projection of
`userId`, e.g. `SessionId(uuidv5(namespace = BLISS_USER, name = userId))`.
The database schema does **not** change. The `lobby_players.session_id`
column, the `lobby_cell_entries.written_by_session_id` column, and the
`lobbies.owner_session_id` column continue to mean "the identity of
this player as far as game-api can tell". Only the identity adapter
(the thing that resolves an HTTP request to a `SessionId`) changes,
from "read `localStorage`" to "resolve OAuth subject → deterministic
UUID". No data migration. No orphaned rows.

This is the central reason the schema is keyed by `SessionId` rather
than by a future `UserId` — keeping today's anonymous identifier in
the same column shape that tomorrow's authenticated identifier will
project into.

### f. RGPD erasure cascade — three rules

Persisting lobbies in Postgres turns "Effacer mes données" into a real
Article 17 obligation. game-api gains `DELETE /v1/sessions/{sessionId}`
mirroring grid-api's existing surface. The frontend Erase button calls
both grid-api and game-api and treats either failure as a hard error
(no "data erased" toast if half the cascade failed).

Per lobby that the erased `sessionId` is a member of, exactly one of
three rules applies. They are applied transactionally per lobby (one
`SELECT … FOR UPDATE` over every affected lobby in the Postgres
adapter; the per-lobby `ReentrantLock` in the in-memory adapter):

1. **Erased user owns the lobby and is the sole player.** The `lobbies`
   row is `DELETE`d. `ON DELETE CASCADE` removes the matching
   `lobby_players` and `lobby_cell_entries` rows automatically. No
   manual cleanup pass.
2. **Erased user owns the lobby with at least one remaining player.**
   Ownership is transferred to the **earliest-joined remaining
   player** (`ORDER BY joined_at ASC LIMIT 1` excluding the erased
   session). The erased user's `lobby_players` row is removed. Their
   `lobby_cell_entries` rows are anonymised: `written_by_session_id`
   set to `NULL` on Postgres, or to a zero-UUID sentinel in the
   in-memory adapter (the DTO mapper aligns the two). **Cell letters
   themselves are kept** — removing them would render the puzzle
   incoherent for the remaining players, which is a worse outcome than
   severing attribution.
3. **Erased user is a non-owner player.** The `lobby_players` row is
   removed and the `lobby_cell_entries` rows are anonymised as in
   Rule 2. `ownerSessionId` is unchanged.

The endpoint returns aggregate counts (`deletedLobbies`,
`transferredLobbies`, `removedPlayerships`, `anonymisedEntries`) and
always responds 200, even when no rows were affected — disclosing
presence is itself a leak, matching grid-api `SessionRoute.kt`'s
posture. Erasure is idempotent: re-running yields all zeros.

This is the **only** path in the codebase that transfers lobby
ownership. The regular `LeaveLobbyUseCase` deliberately does **not**
transfer ownership on owner-leave (see the rollout plan §10): the
owner is expected to come back tomorrow via "My games", and
prematurely promoting a replacement would make returning users
second-class citizens in their own lobby. Erasure is the opposite
case: the user is gone permanently, and leaving a "dead owner"
attached to a multi-player lobby would lock the remaining players out
of every owner-gated action (start, kick, change settings). Hence the
transfer.

## Consequences

**Easier.**

- Multi-day games are possible. The original user need ("start tonight,
  finish tomorrow") becomes the default path.
- Lobby state is durable across `game-api` rollouts. Today every
  rolling restart is silent data loss; after this change it is a
  no-op.
- Real Article 17 compliance for game-api state. The privacy notice
  already commits to erasure; today's in-memory store made that vacuous
  for lobbies, and this ADR makes it operational.
- A returning player can find their games without bookmarking a URL or
  remembering a 6-character join code, via the "My games" card.

**Harder / new costs.**

- One more Flyway migration set to maintain. Standard cost.
- `BlissDatabase` is duplicated between `grid/infrastructure` and
  `game/infrastructure`. Documented in §b above. If a third context
  appears, that is the trigger to re-evaluate `infra-common`; until
  then, two copies is the lower-friction answer.
- **Orphan owner edge case.** A user can clear `localStorage` without
  ever pressing "Effacer mes données". Their `SessionId` is then
  unreachable but `ownerSessionId` rows in `lobbies` still point at
  it. Players can keep playing; only owner-gated actions are
  unavailable on those lobbies. The rollout plan §10 documents the
  trade-off in `LeaveLobbyUseCase`'s KDoc. OAuth makes this edge case
  disappear because identity will be server-recoverable.
- **Backup-window residue.** CNPG backup retention will keep deleted
  lobby rows in cold storage beyond the erasure call, exactly as the
  privacy notice already discloses for `puzzle_hint_usage`. No new
  disclosure required.
- **Anonymised attribution shape divergence between adapters.** The
  Postgres adapter uses SQL `NULL` on `written_by_session_id`; the
  in-memory adapter uses a zero-UUID sentinel because
  `CellEntry.sessionId` is non-nullable today. The DTO mapper papers
  over the difference. If a future UI needs to render "erased user"
  distinctly from "real user", we promote `CellEntry.sessionId` to
  `SessionId?` in the domain — explicitly out of scope here.

**Out of scope (deliberate non-goals).**

- **Manual ownership transfer** between two living users. Erasure is
  the only ownership-mutation path; leave is not. If a "promote
  player" use case becomes necessary, it gets its own ADR.
- **Pagination** on `GET /v1/sessions/{sessionId}/lobbies`. A single
  anonymous session naturally caps at a handful of lobbies; cursor
  pagination is a future change only if real usage proves it
  necessary.
- **OAuth identity adapter.** The schema is shaped to accept it (§e);
  the implementation is a separate ADR when that work begins.

## Amendment 2026-05-12 — exclude WAITING lobbies from listing

The original design (§d) returned lobbies in all lifecycle states.
Field feedback showed this conflated "salons d'attente" with
"parties": un-started lobbies appeared in "Mes parties" and produced
confusing 404 errors when the WAITING TTL evicted them between fetch
and click. The listing now returns only IN_PROGRESS and COMPLETED.
WAITING lobbies remain joinable via direct URL / invite code. The GC
matrix in §c is unchanged; only the read projection narrows.

## Amendment 2026-05-13 — split CNPG cluster into its own Helm chart

The original cutover (§a) packaged the CNPG `Cluster` CRD inside the
api Helm chart at `game/api/deploy/chart/templates/postgres-cluster.yaml`.
That coupling produced a destructive loop on the very first prod
deploy: a fresh 3-instance CNPG cluster on Hetzner volumes bootstraps
in 5-10 min, longer than the api chart's `helm upgrade --wait
--timeout 5m`. Every failed api deploy auto-rolled-back to the
pre-cutover revision, which deleted the Cluster CRD, forcing the
next deploy to re-bootstrap from scratch. Four consecutive timeouts
were observed on 2026-05-12.

A first attempt (PR #403) annotated the Cluster with
`helm.sh/resource-policy: keep` to survive rollback. That annotation
only applies to `helm uninstall`, not `helm rollback`, so the loop
continued.

Resolution — split the chart in two:

- `game/api/deploy/db-chart/` — chart name `bliss-game-api-pg`,
  installed as Helm release `wordsparrow-game-api-pg` (matrix entry
  `db-release-name` in the CD workflow). Owns the CNPG `Cluster` CRD
  and its backup configuration. Install-once-update-rarely; the deploy
  workflow runs `helm upgrade --install` idempotently on every push,
  but the diff is empty in the steady state. The CNPG-managed Secret
  (`<release>-app`) and Service (`<release>-rw`) names line up with
  the api chart's `database.clusterName` value.
- `game/api/deploy/chart/` (Helm release `wordsparrow-game-api`)
  owns the api Deployment, Service, Ingress. It references the
  cluster's `<clusterName>-app` Secret for `DATABASE_URL` and
  hits `<clusterName>-rw` from a `wait-for-postgres` initContainer.
  The Cluster CRD is gone from this chart.
- The CD workflow (`.github/workflows/deploy-api-k8s.yml`) deploys
  the db chart first, then `kubectl wait --for=jsonpath
  '{.status.phase}'='Cluster in healthy state' ... --timeout=15m`,
  then the api chart. First-bootstrap pays the 15 m up front;
  steady-state deploys stay tight at 5 m.

Bounded-context separation is preserved: the db chart is part of
the game/ tree. Grid's chart is **not** split in this amendment —
its CNPG cluster has been live and healthy since the original
cutover, so the same destructive loop has never bitten it. Splitting
grid's chart is a follow-up.

## References

- [ADR-0001 — Parallel-agent development workflow](./0001-parallel-agent-development-workflow.md)
  (§3 schemas-first gate, §4 the 400-line PR cap this ADR fits inside,
  §6 reviewer rules, §7 ADR-before-implementation).
- [ADR-0003 — Cross-language API contract](./0003-cross-language-api-contract.md)
  (RFC 7807 problem envelopes for the new endpoints; explicit
  required/nullable rules the OpenAPI for PR #1 will follow).
- [ADR-0009 — Self-managed k8s deployment](./0009-self-managed-k8s-deployment.md)
  (CNPG is the cluster Postgres operator; the existing pattern this
  ADR reuses).
- [ADR-0018 — Game bounded context and realtime](./0018-game-bounded-context-and-realtime.md)
  (the "persistence deferred to Wave D+" statement this ADR closes).
- [ADR-0020 — LobbyId base58 nanoid](./0020-lobby-id-base58-nanoid.md)
  (`lobbies.id` is `TEXT`, base58 nanoid, not a UUID).
- [ADR-0021 — UUID v7 session id generation](./0021-uuidv7-session-id-generation.md)
  (the anonymous `SessionId` this schema is keyed by, and the column
  shape OAuth will later project into).
- Rollout plan that this ADR shapes (out-of-repo): see §10 of that
  plan for the leave-no-transfer rationale, §11 for the RGPD cascade,
  and §12 for the GC matrix and cutover sequencing.
- Privacy docs updated by PR #11 of the rollout (the three-rule
  cascade is mirrored verbatim into user-facing language):
  - [`docs/privacy/privacy-notice.md`](../privacy/privacy-notice.md)
  - [`docs/privacy/retention-schedule.md`](../privacy/retention-schedule.md)
