# ADR-0044: Identity bounded context for player OIDC sign-in

## Status
Accepted

## Context

Player OIDC sign-in is being introduced (Google + Apple) so that
players can persist progress across devices, get hints and clue
cooldowns (ADR-0031), and have identity in multiplayer (ADR-0018).
Anonymous play continues to work for the daily puzzle itself, but
gated features require sign-in.

The repo already has two pieces of "OAuth2" infrastructure that are
unrelated to player auth and must not be confused with it:

- ADR-0030 (`oauth2-proxy`): a session-cookie wrapper for the SigNoz
  admin UI in htpasswd-only mode. No IDP. Gates admin traffic, not
  player traffic.
- ADR-0028 (`admin-htpasswd`, now superseded by ADR-0030 for SigNoz; still referenced by ADR-0032 for Matomo): the htpasswd Secret that backs
  oauth2-proxy and Matomo.

Neither is in the player auth path.

The natural home for player auth is unclear:

- It does not belong in `grid/` (puzzle generation has no business
  knowing about users; bloating it with auth concerns mixes layers).
- It does not belong in `game/` either (lobby code shouldn't own
  account management).
- Cross-context imports are forbidden by ADR-0001; communication
  between contexts happens via merged schemas or domain events.

CLAUDE.md / ADR-0001 §7 requires an ADR before adding a top-level
bounded context. This is that ADR.

### Options considered

| Option | Pros | Cons |
|---|---|---|
| **New `identity/` top-level context** | Cleanest separation; matches hexagonal pattern; identity is genuinely cross-cutting and not subordinate to grid or game | One more top-level context to operate; needs its own Postgres DB, its own ingress host, its own Helm chart |
| Live inside `game/` | Reuses existing chart and DB | Awkward: `grid/` would import `game/api/openapi.yaml` to know who a user is; `grid/` does not naturally depend on `game/` |
| Live inside `grid/` | grid/ already has the per-session persistence pattern | Bloats `grid/` with auth concerns; same hexagonal layer pollution we've explicitly avoided |
| Edge auth only (extend ADR-0030) | Cheapest to ship | No central place for display name, account linking, or RGPD deletion; can't evolve to multi-provider |

### Why the new context

Identity is a cross-cutting concern that does not belong inside
`grid/` or `game/`. Both contexts need to know "who is this player"
without owning the user record. The operational cost of one more
chart is fixed and small; the architectural cost of mixing identity
into either feature context compounds with every new feature.

## Decision

Add a top-level bounded context `identity/` peer to `grid/` and
`game/`. Standard hexagonal layout: `domain/`, `application/`,
`infrastructure/`, `api/`. Owns:

- The user record (`(provider, subject)` keyed).
- The OIDC client integration for Google and Apple.
- The server-side session record and the `__Host-ws_session` cookie.
- Provider linking (a user may link multiple providers).

Schema-first per ADR-0003: `identity/api/openapi.yaml` is the contract
that `grid/api`, `game/api`, and the frontend consume.

Ingress host: `auth.wordsparrow.io`. The `__Host-ws_session` cookie
carries no `Domain` attribute (required by the `__Host-` prefix) and
is therefore bound to `auth.wordsparrow.io` only. `grid/api` and
`game/api` verify sessions by calling `identity-api`; the
30-second cookie-verify cache documented in Consequences mitigates
request-amplification.

OIDC scopes: `openid` only on both Google and Apple. The "no email,
no name" data-minimization stance will be documented in
ADR-0045 (companion ADR, to be merged before the schema PR).

## Threat Model

The following threats were considered for the OIDC sign-in surface.

**Session cookie theft** — `__Host-ws_session` is set `Secure; HttpOnly;
SameSite=Lax` with the `__Host-` prefix, which additionally enforces
`Path=/` and no `Domain` attribute. An attacker on another subdomain
cannot receive the cookie; JavaScript cannot read it. `SameSite=Lax`
blocks cross-site POST-based CSRF while allowing top-level navigations
needed for the OIDC redirect.

**OIDC redirect-URI fixation / open redirector** — redirect URIs are
registered statically with each IDP (Google Cloud Console / Apple
Developer). The identity-api validates the `redirect_uri` parameter
against its allowlist before initiating the flow; mismatches are
rejected with 400. No dynamic redirect construction is performed.

**ID-token replay** — before issuing a session the identity-api
validates `aud` (must match the registered client ID), `iss` (must
match the IDP's well-known issuer), token expiry, and `nonce` (tied to
the authorization request stored server-side). A replayed token with a
previously consumed nonce is rejected.

**Account-linking abuse** — linking an additional provider to an
existing account requires an already-authenticated session. An
unauthenticated request to the link endpoint is rejected. The link is
recorded as `(existing_user_id, provider, subject)`, so an attacker
cannot redirect a link flow to a victim's account without first
compromising the victim's session.

**Account enumeration** — the sign-in response is uniform regardless
of whether the `(provider, subject)` pair is new or known: a session
cookie is always issued and the account is created on first sight.
There is no distinct "account not found" path that could leak
existence.

**RGPD Article 17 (right to erasure)** — the only PII retained is the
opaque `(provider, subject)` pair; no email address or display name is
stored (per data-minimisation stance, ADR-0045). Deletion scope is
bounded to that key plus all associated session records. The
`identity/` context is the sole owner, so there is no cross-context
PII fan-out to coordinate.

## Consequences

**Easier:**

- `grid/` and `game/` consume identity via generated clients; neither
  owns user state. The Konsist arch tests are unchanged.
- Account deletion (RGPD Article 17) has one obvious owner.
- Provider linking and future merge/unlink flows live in one place.
- Future ADRs (Apple Sign-In on native iOS, account merge across
  providers) layer on top of this context without disturbing `grid/`
  or `game/`.

**Harder:**

- One more Helm chart, one more Postgres CNPG cluster, one more
  ingress host with cert-manager.
- The cross-language API surface grows by one OpenAPI document.
- An end-to-end flow now traverses three contexts (frontend →
  identity-api → grid-api). The 30-second cookie-verify cache in
  `grid/api` and `game/api` mitigates request-amplification.
