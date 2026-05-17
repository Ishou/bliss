# ADR-0047: Token-Endpoint Exchange Threat Model

## Status

Accepted

## Context

ADR-0046 covers threats at the nimbus-jose-jwt layer (algorithm confusion, JWKS unavailability).
ADR-0044 covers redirect-level threats (redirect-URI fixation, session-cookie theft).
Neither document addresses the token-endpoint exchange surface introduced by
`KtorOidcCodeExchanger` (PR #483): the HTTP POST to the provider's token endpoint that
exchanges an authorization code for an OIDC ID token, authenticating the client with either
a `client_secret` (Google) or an Apple `private_key_jwt` client assertion.

Per CLAUDE.md and MANIFESTO.md §473, auth/authz changes require a threat model in the PR body
or a companion ADR before the PR is reviewed. This ADR records that model.

## Decision

### Threat (a): Client-credential at-rest and in-transit

**Threat:** `client_secret` (Google) or `privateKeyPem` (Apple) is exposed if the token
endpoint URL is misconfigured (e.g. attacker-controlled endpoint receives the credential in the
POST body) or if the config object is captured by a log sink or exception serialiser.

**Mitigations:**
- `tokenUrl` is injected at runtime from sealed infrastructure config; misconfiguration produces
  a connection error rather than a credential handoff, unless the attacker controls a reachable
  HTTPS endpoint receiving the POST.
- All traffic to the token endpoint is HTTPS-only; the Ktor `HttpClient` does not follow
  redirects that would downgrade the channel.
- `ClientAuth.Secret` and `ClientAuth.AppleClientAssertion` override `toString()` to redact
  their secret fields (`***`), preventing casual log leakage of credential-carrying config
  objects.

**Residual risk:** `toString()` redaction does not protect against reflection-based serialisers
or distributed-tracing spans that capture the full object graph. Accepted: the config objects
are held in-memory only, never written to a database or cache, and the application does not use
a reflection-based serialiser on domain objects.

### Threat (b): Apple `private_key_jwt` 180-day assertion window

**Threat:** Apple client-assertion JWTs are signed with a long validity window
(`exp = iat + 180 days`). A captured assertion is replayable at Apple's token endpoint for up
to 180 days.

**Why the window is acceptable:**
- Apple's official documentation specifies a maximum validity period of 6 months (≈180 days)
  for client-assertion JWTs; the library uses this maximum because Apple rejects shorter windows
  in some token-endpoint implementations.
- The assertion is transmitted only over HTTPS to Apple's token endpoint. Capture requires a
  MITM on an HTTPS channel or compromise of the application process itself — both out of scope
  for this threat surface.
- The assertion is audience-bound (`aud = https://appleid.apple.com/auth/token`) and
  subject-bound (`sub = clientId`); a captured assertion cannot be replayed against any other
  endpoint or application.
- Apple does not provide a server-side revocation mechanism for client-assertion JWTs; the
  accepted control is private-key rotation via the Apple Developer Portal if key compromise is
  suspected.

**Residual risk:** A compromised assertion remains usable for up to 180 days after key rotation
(the old assertion stays valid until its own expiry). Accepted: key rotation invalidates the
private key, so no new assertions can be minted with the compromised key; existing captured
assertions can only be replayed at Apple's token endpoint for a bounded period.

### Threat (c): `privateKeyPem` stored in `OidcProviderConfig`

**Threat:** The PEM private key lives inside `OidcProviderConfig.clientAuth` for the lifetime
of the request. An exception serialiser, span attribute, or heap dump that captures the config
object could expose the PEM beyond the `toString()` redaction path.

**Mitigations already in place:**
- `ClientAuth.AppleClientAssertion.toString()` returns
  `AppleClientAssertion(teamId=…, keyId=…, privateKeyPem=***)`.
- The config object is not annotated with any serialisation framework; no JSON or protobuf
  encoding of it exists in the codebase.
- `AppleClientAssertionSigner` parses the PEM into an `ECPrivateKey` lazily and holds no
  additional string reference to it after construction.

**Residual risk:** The raw PEM string survives in heap from config construction until GC.
Accepted: the JVM heap is not a shared-memory attack surface in this deployment; heap dumps
require authenticated access to the host. If additional hardening is required in future, the
PEM can be replaced with a pre-loaded `ECPrivateKey` at config construction time so no string
copy persists.

## Consequences

- The three token-endpoint threat surfaces (credential in-transit, Apple assertion replay
  window, PEM in heap) are explicitly documented and their mitigations recorded.
- Future changes to `KtorOidcCodeExchanger` or `AppleClientAssertionSigner` must revisit this
  threat model.
- If HTTPS enforcement or redirect-follow behaviour changes on the `HttpClient`, threat (a)
  must be re-evaluated.
