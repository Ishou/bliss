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

## Consequences

**Easier:**

- ID-token verification and client-assertion signing are implemented
  with audited, well-tested primitives rather than hand-rolled crypto.
- JWKS key rotation is handled by the library's remote JWK-set client
  with automatic refresh — no bespoke caching code.
- One dependency covers both Phase 3f (`JoseOidcVerifier`) and Phase
  3g (`AppleClientAssertionSigner`).

**Harder:**

- One additional JVM dependency to track for CVEs and version bumps
  (Renovate handles this automatically).
- nimbus-jose-jwt's API surface is large; `JoseOidcVerifier` must use
  the `DefaultJWTClaimsVerifier` narrowly to avoid accidentally
  widening the accepted claim set.
