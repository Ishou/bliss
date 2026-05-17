# ADR-0046: Adopt nimbus-jose-jwt for OIDC token handling in identity/

## Status
Accepted

## Context

ADR-0044 establishes the `identity/` bounded context for player OIDC
sign-in (Google + Apple). Phase 3 of the implementation requires two
infrastructure adapters:

- **`JoseOidcVerifier`** — verifies ID tokens returned by Google and
  Apple (validates `iss`, `aud`, `exp`, `nonce`, and signature against
  the IDP's published JWKS).
- **`AppleClientAssertionSigner`** — produces a signed JWT for the
  Apple token endpoint (Apple's OIDC flow requires the client to
  authenticate with a client assertion signed with a private key under
  `Sign in with Apple`, not a static client secret).

Both operations require JWT parsing, JWS signature verification, and
JWK-set handling. Options considered:

| Option | Notes |
|---|---|
| **`com.nimbusds:nimbus-jose-jwt`** | De-facto standard on the JVM for JOSE/JWT; covers JWS, JWE, JWK sets, OIDC ID-token validation; well-maintained; no hand-rolled crypto. |
| Roll our own on top of `java.security` | Viable for simple HMAC but error-prone for RSA/EC key rotation and JWKS endpoint caching; not advisable for security-sensitive code. |
| `auth0/java-jwt` | Narrower scope — JWT only, no JWK-set client; would need a companion library for JWKS; two dependencies for one concern. |
| Spring Security OAuth2 | Heavyweight; `identity/` is a plain Ktor service, not a Spring application. Wrong fit. |

nimbus-jose-jwt covers both adapter needs with a single dependency,
has no known CVEs in 9.x, and is already present in the JVM ecosystem
via many OIDC libraries. Introducing it does not conflict with the
ADR-0001 rule against vendor SDK imports in `domain/` or
`application/` — the adapters that use it live in `infrastructure/`.

## Decision

Add `com.nimbusds:nimbus-jose-jwt:9.40` as a dependency of
`identity:infrastructure`. Use it exclusively in:

1. `JoseOidcVerifier` (port: `OidcVerifier`) — verifies Google and
   Apple ID tokens against their JWKS endpoints.
2. `AppleClientAssertionSigner` (port: `ClientAssertionSigner`) —
   signs the Apple client assertion JWT with the private key stored
   as a k8s Secret.

No other module imports nimbus-jose-jwt. The `domain/` and
`application/` layers remain dependency-free; only the port interfaces
cross the layer boundary.

## Threat Model

The following threats are specific to the nimbus-jose-jwt implementation
layer. Protocol-level threats (session theft, token replay, redirect
fixation, account-linking abuse) are covered in ADR-0044's threat model
and are not duplicated here.

**Algorithm confusion (alg:none / HMAC substitution)** — nimbus-jose-jwt
accepts any algorithm by default if the verifier is not constrained.
An attacker who can supply a crafted token with `"alg": "none"` or swap
an asymmetric algorithm for HMAC could forge signatures against naive
configurations. Mitigation: `JoseOidcVerifier` pins the accepted
algorithm per IDP at construction time — RS256 for Google (RSA + SHA-256
from Google's JWKS) and ES256 for Apple (EC + SHA-256 from Apple's JWKS)
— and passes only the pinned `JWSAlgorithm` to the verifier. Tokens
whose header carries any other algorithm are rejected before the
signature is checked. The same pinning applies to
`AppleClientAssertionSigner`, which signs exclusively with ES256.

**JWKS endpoint unavailability (fail-open risk)** — if the JWKS endpoint
is unreachable or returns an unexpected payload, the verifier must not
fall back to accepting unsigned or previously cached tokens beyond the
configured TTL.
Mitigation: `JoseOidcVerifier` delegates JWKS fetching to `JwksCache` —
a `ConcurrentHashMap`-backed TTL cache with an injectable fetch lambda.
On each verification call, `JwksCache` returns the cached `JWKSet` if
it is within TTL; on expiry or cold start the lambda re-fetches. When
the endpoint is unreachable and the cache is empty or expired, the fetch
exception propagates and `JoseOidcVerifier` maps it to
`OidcVerificationError.JwksUnavailable` — the caller treats any
non-success as unauthenticated. This is fail-closed: a JWKS outage
degrades availability, not security.

**`DefaultJWTClaimsVerifier` misconfiguration (widened claim set)** —
nimbus-jose-jwt's `DefaultJWTClaimsVerifier` accepts a map of exact
claims to match; omitting a claim from that map means it is not
validated. An incorrectly configured verifier could accept tokens from
unexpected issuers or with a mismatched audience. Mitigation: the
implementation PR must pass all four required claims explicitly:
`iss` (IDP issuer URL), `aud` (registered client ID), and `exp`
(expiry, validated automatically) are exact-match; `nonce` is verified
against the server-side value stored during the authorization request
(see ADR-0044). No optional or additional claims are accepted as
substitutes. This is enforced via the `DefaultJWTClaimsVerifier`
constructor overload that takes both a required-claim set and an
exact-match map, leaving the prohibited-claim set empty (no claims
are silently ignored).

## Consequences

**Easier:**

- ID-token verification and client-assertion signing are implemented
  with audited, well-tested primitives rather than hand-rolled crypto.
- JWKS key material is cached in-memory by `JwksCache` — a
  `ConcurrentHashMap`-backed TTL cache with an injectable fetch lambda.
  The library's `RemoteJWKSet` was not used because the injectable-lambda
  design is required for deterministic unit tests. Key rotation is handled
  naturally: once the TTL elapses the lambda re-fetches fresh key material
  from the IDP.
- One dependency covers both Phase 3f (`JoseOidcVerifier`) and Phase
  3g (`AppleClientAssertionSigner`).

**Harder:**

- One additional JVM dependency to track for CVEs and version bumps
  (Renovate handles this automatically).
- nimbus-jose-jwt's API surface is large; `JoseOidcVerifier` must use
  the `DefaultJWTClaimsVerifier` narrowly to avoid accidentally
  widening the accepted claim set.
