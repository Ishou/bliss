# ADR-0045: Player identity data minimization

## Status
Accepted

## Context

ADR-0044 introduces the `identity/` bounded context for player OIDC
sign-in via Google and Apple. RGPD posture on the rest of the product
is set by ADR-0025 (Matomo, cookieless, IP-anonymised). Adding
identifiable accounts is the largest privacy delta the product has
taken on; this ADR pins the data minimization stance so a future
contributor cannot quietly revert it by "just requesting the email."

## Decision

### OAuth scopes

- **Google:** `openid` only. **Not** `email`, **not** `profile`.
- **Apple:** `openid` only. **Not** `name`, **not** `email`.

Both providers return a subject identifier (`sub`) under `openid`
alone. The `sub` is everything we need to map a returning sign-in to
the same user.

### Persisted columns

| Data | Stored? | Rationale |
|---|---|---|
| `users.id` (UUIDv7) | Yes | Server-issued. |
| `users.display_name` | Yes — player-chosen, max 30 chars | Not pulled from any IdP. Not unique (no enumeration vector). |
| `user_providers.provider`, `.subject` | Yes | Required to resolve a returning OIDC sign-in. |
| `user_providers.email_at_link` | **Optional** — populated only on explicit player opt-in at a one-shot post-sign-in consent screen ("Let WordSparrow keep your email for account recovery?"). Defaults NULL. | Player choice; never required. The email is entered by the player at the consent screen — not fetched from the IdP; no `email` OAuth scope is requested. |
| Real name from IdP | Never | Not requested. |
| Profile picture | Never | Not requested. |
| Client IP | Never logged in application code (`identity-api`). Collector-layer IP masking (last 2 octets IPv4 / last 80 bits IPv6, mirroring ADR-0025) is pending the Phase 4 OTel collector-config patch. Until that patch lands, raw IPs traverse the collector→SigNoz path; **`identity-api` must not go to production before Phase 4 is deployed.** | Operator does not need raw client IPs; ingress-nginx rate-limits already cover abuse (ADR-0028 §4, superseded by ADR-0030; rate-limiting behaviour unchanged). |

### Cookie

`__Secure-ws_session` (renamed from `__Host-ws_session` per ADR-0044
amendment 2026-05-18) is strictly necessary for authentication and is
therefore not subject to the RGPD cookie-banner regime. ADR-0025's
cookieless analytics posture is preserved — `identity-api` adds no
tracking cookies.

### Erasure

- `DELETE /v1/users/me` (self-service) performs a hard delete of the
  `users` row; cascading deletes of `user_providers` and `sessions`;
  publishes a `UserDeleted` event to which `grid/api` and `game/api`
  react by deleting their `WHERE user_id = ?` rows.
- Operator-initiated RGPD Article 17 follows the same path,
  triggered manually.
- The `users` table has no `deleted_at` column. A returning OIDC
  sign-in after a deletion creates a fresh `users.id` — the deleted
  account is forgotten.

### Why this is load-bearing

Future contributors will be tempted to request `email` or `profile`
scope ("just to make password recovery easier"). That request would
require this ADR to be superseded. The supersession discussion is the
forcing function that keeps minimization honest.

## Consequences

**Easier:**

- The privacy notice has a short and accurate "what we store" list.
- RGPD Article 17 erasure is a row-level delete, not a hashed-email
  audit trail traversal.
- Compatible with Apple's "Hide my email" by way of irrelevance —
  we never ask for the email in the first place.

**Harder:**

- No email-based password recovery. If a player loses access to
  every linked IdP, the account is irrecoverable. Acceptable trade-off
  for v1; revisit if/when "account recovery" becomes a real support
  load.
- Anti-abuse signals (e.g. block a known-bad email) are unavailable.
- Some operator dashboards will not show "which player is this" — by
  design.
