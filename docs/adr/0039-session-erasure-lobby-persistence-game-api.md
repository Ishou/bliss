# ADR-0039: Session Erasure and Lobby Persistence for game-api

## Status

Accepted

## Context

ADR-0018 §3 built the game-api's lobby store as an in-memory
`ConcurrentHashMap`, evicted after 30 minutes of inactivity. While
state was ephemeral, the "Effacer mes données" flow (ADR-0025 §5)
only needed to touch `grid/`'s `puzzle_hint_usage` table — game-api
held nothing that survived a session.

The multiplayer persistence rollout (Wave A: this ADR + schema;
Wave B: Flyway migration, Postgres adapter, `ListLobbiesForSession`
use case) changes that contract: lobbies will be durable in Postgres.
A returning player can find yesterday's lobby without a bookmark
(`GET /v1/sessions/{sessionId}/lobbies`). The moment lobby rows
survive beyond in-process memory, any `sessionId` that appears as
`ownerSessionId` or in the `players` list is personal data under
RGPD Article 4(1) — a pseudonymous identifier tied to a natural
person. Article 17 erasure becomes a real obligation on game-api,
not just on grid-api.

Three design questions need to be settled before any Flyway migration
lands:

1. **How should erasure cascade across the lobbies a session belongs
   to?** A session can be the sole owner, the owner with other players,
   or a non-owner member. Each role implies a different action.

2. **What anonymisation strategy preserves puzzle coherence for the
   remaining players?** Deleting an erased player's cell entries would
   create blank cells mid-puzzle for others; blanking their attribution
   but retaining the letters keeps the puzzle intact.

3. **Should the erasure endpoint leak whether a session has ever had
   data?** A 404 on an unknown session is itself a privacy disclosure;
   the grid-api sibling (`SessionRoute.kt`) already demonstrates the
   correct posture.

### Alternatives considered

**Delete all entries for the erased session.** Guarantees no personal
data remains. Rejected because it leaves holes in in-progress puzzles
for the remaining players, breaking the cooperative-game invariant that
every letter typed by a confirmed participant is canonical.

**Pseudonymise with a deterministic anonymised sessionId.** Replace the
erased `sessionId` in `cell_entries` with a stable "anonymous-NNN"
token so "this letter was written by the same erased player" is still
visible to the UI. Rejected because a stable token could, in practice,
re-link entries to the erased session if the erased user's letter
pattern is known to observers. Dropping the `sessionId` entirely
(NULL or constant anonymous sentinel) is the conservative choice.

**Return 404 for an unknown session.** Simple and conventional. Rejected
because presence disclosure violates ADR-0025 §5's stated design goal
("Effacer mes données" must not reveal whether a session exists). The
grid-api sibling sets the same precedent.

**Transfer ownership to the latest-joined remaining player.** Arbitrary
tie-breaking, not tied to any game-rule meaning. Rejected in favour of
the earliest-joined remaining player: the first joiner is usually the
player who kept the game alive longest and is the most natural
"inheritor" of the lobby.

## Decision

### 1. Three-rule erasure cascade

`DELETE /v1/sessions/{sessionId}` cascades across every lobby the
erased session is a member of according to three rules, applied
per-lobby:

- **Rule 1 — sole owner-player:** the erased user owns the lobby and
  is the only remaining player. The lobby is deleted outright; there
  is no game state left to preserve.

- **Rule 2 — owner with other players:** the erased user owns the lobby
  but at least one other player holds a slot. Ownership transfers to
  the earliest-joined remaining player. The erased user is removed from
  the players list. Their `cell_entries` rows are anonymised: the
  `writtenBySessionId` column is cleared (NULL), letters retained.

- **Rule 3 — non-owner member:** the erased user is a regular player.
  They are removed from the players list. Their `cell_entries` rows are
  anonymised identically to Rule 2. The lobby and its owner are
  unchanged.

The response body (`DeleteSessionResponse`) reports per-rule counts —
`deletedLobbies`, `transferredLobbies`, `removedPlayerships`,
`anonymisedEntries` — so the frontend can surface a confirmation
("your data has been erased from N lobbies") without knowing the
implementation detail.

### 2. Anonymisation strategy

Cell entries belonging to the erased session have their
`writtenBySessionId` set to NULL. The letter value is left in place.
From the application layer's perspective the entry now belongs to no
one: it will never be attributed to any player in a `CellEntry` REST
response or `cellUpdated` WebSocket broadcast, but the puzzle grid
remains complete for any remaining players.

This is the only change to the `lobby_cell_entries` table for an
erased session. No rows are deleted; the foreign-key referential
integrity on the lobby itself is maintained.

### 3. Always-200, never-leak-presence

The endpoint returns `200` in all cases, including when the supplied
`sessionId` has never appeared in any lobby. The response body will
contain all-zero counts. A `404` would disclose whether a session
existed — itself a RGPD Article 5(1)(f) confidentiality concern and
inconsistent with the "Effacer mes données" privacy guarantee in
ADR-0025 §5.

This mirrors the grid-api's `DELETE /v1/sessions/{sessionId}` posture
(`SessionRoute.kt`); the frontend fans the two calls out in parallel.
Both must return 200 before the UI claims data was erased.

### 4. Lobby listing endpoint posture

`GET /v1/sessions/{sessionId}/lobbies` returns `200 []` (empty array)
when the session has no lobby memberships, rather than `404`. Same
rationale: a `404` leaks session history. The empty-array response is
non-ambiguous because the frontend already uses `200 []` as the
"no lobbies" state for the "Mes parties" card.

### 5. Optional `title` on `Lobby` and `CreateLobbyRequest`

The owner may supply a human-friendly label at creation (e.g. "Mardi
soir", "Game A"). The field powers the "Mes parties" card label on
Accueil; when absent the frontend falls back to a date-derived label.
Per ADR-0003 §6, the field is absent OR present on the wire — never
`null`. It is therefore not in the `required` list and carries no
`nullable: true`.

### 6. Schema-first gate

Per ADR-0001 §3, the OpenAPI changes (this PR) land before the Flyway
migration, Postgres adapter, and use-case PRs. The schema is the
source of truth for the wire contract; the implementation must
conform to it, not the other way around.

## Consequences

### Easier

- The "Effacer mes données" flow is now complete: the frontend fans
  `DELETE /v1/sessions/{sessionId}` to both grid-api and game-api; both
  return `200`; the UI shows a single confirmation.
- The three-rule cascade covers every membership role a session can
  hold, so the implementation has no edge-case branching outside these
  rules.
- Anonymised entries keep completed lobbies coherent for the remaining
  players — no partial puzzle breakage.
- The `title` field unlocks lobby labelling without requiring a
  migration later; Wave B can persist it as a nullable column with no
  schema change needed.

### Harder

- The Postgres adapter (Wave B) must implement all three rules
  transactionally. A partial failure (e.g. ownership transferred but
  entries not anonymised) would leave inconsistent state. The use-case
  must run the cascade inside a single transaction.
- Returning all-zero counts for unknown sessions means the frontend
  cannot distinguish "data erased" from "no data existed". This is
  intentional (presence non-disclosure) but means the confirmation copy
  must be written to cover both cases ("your request has been
  processed") rather than "N records deleted".
- Ownership-transfer by earliest-joined player is a server-side
  business rule that the frontend cannot predict. Any UI that shows
  the current owner name must re-fetch after a deletion.

### Different

- Lobby persistence is the first durable per-user state in game-api.
  Every future feature that touches `sessionId` in game-api (e.g.
  per-session stats, favourites) inherits the Article 17 obligation
  documented here and must extend the cascade accordingly.
