# ADR-0034: CORS — wildcard `Access-Control-Allow-Headers` for app + game APIs

## Status

Accepted

## Context

Three production incidents in two months have all matched the same
shape: a contributor adds a new outbound HTTP header on the frontend
side (or enables a middleware that does), the backend's Ktor CORS
plugin doesn't list it in `allowHeader`, every cross-origin call from
`https://wordsparrow.io` fails the browser's preflight with no
`Access-Control-Allow-Headers`, and the user-visible symptom is
"Failed to fetch" with no backend log.

The pattern is documented in `feedback_cors_recurring_tax.md` (the
auto-memory note). Each incident:

1. **DELETE method missing** when GDPR erasure shipped (PR #259).
2. **`X-Request-Id` missing** when correlation IDs were wired (PR #267).
3. **`traceparent` / `tracestate` missing** when the OTel browser
   SDK shipped (PR-F.2, surfaced 2026-05-10): hint button broken,
   word validation broken, multiplayer create broken. PR #307 added
   the explicit allow-list entries as an immediate fix.

Every incident was caught by users, not CI. The unit tests in
`CorsTest` pass because Ktor's `testApplication` HTTP client doesn't
enforce CORS the way a real browser does — preflights succeed in
tests even when they'd fail in Chrome.

## Decision

**Replace the explicit `allowHeader(...)` allowlist with a wildcard
predicate (`allowHeaders { true }`) on both grid-api and game-api.**

Supersedes PR #307's explicit allow-list approach (which was the
correct immediate fix for the live regression but doesn't prevent the
class).

The CORS plugin keeps its other constraints — `allowMethod` stays
explicit (small, stable set), `allowHost` stays narrow (apex, www,
local Vite), `allowCredentials = false` — because methods and origins
have not been the recurring-tax surface. Only the header allowlist
flips to wildcard.

## Why this is OK for these APIs

`allowHeader` is a defense-in-depth layer, not a security boundary.
The actual security properties of these APIs are:

- **Read-mostly, no auth.** No cookie / token / credential is sent on
  any request (`allowCredentials = false`). An attacker probing custom
  headers gains nothing because every endpoint is already public.
- **Origin allowlist remains.** Cross-origin requests from non-
  `wordsparrow.io` pages still fail the preflight on `Origin` — the
  surface most CORS attacks actually target.
- **Per-IP rate limit at ingress remains** (nginx-ingress
  `limit-rps`). DoS via header-spam is bounded at the edge.
- **Backends ignore unknown headers.** Ktor's request pipeline doesn't
  route on or react to unrecognised header names; an attacker setting
  `X-Foo: bar` against `/v1/puzzles/...` is wasting bytes.

What we're losing: the (small) ability to assert in `CorsTest` that
every header the frontend ships matches a documented set on the
backend. That's exactly the assertion that didn't catch any of the
three incidents above — because the frontend headers came from
middleware that wasn't visible to the backend anyway.

## Why not the alternatives

**A. OpenAPI as source of truth + an `x-cors-transport-headers`
extension that backends parse.** The cleanest "single source"
architecture, but doesn't solve the problem: a contributor adding a
new transport header still has to remember to update the extension.
Same human-memory tax as `allowHeader`, just in a different file.
The `feedback_cors_recurring_tax.md` memory already existed and was
forgotten three times; renaming the registry doesn't make humans
remember to update it.

**B. Real-browser e2e (Playwright) on a cross-origin deploy.** Catches
the regression deterministically — the browser is the only authority
that can fail a preflight the way Chrome will fail it in production.
Tracked as a follow-up; orthogonal to this ADR. We adopt the
wildcard *and* eventually wire e2e on top.

**C. nginx-ingress wildcard + leave Ktor alone.** Adding
`nginx.ingress.kubernetes.io/cors-allow-headers: "*"` at the ingress
would handle prod traffic, but local dev (no ingress) keeps the Ktor
allowlist enforcement, so the regression still surfaces in dev /
preview. Wildcarding Ktor is the consistent choice.

**D. Continue to chase the explicit allowlist** (the PR #307 path).
Each incident has been small and easily fixed once spotted. Cumulative
cost over the launch window: hours of debugging across several team-
conversations, plus user-visible breakage. Not a cost we want to keep
paying for a defense-in-depth layer that adds no real protection.

## Trade-offs

**Easier:**

- Adding a new outbound header on the frontend (manually, or via a
  new instrumentation library) doesn't break production.
- The `CorsTest` assertion gets simpler and more honest: "any header
  passes" rather than "this specific list of historically-incident
  headers passes."
- One less place where the codebase relies on out-of-band human
  memory between frontend changes and backend config.

**Harder:**

- A future where these APIs grow auth (cookies / Bearer tokens) needs
  a re-evaluation of the wildcard. With credentials in scope,
  `Access-Control-Allow-Credentials = true` requires a non-wildcard
  `Allow-Origin` and (in many browsers) a non-wildcard
  `Allow-Headers`. When that day comes, this ADR gets superseded by
  one that re-narrows the allowlist with the auth-specific headers
  enumerated.
- Less expressive backend introspection: a curious reader can no
  longer infer "what headers does the frontend send" by reading
  `Module.kt`. They have to read the frontend's middleware /
  instrumentation config instead. Acceptable; that information
  belonged in the frontend anyway.

**Different:**

- The defense-in-depth posture for these APIs now leans entirely on
  origin allowlist + ingress rate limit + read-only public surface.
  When any of those three change (e.g., the API gains a write that
  must be auth-only), the wildcard headers decision must be
  re-examined in the same PR, not as a follow-up.

## Follow-ups

1. Real-browser e2e (Playwright) hitting hint / validate / lobby-create
   from the actual cross-origin host. Catches CORS, CSP, cookie-domain
   issues — anything where unit tests lie. Tracked separately; not
   blocking this ADR.
2. If `wordsparrow.io` ever introduces auth, reopen this decision in
   a new ADR — the wildcard is incompatible with credentialed CORS
   under modern browser rules.

> **Update (2026-05-19): predicate vs literal wildcard.** Auth landed
> on identity-api (PR #514, Phase 5 OAuth2), and the wildcard-vs-
> credentials caveat in Follow-up #2 needs sharpening before someone
> re-reads this ADR and concludes `allowHeaders { true }` is unsafe.
>
> The original caution holds for the **literal** response value
> `Access-Control-Allow-Headers: *`. Browsers reject that string
> whenever `Access-Control-Allow-Credentials: true` is also set — the
> Fetch spec disallows the literal wildcard in credentialed mode.
>
> Ktor's `allowHeaders { true }` is **not** a literal wildcard. It is
> a predicate the CORS plugin evaluates against the preflight's
> `Access-Control-Request-Headers` and **echoes the matching values
> back verbatim**. The wire response on a credentialed preflight is
> `Access-Control-Allow-Headers: x-request-id, traceparent,
> tracestate` (or whatever the client actually asked for) — never the
> literal `*` string. That form is spec-compliant alongside
> `Allow-Credentials: true`, which is why identity-api can use it.
> Reference: `identity/api/src/main/kotlin/com/bliss/identity/api/Module.kt`
> pairs `allowHeaders { true }` with `allowCredentials = true`; PR
> #514 introduced the combination.
>
> Guidance: prefer `allowHeader(...)` (explicit list) when the header
> surface is small and stable — auth-token APIs usually qualify. Use
> `allowHeaders { true }` only when the headers vary by client, which
> is currently the case because the OTel browser SDK (ADR-0033) adds
> `traceparent` / `tracestate` dynamically and our middleware stack
> is the recurring-tax surface this ADR was written to address.
