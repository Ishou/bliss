# ADR-0029: Owner-rotated lobby join code

## Status

Accepted

## Context

ADR-0027 made the WebSocket `joinLobby` frame code-gated and minted the
lobby's join `code` once at create-time. A leak of that single code
(streamer reads it aloud, viewer screenshots the WaitingRoom, screen
share captures the chip) currently leaves the owner with one option:
abandon the lobby and create a fresh one. That kicks every legitimate
player and breaks any in-flight share link.

The product wants the multiplayer flow usable on streams. ADR-0027 §3
("Information disclosure") explicitly carries a residual "members who
have already joined keep their access" risk, but the worse story today
is the **non-member** who learns the code from the stream and then
joins indefinitely until the lobby is torn down. Today there is no
remediation short of a full restart.

The user's framing during planning:

- *"I like the code rotation thing, let's plan that."* — preference for
  in-place rotation over re-creation.
- *"Already-joined players should not get kicked."* — preserve the live
  game / waiting room as members experienced it.

ADR-0001 §"Threat Modeling Before Building" requires a model for
authn / authz changes. ADR-0027's threat model is updated by reference:
this ADR adds a *mitigation* for one of its residual risks rather than
introducing a new attack surface.

## Decision

Add an owner-only `rotateCode` operation that mints a fresh
`LobbyCode` for an existing lobby in place, broadcasting the new value
to all currently-connected sessions via the existing `lobbyState`
snapshot frame. The lobby's identity (`LobbyId`), membership, and game
state are unchanged; only the join authority is replaced.

Concretely:

1. **New `RotateCodePayload` (client→server)**: `{ "type": "rotateCode" }`.
   No body — the WebSocket session is already bound to a `sessionId` (the
   `joinLobby` step), so owner identity is implicit.

2. **`LobbyStatePayload` gains a required `code` field.** Currently the
   snapshot does not carry the join code (the frontend preserves it
   from the prior reducer state), which works at create-time but
   leaves no way to deliver a *changed* code over the wire. Making
   `code` first-class on the snapshot is one field, one source of
   truth, no split-brain between two delivery channels. The frontend
   reducer simplifies to `code: event.code` rather than preserving.

3. **`RotateLobbyCodeUseCase`** in `:game:application`:
   - read lobby; reject `LobbyNotFound` if missing.
   - reject `NotOwner` if `sessionId` is not the owner.
   - mint a fresh `LobbyCode` via the existing collision-retry
     helper (lifted out of `CreateLobbyUseCase` to a package-private
     `mintUniqueCode` so both call sites share one implementation).
   - mutate the lobby in place; emit a new `LobbyEvent.CodeRotated`
     that the route translates into a refreshed `lobbyState` broadcast
     (same posture as `GridConfigChanged` per the wire-mapping note in
     `LobbyEvent.kt`).

4. **Reconnects unaffected.** `JoinLobbyUseCase`'s reconnect branch
   (`lobby.hasJoined(sessionId) -> lobby.touched(...)`) keys on
   `sessionId` and never reads `code`. Already-joined players stay
   joined across rotations; refresh / tab-recovery still works without
   re-supplying the code.

5. **`code` is never on a URL.** ADR-0027's URL design stays — the
   only share affordance is `/join/$code`, which redirects to
   `/lobby/$lobbyId` with `replace: true`. After rotation, the
   "Copier le lien" button copies a fresh `/join/$NEWCODE` URL; the
   pre-rotation share URL is invalidated by the same gate that
   ADR-0027 already enforces.

