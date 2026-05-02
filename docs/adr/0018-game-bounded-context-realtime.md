# ADR-0018: Game Bounded Context and Realtime Architecture

## Status

Accepted

## Context

ADR-0006 §5 deferred WebSocket support "until multiplayer ships." That condition
is now met: Bliss requires a real-time multiplayer crossword experience where
players in the same lobby see each other's letter placements with sub-second
latency.

Three architectural decisions need to be recorded before the `game/` bounded
context can land:

1. **New bounded context boundary.** Multiplayer gameplay is functionally and
   operationally distinct from the grid-generation pipeline (`grid/`) and any
   future `accounts/` context. It owns lobby lifecycle, player slots, session
   state, and game synchronisation. Scoping it as `game/` follows the manifesto
   pattern: domain → application → infrastructure → api.

2. **Transport.** The coordination surface is inherently event-driven and
   bidirectional. HTTP polling would work but introduces unnecessary latency and
   server load. SSE (server-sent events) is unidirectional. WebSocket is the
   standard for full-duplex real-time browser ↔ server communication.

3. **Conflict resolution on concurrent cell writes.** Two players may write to
   the same cell within the same network round-trip. A deterministic, stateless
   tie-breaking rule is required so all clients converge without coordination.

Alternatives considered:

- **WebRTC data channel** — no server relay needed, but requires a signalling
  server anyway and complicates auth/observability. Rejected: premature for
  ≤ 8 players per lobby.
- **CRDTs for cell state** — correct in theory, but crossword cells are not
  commutative (the final letter is a scalar choice, not a set). CRDT overhead
  is unjustified here.
- **Operational transforms** — well-understood but complex to implement
  correctly. Overkill for a 15×15 grid with ≤ 8 writers.

## Decision

### 1. `game/` bounded context

The `game/` directory is the authoritative owner of:

- **Lobby lifecycle** — states `WAITING → IN_PROGRESS → COMPLETED`.
  `WAITING`: players joining and configuring the grid. `IN_PROGRESS`: active
  gameplay after the owner starts. `COMPLETED`: all cells match the solution.
- **Player slots** — max 8 players per lobby, identified by client-generated
  UUID v7 `sessionId` (persisted in `localStorage`, survives page refresh).
- **Game session** — the puzzle fetched from `grid/` on start plus the
  server-authoritative cell state.

`game/` does not import from `grid/`'s domain or application packages (ADR-0001
§1). It fetches puzzle data from `grid/` over HTTP at start time and forwards the
document verbatim to clients.

### 2. WebSocket transport

One WebSocket connection per (browser tab × lobby), at:

```
/v1/lobbies/{lobbyId}/ws
```

The client opens the socket after fetching the lobby via REST and sends
`joinLobby` as its first frame. The server replies with a `lobbyState` snapshot,
then streams incremental events. Every frame is a JSON object with a top-level
`type` discriminator.

The REST control plane (`game/api/openapi.yaml`) is planned but not yet merged;
it covers lobby CRUD and is a follow-up to this spec PR.

### 3. Last-write-wins conflict resolution, server-stamped

Cell-update conflict policy: **last-write-wins, server-stamped**.

- The client sends `cellUpdate { row, col, letter }`.
- The server accepts it unconditionally, stamps `writtenAt`, and broadcasts
  `cellUpdated` to all subscribers (including the sender).
- Clients apply `cellUpdated` unconditionally. The server's `writtenAt` is the
  tie-breaking clock; the last server-accepted write wins.

Rationale: crossword cells converge to a single letter regardless of arrival
order. The complexity of OT or CRDTs is not warranted. Players can see each
other's edits and resolve conflicts interactively.

### 4. Reconnection window

A client that closes and reopens the WebSocket within approximately 30 seconds
retains its slot. The server identifies the player by `sessionId`. After the
window expires the slot is freed, and the player must rejoin (which may be
rejected if the lobby is full or `IN_PROGRESS`).

## Consequences

### Easier

- Lobby and game logic are isolated in `game/`; the `grid/` pipeline is
  unaffected by multiplayer concerns.
- LWW with server timestamps is simple to implement, test, and explain to
  players ("last one to type wins").
- WebSocket framing is self-describing (`type` discriminator); forward-compatible
  extension is straightforward.
- `sessionId` in `localStorage` means page refresh does not lose a player's
  slot within the reconnection window.

### Harder

- `game/` must maintain in-memory session state (lobby membership, cell state).
  Horizontal scaling requires a shared-state store (e.g. Redis pub/sub) before
  the service runs on more than one instance.
- LWW means two simultaneous writers to the same cell produce one winner and one
  loser with no merge — players must re-type if they are outpaced. This is
  acceptable for the casual crossword use case.
- The REST control plane (`game/api/openapi.yaml`) is a follow-up; until it
  lands, lobby creation/listing is undocumented on the wire.

### Different

- ADR-0019 records the companion decision to use AsyncAPI 2.6 rather than 3.x
  for `game/api/asyncapi.yaml`.
- The `grid/` context is now called over HTTP from `game/` at game-start time;
  this is the first cross-context runtime dependency in Bliss.
