# ADR-0018: `game/` Bounded Context and Real-Time Architecture

## Status

Accepted

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
