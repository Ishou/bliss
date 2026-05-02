# ADR-0018: `game/` Bounded Context and Real-Time Architecture

## Status

Accepted — Implemented 2026-05-02

## Context

Bliss today is a single-player French mots-fléchés app. The frontend
loads a puzzle from the stateless `grid/` Ktor API on a single route
(`/`) and renders it via `Grid.tsx`. There is no concept of users,
sessions, or shared state: every keystroke stays in the browser, and
nothing on the server is mutable per-player.

The next workstream introduces a multiplayer mode: a player creates a
lobby, gets a shareable URL, up to eight players join via that URL, the
lobby owner picks a grid size and starts the game, and all players type
into the same grid in real time. A timer runs from the start of the
game until the grid is solved. Solo mode at `/` is preserved
unchanged — multiplayer is additive.

This trigger resolves an explicit deferral. ADR-0006 §5 picked SSE for
v1 live updates and stated that "WebSocket is reintroduced when the
multiplayer feature ships (real-time bidirectional)." That moment has
arrived, and a single ADR needs to capture: the new bounded context,
the transport, the state model, the conflict policy, the lobby
contract, the deploy constraints, and the future-work path.

The work is well over the 400-line PR cap (ADR-0001 §4) — twenty-two
PRs across nine dispatch waves — so the architectural decisions have
to be settled before any implementation PR opens. Schemas first
(ADR-0006 §spec-first); this ADR is the gate.

## Decision

### 1. New bounded context: `game/`

A new top-level bounded context alongside `grid/`, with the same
Gradle-module shape:

- `game:domain` — `Lobby`, `Player`, `GameSession`, `GameState`,
  `LobbyId`, `SessionId`, `GridConfig`. Pure Kotlin; no Ktor, no
  vendor SDKs.
- `game:application` — use cases (`CreateLobby`, `JoinLobby`,
  `RenameSelf`, `SetGridConfig`, `StartGame`, `UpdateCell`,
  `LeaveLobby`, `EvaluateSolved`); ports
  (`LobbyRepository`, `PuzzleProvider`, `Clock`,
  `LobbyEventBroadcaster`).
- `game:infrastructure` — adapters implementing the ports.
- `game:api` — Ktor module, REST + WebSocket endpoints, Helm chart.

`game/` communicates with `grid/` via HTTP only. No cross-context
domain or application imports — CLAUDE.md prohibits them and ArchUnit
enforces it. The puzzle fetch goes through an `HttpPuzzleProvider`
adapter behind the `PuzzleProvider` port.

### 2. Transport: WebSocket

Ktor `install(WebSockets)` on the `game:api` module. Channel:
`/v1/lobbies/:lobbyId/ws`.

Why WebSocket over SSE for this workload:

- **SSE is one-way (server → client).** Player keystrokes would each
  need their own POST, doubling the round-trip count and forcing
  reconnection logic on every update. The collaborative-editor
  pattern is bidirectional by nature.
- **Ktor's `WebSockets` plugin is first-party** (already in the
  selection-space evaluation in ADR-0006 §1) — no third-party adapter
  to vet.
- **ADR-0006 §5 explicitly named multiplayer as the trigger** for
  reintroducing WS. This ADR resolves that deferral.

### 3. State: in-memory, single replica (v1)

Lobby state lives in a `ConcurrentHashMap<LobbyId, Lobby>` inside the
`game:api` JVM. Per-lobby mutations are guarded by a `ReentrantLock`
to make state transitions (join, cell write, solve detection) atomic.

This forces a hard `replicas: 1` constraint on the `game-api` Helm
chart (ADR-0009 deploys to k3s on Hetzner; the chart is a copy of
`grid/api/deploy/chart/` with the replica count pinned and a
WebSocket-friendly `proxy-read-timeout` ingress annotation). A
backend restart drops every active lobby. This is accepted for v1:
rolling deploys are scheduled, and the upgrade path to multi-replica
is documented below.

### 4. Conflict resolution: last-write-wins, server-stamped

