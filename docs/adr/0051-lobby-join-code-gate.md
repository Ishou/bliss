# ADR-0051: Code-gated lobby join (streamer-friendly)

## Status

Accepted

## Context

PRs #261 / #262 / #263 introduced a six-character Crockford-style `code`
on the `Lobby` resource and a `GET /v1/lobbies/by-code/{code}` lookup
endpoint, with the Accueil "Rejoindre avec un code" flow reading the code
client-side and navigating to `/lobby/$lobbyId`. The wire-level join,
however, still keys solely on `lobbyId`: the WebSocket `joinLobby` frame
carries `sessionId` + `pseudonym` and the server's `JoinLobbyUseCase`
adds the player to the lobby on any well-formed call.

Concretely:

- A streamer with `/lobby/abc12345` visible in the address bar leaks
  effective join authority to every viewer who can copy 8 characters.
- The `code` is currently surfaced in the WaitingRoom but does no
  authorization work — it's an honest-system token, not a security
  boundary.

This is a real concern: ADR-0018 §3 documents lobbies as in-memory and
short-lived, and the product wants the multiplayer flow usable on
streams without "raid the streamer's lobby" being a one-paste attack.

The user's constraints (verbatim during planning):

- *"Code shouldn't be seen in the URL"* — share via the address bar
  must not be enough.
- *"LobbyId shouldn't be required to join, only the code"* — the
  joiner's mental model is "I have a code, I get in."

ADR-0001 §"Threat Modeling Before Building" requires a model on
authn / authz changes; ADR-0003 §6 is unchanged but the WebSocket
contract carried by `game/api/asyncapi.yaml` gets a new optional
field.

## Decision

The WebSocket `joinLobby` frame becomes **code-gated for new joiners**
and **bypassed for already-joined sessions** (the existing reconnect
branch in `JoinLobbyUseCase`).

Concretely:

1. **`JoinLobbyPayload` gains an optional `code: string`** (Crockford
   pattern `^[A-HJKM-NP-Z2-9]{6}$`). Optional on the wire because
   reconnects re-send the same frame without re-typing the code; the
   server enforces required-for-new-joiners at the use-case layer.

2. **`JoinLobbyUseCase` validates `code == lobby.code.value` after the
   reconnect branch.** Reconnecting sessions (`lobby.hasJoined(
   sessionId)`) bypass the check by construction so a refresh /
   tab-recovery flow does not need the code.

3. **A new `WrongCode` error kind** (`UseCaseError.WrongCode`) maps to
   a typed `error` frame with URI
   `https://bliss.example/errors/wrong-code` and HTTP-style status
   `403`. The frontend lobby route renders an inline banner and does
   NOT mount the `WaitingRoom` — the un-authorised user lands on a
   read-only-snapshot view with an explanation.

4. **The lobby URL `/lobby/$lobbyId` does not carry the code** in any
   form (path, query, fragment). The frontend stashes the code in
   per-tab `sessionStorage` (one-shot, cleared after the WS handshake)
   to thread it from the navigation that produced the route mount —
   either the Accueil "Rejoindre" submit or a click on a `/join/$code`
   share link.

5. **A new public route `/join/$code`** is the one share-link surface.
   It resolves `findByCode(code)` server-side, stashes the code in
   `sessionStorage`, and `navigate({ replace: true })` to
   `/lobby/$lobbyId`. The share URL never lingers in browser history;
   the address bar shows only the routing slug after the redirect.

6. **`Copier le lien` in the WaitingRoom** copies a `/join/$code` URL,
   not the routing URL.

## Consequences

### Easier

- Sharing a lobby via a link in Discord / SMS / etc. still works —
  the recipient pastes a short URL, and the redirect lands them in the
  lobby with a single click.
- A streamer with the routing URL on stream is no longer leaking join
  authority. Viewers who copy `/lobby/$lobbyId` reach the snapshot but
  the WS join fails.
- Reconnects after refresh stay frictionless — the existing
  sessionId-keyed path is unchanged.