6. **Frontend "Régénérer le code" button** in the WaitingRoom, owner-only,
   adjacent to the readonly PIN slots. Brief `aria-busy` round-trip
   state during the WS dispatch. UX copy ("Le code actuel ne fonctionnera
   plus") sets honest expectations per the threat model below.

## Consequences

### Easier

- Streamer can mint a fresh code in-place after a leak without
  recreating the lobby; legitimate players keep their seats. The hostile
  "abandon and re-create" remediation goes away.
- The `lobbyState` snapshot now carries every wire-relevant lobby
  field. Future "lobby fields changed" work (renaming, capacity,
  difficulty knobs once they land) reuses the same snapshot path
  rather than each adding a bespoke event frame.
- The Phase A asyncapi follow-up (`code` was deliberately omitted from
  the snapshot in PR #273) closes here.

### Harder

- **Information disclosure** (residual, unchanged shape): a viewer who
  has *already joined* before rotation still sees the new code in
  their `lobbyState`. Rotation does not expel attackers who got in;
  it only invalidates pending share-links / unjoined viewers / the
  pre-rotation URL. Documented on the button tooltip so owners do not
  conclude rotation is a "kick" affordance.
- The lobby snapshot now leaks `code` to every subscribed socket. This
  is acceptable: every subscribed socket is already a member (the
  code-gate per ADR-0027 admits no one else), and members already had
  access via `Lobby` REST anyway (see Threat model → Information disclosure below).

### Different

- Frontend lobby-route reducer's `case 'lobbyState'` becomes
  `code: event.code` rather than `code: current.lobby.code`. The
  preservation comment introduced in PR #273's reducer goes away.
- One `LobbyEvent.CodeRotated` is emitted per rotation; the route maps
  it to `null` (no dedicated frame) and re-broadcasts the snapshot,
  matching how `GridConfigChanged` already works.

## Threat model (STRIDE-lite)

Per the manifesto's *Threat Modeling Before Building*. Categories with
no delta from ADR-0027 are noted for completeness.

### Spoofing — *attacker rotates the code without owning the lobby*

`RotateLobbyCodeUseCase` checks `lobby.isOwner(sessionId)` both before
the mint and re-checks inside the `repo.mutate` lambda (the
canonical TOCTOU posture documented in `SetGridConfig` /
`LeaveLobby` use cases). A non-owner caller receives `UseCaseError.NotOwner`
and the lobby is not mutated.

**Mitigated.**

### Tampering — *attacker submits a chosen code via the rotateCode frame*

The frame carries no payload (`{ "type": "rotateCode" }`). The new code
is server-minted via `LobbyCode.generate()` which already passes
property-based generator tests (`LobbyCodeTest`). The client cannot
inject, suggest, or constrain the value.

**Mitigated.**

### Repudiation

Out of scope, same as ADR-0027. Rotation is logged at the analytics
sink (`AnalyticsEvent.LobbyCodeRotated`); no audit trail beyond that.

**No delta.**

### Information disclosure — *attacker reads the lobby state with a stale code*

Two cases:

1. *Pre-rotation member, unchanged*: see "Different" above. A member
   who joined under the old code keeps their seat and learns the new
   code from `lobbyState`. Rotation does not address this and is not
   marketed as doing so.
2. *Non-member with the old code*: blocked by the existing ADR-0027
   `wrong-code` gate. Pasting the pre-rotation `/join/$OLDCODE` URL
   resolves the lobby snapshot via REST (no change there) but the WS
   join is rejected. This is the case rotation actually mitigates.

**Mitigated** for the non-member case; **residual** for the
already-joined-member case (called out in UX copy).

### Denial of service

Rotation is O(1) on the in-memory store + a bounded mint retry
(`MAX_CODE_MINT_ATTEMPTS = 8`). Owner-only gate eliminates the broad
attack surface; only the lobby owner can dispatch the frame, and an
owner who spams rotation merely inconveniences themselves and the
people they invited. Not worth a rate-limit in v1; revisit if
telemetry shows abuse.

**Mitigated.**

### Elevation of privilege

Rotation does not transfer ownership. The owner stays the same player
across the rotation; the only thing that changes is the bytes of the
join code.

**Not applicable.**

## Notes

- The "streamer-mode toggle" alluded to during ADR-0027's planning
  (hide *Copier le lien* entirely behind a per-session preference)
  remains out of scope. It is additive UI on top of this ADR and does
  not change the wire contract.
- `LobbyCode.generate()` is unchanged; the generator's keyspace
  (`32^6 ≈ 1.07B`) and bounded retry semantics are documented in the
  domain class's KDoc and not duplicated here.
- The MSW preview mode mirrors the rotation handler so review deploys
  exercise the wire path end-to-end without a real backend.