Every `cellUpdate` is stamped with a server `writtenAt`. The lobby's
authoritative state is the latest write per cell. The server
rebroadcasts the canonical value on `cellUpdated`, so any
latency-induced disagreement between clients converges on the next
broadcast. This is the standard collaborative-editor convention and
introduces no UX friction at human typing speeds.

### 5. Lobby contract

- **`/lobby/:lobbyId`** — frontend route. `lobbyId` is an 8-character
  base58 nanoid (URL-safe, low collision probability for the lobby
  population we expect, short enough to share verbally).

  > **Exception to ADR-0003 §6 (UUID v7 identifiers):** `lobbyId`
  > uses an 8-character base58 nanoid rather than UUID v7 because a
  > lobby code must be short enough to share verbally and type without
  > error; a 36-character UUID v7 string is unsuitable for that UX.
  > The trade-off accepted is slightly reduced uniqueness guarantees
  > (acceptable at expected lobby volumes) and a deviation from the
  > cross-context identifier convention. `sessionId` (§6) is correctly
  > UUID v7 and is unaffected.
- **Max 8 players** per lobby. Acts as natural rate-limiting and
  bounds the broadcast fan-out per cell write.
- **30-second reconnect window.** Closing and reopening the WS keeps
  the slot — useful for mobile network blips and tab backgrounding.
  After 30 s the slot is freed; a reconnecting client falls back to
  the "join as new" UI.
- **Owner-driven game start.** The first joiner becomes the owner.
  They pick the grid size (forwarded to `grid/`'s
  `GET /v1/puzzles/{id}?width=N&height=N`) and click Start. Other
  players see a waiting room until then.

### 6. Anonymous players

Each browser generates a UUID v7 `sessionId` on first visit and stores
it in `localStorage` alongside an editable pseudonym (default:
`Joueur 1234` with a random suffix). The `sessionId` is sent on every
WebSocket frame; the server uses it to track reconnects within the
30 s window. No accounts, no auth in v1.

Pseudonym uniqueness is **not** enforced. Two players named "Alice"
coexist; the UI differentiates them with a `sessionId`-derived hue on
each player's avatar.

### 7. Threat model (v1 accepted risks)

This ADR introduces a two-tier authorization surface: `sessionId` (a
client-generated UUID v7 in `localStorage`) is the ownership
credential for owner-only operations (`setGridConfig`, `startGame`).
A STRIDE pass over the v1 design:

- **SessionId spoofing / ownership hijack** *(Elevation of Privilege)*:
  A client that learns another player's `sessionId` could issue
  owner-only commands in their name. Mitigation: the server binds
  `sessionId` to the WebSocket connection at join time; commands
  arriving on a connection whose bound `sessionId` does not match the
  stored owner `sessionId` are rejected. Accepted residual risk: no
  cryptographic challenge is performed — if a `sessionId` leaks on a
  plain-HTTP path, spoofing is possible. Fully mitigated in production
  by mandatory HTTPS/WSS.
- **Reconnect-window ownership takeover** *(Elevation of Privilege)*:
  During the 30-second reconnect window the owner's slot is reserved;
  a reconnecting client must present the same `sessionId` to reclaim
  it. A client presenting a different `sessionId` joins only as a new
  regular player — it never inherits owner status. After the window
  expires, ownership transfers to the next-oldest connected player
  server-side; this is an accepted UX trade-off, not a
  privilege-escalation vector.
- **Lobby enumeration / DoS** *(Denial of Service)*: The 8-character
  base58 namespace is ~2.8 × 10¹³ combinations; blind brute-force is
  infeasible. The 8-player hard cap bounds broadcast fan-out per
  lobby. Lobby creation rate-limiting is **not** implemented in v1;
  it is noted as a follow-up task once traffic data is available.
- **Out of v1 scope**: private / password-protected lobbies, spectator
  roles, cross-lobby DoS amplification, and cryptographic session
  binding. Each is explicitly deferred to a follow-up ADR.

### 8. Solo mode unchanged