- Future "streamer mode" (hide `Copier le lien`, force verbal /
  Discord-only sharing) is purely additive UI on top of this gate.

### Harder

- **Information disclosure on `/lobby/$id`**: viewers can still GET
  the snapshot (lobby state, players, gridConfig). This is unchanged
  from today; flagged as residual risk. Tightening it would require
  per-request authorization on the REST surface (out of scope here).
- A user who closes the tab and reopens the routing URL without being
  a member (e.g. they were forwarded a `/lobby/$id` link by a stream
  viewer) cannot recover by guessing the code — they must be sent a
  `/join/$code` share link. Acceptable: the alternative is leaving the
  attack vector open.
- The `joinLobby` frame loses backward compatibility with clients that
  don't know about `code` for new-joiner paths. Reconnects still work
  unchanged. In practice the only client is this repo's frontend, so
  the migration is atomic.

### Different

- The frontend's `findByCode` REST adapter (PR #262) now feeds two
  surfaces: the in-app PIN input on Accueil AND the share-link route.
- The lobby route's `wrong-code` error banner is a new UI surface; it
  shares copy with the Accueil 404 path so the message is consistent
  whether the user typed the code or followed a link.

## Threat model (STRIDE-lite)

Per the manifesto's *Threat Modeling Before Building*. Only items where
the new design changes the attack surface are listed; categories with
no delta from today are noted for completeness.

### Spoofing — *attacker pretends to be a legitimate joiner*

Pre-change, any party with `lobbyId` could call `joinLobby` and be
indistinguishable from an invited friend. After this ADR, the attacker
also needs `code`, which is shared out-of-band. The two share channels
are now distinct (URL vs verbal/Discord), so the attacker needs to
compromise both.

**Mitigated.**

### Tampering — *attacker submits a malformed or guessed `code`*

`code` is six chars from a 32-symbol alphabet (`32^6 ≈ 1.07B`). Edge
rate limiting (existing concern, ADR-0018 §"Rate limiting") caps
submission velocity; the keyspace makes random guessing infeasible at
realistic rates. The server validates against the canonical
`lobby.code.value` (no string comparison shortcuts; constant-time
comparison is overkill for a 6-byte string but kept on the table for a
follow-up if telemetry shows targeted abuse).

**Mitigated.**

### Repudiation

Out of scope: there is no auth identity beyond `sessionId`; the lobby
flow trusts the client-issued session. Identity-rebuilding is a
separate workstream.

**No delta.**

### Information disclosure — *attacker reads private lobby state*

The REST snapshot at `GET /v1/lobbies/{lobbyId}` is unchanged. A viewer
who copies the routing URL can read state but cannot join. This is the
single residual risk this ADR explicitly carries; the code-gate
addresses join authority, not read authority. A follow-up ADR may
introduce per-request authorization on REST if state confidentiality
becomes a product requirement.

**Residual.**

### Denial of service

Two new vectors to consider:

1. *Code-enumeration spam*: 32^6 keyspace + edge rate limits make
   brute-force enumeration infeasible. The use case rejects mismatches
   without state mutation; no in-memory cost beyond the lookup-then-
   validate.
2. *`/join/$code` resolution storm*: each share-link click costs one
   `findByCode` lookup (O(n) over the in-memory lobby map per
   ADR-0018 §3). Same edge rate limit applies. Acceptable until
   numbers grow.

**Mitigated.**

### Elevation of privilege

Not applicable. All lobby members have equal capabilities except the
owner, and ownership is established at lobby-creation time and is
unaffected by this change.

**Not applicable.**

## Notes

- The "streamer-mode toggle" alluded to during planning (hide *Copier
  le lien* entirely behind a per-session preference) is **out of scope
  here** and additive on top of this ADR. It does not change the wire
  contract.
- The `Lobby.code` schema field stays `nullable: true` per #261's
  decision; tightening to `required` is deferred and tracked in the
  out-of-scope list of the join-by-code wave.
- The MSW preview mode mirrors the server's code validation so review
  deploys exercise the wire path end-to-end without a real backend.
