# ADR-0048: CORS wildcard predicate for identity-api with credentialed requests

## Status

Accepted

## Context

ADR-0034 established `allowHeaders { true }` (Ktor's wildcard predicate) for
grid-api and game-api, and included an explicit follow-up:

> "If `wordsparrow.io` ever introduces auth, reopen this decision in a new ADR
> — the wildcard is incompatible with credentialed CORS under modern browser
> rules."

Identity-api is that auth service. It became production-live in 2026-05 and
within hours produced two CORS incidents of the same shape ADR-0034 catalogued
for grid/game:

1. **PR #513** — `X-Request-Id` missing from the identity-api allow-list.
2. **This ADR's trigger** — `traceparent` / `tracestate` missing (OTel browser
   SDK, ADR-0027 / ADR-0033).

Both were caught by users in under an hour. The identity-api's CORS config at
that point used the same explicit `allowHeader(...)` approach that ADR-0034
replaced on grid/game, meaning the same recurring-tax problem repeated
immediately.

The key difference between identity-api and grid/game: identity-api sets
`allowCredentials = true` because session cookies ride on every authenticated
request. Grid-api and game-api use `allowCredentials = false`.

### Why the ADR-0034 warning applies to `*`, not to Ktor's predicate

The browser restriction that ADR-0034 called out is:

> `Access-Control-Allow-Headers: *` is forbidden when the response also
> carries `Access-Control-Allow-Credentials: true`.

This restriction is specific to a **literal `*` string** in the response
header. Ktor's `allowHeaders { true }` does not emit `*`. Instead, the CORS
plugin reads `Access-Control-Request-Headers` from the preflight and echoes
back the same header names, verbatim. A preflight for
`x-request-id,traceparent,tracestate` receives:

```
Access-Control-Allow-Headers: x-request-id,traceparent,tracestate
Access-Control-Allow-Credentials: true
```

Each header is named explicitly. Browsers accept this combination; the
`*`-with-credentials restriction is not triggered. The CORS spec's prohibition
is on the value `"*"`, not on "allowing many headers."

## Decision

Apply `allowHeaders { true }` to identity-api, matching the decision already
in force for grid-api and game-api (ADR-0034), despite `allowCredentials = true`
on identity-api.

The security perimeter for credentialed-CORS requests on identity-api rests on:

1. **Strict origin allowlist** — only `wordsparrow.io`, `www.wordsparrow.io`,
   `bliss-cb4.pages.dev`, and `localhost:5173` are allowed. Cross-origin
   requests from any other origin fail at the Origin check before headers are
   evaluated.
2. **Per-IP rate limit at ingress** — nginx-ingress `limit-rps` bounds DoS via
   header-spam at the edge, same as grid/game.
3. **Predicate echoing** — the emitted `Access-Control-Allow-Headers` value is
   always a closed set derived from the incoming preflight, not an open
   invitation. An attacker sending unusual headers gains no cross-origin access
   they didn't already have via the public surface.

The `allowHeader` allowlist does not defend against anything meaningful on
identity-api: an attacker who can reach the API from an allowed origin already
has cookie-level access to every authenticated endpoint. Header enumeration adds
no security, but does cause production incidents.

## Why not the alternatives

**A. Keep the explicit allowlist, chase additions reactively.**
This is exactly the approach that produced two incidents in the first hour of
identity-api going live. ADR-0034 already closed this argument for grid/game.
The recurring tax is real; it applies equally here.

**B. Narrow list to the known set (X-Request-Id, traceparent, tracestate).**
A new instrumentation library (OTel feature, a future auth header, a new
correlation scheme) would break without a backend change. Same problem,
smaller list.

**C. Proxy the CORS handling to nginx-ingress, leave Ktor unconfigured.**
Local dev has no ingress; Ktor enforces CORS in tests and on the dev cluster.
Inconsistency between dev and prod has historically hidden regressions.
Wildcarding Ktor is the consistent choice.

## Consequences

**Easier:**

- A frontend instrumentation library can attach new headers without requiring a
  backend change or a production incident.
- `CorsTest` asserts "the predicate echoes every header in the preflight",
  which is honest and regression-safe.

**Harder / Different:**

- Identity-api's `allowCredentials = true` combined with a wildcard predicate
  requires this rationale to be documented (this ADR) so future readers
  understand why the combination is safe.
- If identity-api ever moves to a different CORS framework, the new
  implementation must be verified to behave as a predicate (echo) and not emit
  a literal `*` in `Access-Control-Allow-Headers`.

## Supersedes

ADR-0034 Follow-up #2 (the "reopen when auth comes" clause). ADR-0034 itself
remains in force for grid-api and game-api.