The existing `/` route stays exactly as today. Multiplayer is a
separate route under `/lobby/:lobbyId`. No refactor of the solo flow,
zero regression risk for solo play. `Grid.tsx` is extended to accept
an optional `gameClient` prop; solo mode passes `undefined` and the
existing uncontrolled-input path (ADR-0002 §4) is unchanged.

> **Update (2026-05-02):** Wave B (PR #124) superseded the `gameClient`
> prop with `onCellChange?: (row: number, col: number, letter: string | null) => void`
> on `Grid.tsx`. The callback decouples the Grid component from any
> transport object — the parent owns the WebSocket client and decides
> what to do with each cell change — which aligns with the hexagonal
> architecture principle that UI components must not hold infrastructure
> references. Solo mode passes no callback (`undefined`), leaving the
> uncontrolled-input path unchanged. The `gameClient` design was
> superseded before any implementation used it. Wave H wires
> `onCellChange` to the WebSocket `cellUpdate` broadcast.

### 9. Schemas first

Per ADR-0006 §spec-first, the contract precedes the implementation:

- **`game/api/openapi.yaml`** — REST surface for lobby create
  (`POST /v1/lobbies`) and lobby fetch (`GET /v1/lobbies/:id`, used
  by the joining-page bootstrap loader).
- **`game/api/asyncapi.yaml`** — every WebSocket frame, both
  directions, on channel `/v1/lobbies/:id/ws`. Client→server messages:
  `joinLobby`, `renameSelf`, `setGridConfig` (owner only),
  `startGame` (owner only), `cellUpdate`, `leaveLobby`.
  Server→client broadcasts: `lobbyState` (snapshot on join),
  `playerJoined` / `playerLeft` / `playerRenamed`, `gameStarted`,
  `cellUpdated`, `gameSolved`, `error` (RFC 7807 style).
- **`grid/api/openapi.yaml`** — extended so
  `GET /v1/puzzles/{puzzleId}` accepts `?width=N&height=N`, allowing
  the lobby owner's chosen grid size to flow through.

Both new specs are linted in CI (Spectral + AsyncAPI ruleset) and
TypeScript clients are generated from them.

### 10. Deployment posture

MANIFESTO §CI/CD (`MUST`: "New features are deployed behind flags. Flags have an
expiration date. Expired flags fail CI.") applies to this workstream. This section
documents how the multiplayer feature satisfies that requirement.

**`game-api` backend service.** The entire `game:api` module is a new, independent
Helm chart that does not exist in the production stack until a deployment PR
explicitly provisions it. The REST and WebSocket endpoints are structurally absent
from production until that chart is applied — no running code to flag-gate. The
Helm chart deployment PR (Wave A) is itself the "deploy dark" gate for the backend.

**Frontend multiplayer routes.** The `/lobby/:lobbyId` route and the `POST
/v1/lobbies` call site will be wrapped behind a `FEATURE_MULTIPLAYER` flag,
evaluated at runtime via the project's flag interface (MANIFESTO: "simple runtime
interface — not a vendor SDK in domain code"). The flag defaults to `false` in all
environments; it is set to `true` in production only when the `game-api` Helm chart
is live and the end-to-end smoke tests pass. The flag expiration date (no more than
90 days from the Wave A merge that enables it) is set in the implementation PR that
introduces the flag — not in this ADR — per the convention that expiration dates are
tied to implementation, not architecture decisions.

**No permanent flag.** Once multiplayer is fully released, the flag is removed
(MANIFESTO: `MUST NOT` use permanent feature flags). That removal PR is the last
step of the Wave A workstream.

## Consequences

### Easier

- Collaborative play is a tractable feature on top of a clean
  bounded context, not a graft on the solo flow.
- End-of-game stats (duration, contributions per player) have a
  natural home in the `game:application` `EvaluateSolved` use case
  and can be surfaced without further architectural change.
- Future work — leaderboards, persisted accounts, replays — can
  hang off `game/` without re-shaping `grid/`.

### Harder

- Single replica caps throughput. Vertical scaling is the only lever
  in v1; once that runs out, the multi-replica upgrade is a
  significant change (see future work below).
- A backend restart drops every active lobby. Players see a
  disconnect banner and have to recreate the lobby. Operationally
  this means deploys are scheduled, never reactive.
- Reconnect UX needs care. The 30-second window must be visible to
  the player so a deliberate refresh feels safe, and the
  beyond-window "join as new" path must not look like a server
  error.

### Different — trade-offs accepted for v1

- **No private lobbies.** Anyone with the URL joins. The 8-cap is the
  rate-limiter. Password-protected lobbies are a v2 ADR.
- **No chat, no spectators, no cursor presence.** Each adds a
  message type and a UI surface; explicitly out of v1 scope to keep
  the PR count bounded.
- **No durable history.** Solved games are not archived. Future
  leaderboards / replays will need an event-log store; out of scope.
- **Pseudonym uniqueness not enforced** (see §6).

### Future work — multi-replica scale-out

When the single replica hits its limit, two paths are credible and a
follow-up ADR will pick one:

- **Sticky sessions + Redis pub/sub.** Ingress hashes by `lobbyId` so
  every WS for a given lobby lands on the same pod; cross-pod
  notifications (lobby creation, owner transfer) go through Redis.
  Lower latency, more moving parts.
- **Postgres event log + advisory locks.** Every mutation is an
  event row; per-lobby advisory locks serialize writes. Replicas are
  symmetric; persistence falls out for free. Higher latency, fewer
  moving parts, aligns with ADR-0009's preference for in-cluster
  Postgres.

Either path is reachable from the v1 in-memory shape because the
`LobbyRepository` and `LobbyEventBroadcaster` ports are the only
seams that need new adapters.

## References

- **ADR-0001 §4** — 400-line PR cap that drove the 22-PR / 9-wave
  workstream split.
- **ADR-0002 §4** — frontend uncontrolled-input pattern; preserved
  in solo mode and extended for multiplayer.
- **ADR-0006 §5** — SSE-for-v1 / WebSocket-deferred decision that
  this ADR resolves.
- **ADR-0009** — k3s on Hetzner deploy target; the `game-api` Helm
  chart is a single-replica clone of the `grid-api` chart shape.
- **ADR-0019** — AsyncAPI 2.6 retained over 3.x; granted as an
  exception during Wave A because Spectral's 3.0 ruleset was not
  yet stable.
- **ADR-0020** — `lobbyId` base58 nanoid (8 chars), formalising
  the §5 deviation from ADR-0003 §6 once implementation confirmed
  the trade-off held.
- **ADR-0021** — `sessionId` UUID v7 generation, reaffirming §6.
- **ADR-0022** — application-layer deviations from this ADR
  documented during Wave C (PR #127).

## Implementation

This ADR was opened as `Proposed` in Wave A on 2026-05-02 and walked
through the rollout in nine dispatch waves over the same day. All
contributing PRs landed against `main` via the §6a review-and-fix
cycle. Per ADR-0001 §9, the `git log` is the source of truth; this
list is a navigational index, not an authority.

### Wave A — schemas + ADR
- **PR #121** — `docs(adr): adr-0018 multiplayer realtime architecture` (this ADR, original `Proposed` form).
- **PR #122** — `feat(game-api): asyncapi 2.6 spec for lobby + game websocket events`.
- **PR #123** — `feat(grid-api): accept width/height query params on GET /v1/puzzles/{id}`.

### Wave B — domain types + frontend helpers
- **PR #124** — `feat(frontend-grid): optional onCellChange callback on useGridNavigation` (supersedes the `gameClient` prop sketched in §8; see in-line update note).
- **PR #125** — `feat(frontend-session): localStorage helper for sessionId + pseudonym`.
- **PR #126** — `feat(game-domain): Lobby, Player, GameSession domain types`.

### Wave C — application layer
- **PR #127** — `feat(game-application): use cases + ports for lobby + game lifecycle`. Drift from §1's port list captured separately in **ADR-0022**.

### Wave D — infra adapters + frontend domain port
- **PR #130** — `feat(frontend-game): domain types + GameClient port`.
- **PR #131** — `feat(game-infrastructure): in-memory LobbyRepository + per-lobby locks` (the `ConcurrentHashMap` + `ReentrantLock` shape from §3).
- **PR #132** — `feat(game-infrastructure): HttpPuzzleProvider adapter for grid REST`.

### Wave E — Ktor module skeleton + WebSocket client
- **PR #133** — `feat(game-api): bootstrap ktor module with websockets plugin + health route`.
- **PR #134** — `feat(frontend-game-infrastructure): websocket adapter for GameClient port` (the `WebSocketGameClient`).

### Wave F — REST + WS endpoints + Helm chart
- **PR #136** — `chore(game-api-chart): helm chart for game-api with replicas=1` (encodes the §3 single-replica constraint and the WS-friendly ingress annotation).
- **PR #137** — `feat(game-api): rest endpoints for lobby create + get`.
- **PR #138** — `feat(game-api): websocket endpoint + session manager + broadcaster`.

### Wave G — `/lobby/:lobbyId` route
- **PR #140** — `feat(frontend-game-ui): /lobby/:lobbyId route + GameClient context wiring`.

### Wave H — UI components + integration
- **PR #141** — `feat(frontend-grid): apply remote cellUpdated events to uncontrolled inputs`.
- **PR #142** — `feat(frontend-game-ui): WaitingRoom component for lobby waiting state`.
- **PR #143** — `feat(frontend-game-ui): connection-state banner component`.
- **PR #144** — `feat(frontend-ui): create-lobby button on home route`.
- **PR #145** — `feat(frontend-game-ui): live timer + end-of-game modal`.
- **PR #146** — `feat(frontend-game-ui): wire Wave H components into /lobby/:lobbyId`.

### Closure
- **PR #147** — `feat(frontend): enable VITE_FEATURE_MULTIPLAYER in production` (the §10 flag flip; preview environments stay `false` because MSW handlers for the lobby surfaces are not yet written — a separate workstream).
- **PR #148** — this closure update.

### Tangential follow-ups landed during the workstream
These were not on the wave dependency map but shipped during the
rollout window because the work surfaced during review:
- **PR #128** — `chore(skills): add dispatch playbook for parallel-agent rollouts`.
- **PR #129** — `chore(skills): add frontend / schemas / jvm-backend domain playbooks`.
- **PR #135** — `feat(game): support dual-clue mots-fleches cells across spec/domain/infra` (resolved a deferral from PR #132's review; touched spec + domain + infrastructure in one PR under an explicit user-approved exception to ADR-0001 §1).
- **PR #139** — `feat(frontend-game-infrastructure): http lobby client + openapi codegen for game/` (split out of Wave E and landed once `game/api/openapi.yaml` was on `main` after PR #137).

## Lessons learned

These are the recurring failure modes and cross-cutting observations
worth preserving for the next multi-wave rollout. They are bullets,
not narrative: each should map to a concrete change in playbook,
skill file, or CI rule.

- **`settings.gradle.kts` ↔ `Dockerfile` `COPY <module>/build.gradle.kts` drift is a recurring failure mode.** Adding a new Gradle module without also adding the matching `COPY` line to the JVM Dockerfile reliably broke CI in Waves D, E, and F. Three independent agents hit it before the `/jvm-backend` skill grew a checklist item. Lesson: when an agent adds a Gradle subproject, the same PR must touch the Dockerfile, and the skill file must say so explicitly.
- **Cross-PR port-shape changes during a parallel wave are dangerous.** PR #144's deploy went red because PR #143's port-shape change merged into `main` between #144's fork-point and CI's merge-commit `tsc -b`. Both PRs were green at fork time; the merge commit was where the type mismatch surfaced. Lesson: when a wave touches a shared port surface, freeze the port shape PR and let it merge first; do not parallelise PRs that change the same port's signature.
- **PRs must be opened with `gh pr create` *before* polling CI.** PR #146's integration agent committed and pushed but never opened the PR; its CI poll loop waited for checks that would never run because GitHub does not run the full CI matrix on a branch absent a PR. Lesson: agent briefs must list `gh pr create` as an explicit step before any CI poll, and the dispatcher template should not mark a workstream "in CI" until the PR URL is recorded.
- **Manual rebases happen and need a documented playbook.** PR #138 needed a manual rebase after #137 merged ahead, with three-file conflicts (`SystemClock`, `Module.kt`, `WebSocketFrameDto`). The rebase was straightforward but undocumented; a fixer agent without the prior context would have stalled. Lesson: when two PRs in the same wave touch overlapping module-level files, the second PR's brief should pre-declare the expected conflict surface.
- **Investing in skill files mid-rollout paid off.** PRs #128 and #129 (the `dispatch`, `frontend`, `schemas`, and `jvm-backend` playbooks) landed mid-Wave-D and reduced per-agent prompt boilerplate by ~30–50% on subsequent waves while locking in repeated gotchas (the Dockerfile-COPY rule, the em-dash test-name rule, the boundaries plugin layering rules). Lesson: do not wait until a rollout ends to extract skill files; the second occurrence of a gotcha is the trigger to write the skill, not the tenth.
- **Em-dashes in `@Test fun` names crash `compileTestKotlin` under POSIX-locale CI.** Three test files used Unicode em-dashes in backticked test names; locally they compiled, in CI they did not because the runner's `LC_ALL=C` cannot encode the bytes through `javac`'s source-file path. Lesson: ASCII-hyphen-only is now a hard rule in the `/jvm-backend` skill; lint or a pre-commit hook should enforce it rather than relying on agent memory.
- **The 400-line cap is a tension worth documenting, not a rule to silently break.** Several PRs blew the cap as cohesive single-context work — notably #127, #132, #137, and #138 (the largest at 1,167 LOC). They were merged anyway because splitting would have produced PRs that referenced each other's unmerged code, which fails CI's "compiles independently" gate. The cap exists for review fatigue, and the user-approved exceptions were honest about that. Lesson: when a wave's scope provably cannot be cut under 400 lines without producing dependent fragments, the brief should pre-declare the exception and note the reviewer-fatigue cost rather than relying on an in-flight ad-hoc waiver.
- **Pre-3.0 schema tooling sometimes requires its own ADR.** AsyncAPI 2.6 had to be granted an exception because Spectral's 3.0 ruleset was not yet stable; that exception is **ADR-0019**. Lesson: when a schema-first rollout depends on a spec version that lags upstream tooling, capture the version pin in its own short ADR — not as a footnote in the feature ADR — so the deferral has its own revisit trigger.

## Open questions

The §"Future work — multi-replica scale-out" question (sticky
sessions + Redis vs Postgres event log + advisory locks) is unchanged
and remains the next architectural decision when the single-replica
ceiling is hit. Persisted accounts and spectator/replay also remain
out of scope, deferred to their own future ADRs as originally noted.

Surfaced during implementation:

- **Reconnect-with-backoff in the WebSocket adapter is still stubbed.**
  The `ConnectionBanner` component (PR #143) renders a `reconnecting`
  state, but no logic in `WebSocketGameClient` (PR #134) emits that
  state — the adapter currently closes and reopens on the next user
  event without any backoff strategy. The 30-second reconnect window
  in §5 is correctly enforced server-side; the gap is purely client
  UX. A follow-up workstream should land a real backoff loop (e.g.
  exponential with jitter, capped at the 30 s server window) and wire
  it through the existing port surface so the banner reflects reality.
- **Lobby creation is not rate-limited.** §7's threat-model bullet
  acknowledged this as a v1 acceptance; no traffic data has yet
  emerged that argues for a specific limit, but the gap is now live
  in production and should be revisited once the flag has been
  bright for a month.
- **MSW handlers for the lobby REST and WebSocket surfaces do not
  exist.** PR #147 keeps `VITE_FEATURE_MULTIPLAYER=false` in preview
  environments specifically because the CTA would render and 500 in
  any PR preview that uses MSW. Closing that gap is a frontend
  workstream of its own.
