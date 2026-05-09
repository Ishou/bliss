# ADR-0028: Admin URL auth — ingress-nginx basic auth + htpasswd

## Status

Proposed

## Context

The launch-readiness rollout introduces three admin URLs:

- `errors.wordsparrow.io` and `dashboard.wordsparrow.io` — both alias the
  same SigNoz UI per ADR-0027 (one Service, two Ingress hosts).
- `analytics.wordsparrow.io` — existing Matomo deployment, currently public,
  needs gating before public traffic arrives.

These URLs surface logs, traces, error messages with stack traces, request
URLs, and aggregate analytics — the kind of operational data that an
attacker or an indexed-by-Google leak would treat as a goldmine. None of
them should be reachable from the public internet without authentication.

The MANIFESTO §Security commits the project to:

- "Authentication and authorization changes always get a threat model."
  (`MUST`) — the document you are reading.
- "Every service has its own credentials with minimal permissions." (`MUST`)
- "Permissions are defined in IaC and reviewed in PRs." (`MUST`)

The cluster has no existing identity or secrets-management story:

- No SOPS, no sealed-secrets controller, no External Secrets Operator.
- No OIDC provider, no SSO, no IDP integration.
- Cloudflare is in the mix (DNS + Pages) but `feedback_admin_subdomains_and_auth`
  records the maintainer's explicit decision to keep auth in-cluster, not at
  the Cloudflare edge.
- The maintainer is the sole human user of these URLs.

The selection space is therefore "what's the simplest in-cluster auth
mechanism that protects three read-only ops UIs from public traffic, fits
ingress-nginx, and does not require new platform components."

### Options considered

| Option | New components | Per-user audit | Setup cost | Day-2 ops cost |
|---|---|---|---|---|
| **ingress-nginx basic auth** (`auth-type: basic` + htpasswd Secret) | 0 | No | One bootstrap command | Rotate password once a year |
| OAuth2-Proxy sidecar | OAuth2-Proxy + IDP integration (Google OIDC) | Yes (per-email) | Register OAuth client, configure proxy, plumb through ingress | Maintain proxy version, IDP mapping |
| Authelia / Authentik (in-cluster IDP) | Authelia DB + UI + Redis | Yes | Stand up entire IDP, define users | Operate an IDP for 1 user |
| mTLS with client certs | cert-manager already in cluster, but client cert distribution is manual | Yes (per-cert) | Issue cert, install in browser per device | Re-issue per device, no easy revoke for browsers |
| Cloudflare Access | 0 in-cluster | Yes | Configure CF Access app + policy | None on cluster, edge-managed |

### Why htpasswd over the close alternatives

- **vs. Cloudflare Access:** the maintainer's recorded preference is in-cluster
  auth (`feedback_admin_subdomains_and_auth`). CF Access is the cleanest
  path operationally but couples auth to a third-party gate. Documented
  here so a future operator knows the trade-off was made consciously.
- **vs. OAuth2-Proxy:** delivers per-user identity that is unnecessary today
  (one user) and adds two new things to operate (the proxy, the OIDC
  integration). Reconsider if/when there are 3+ admin users with different
  scopes.
- **vs. Authelia:** strictly worse than OAuth2-Proxy for one user — same
  capability, more components.
- **vs. mTLS:** cert distribution UX on browsers (especially mobile, where
  the maintainer occasionally checks dashboards) is hostile. Acceptable
  trade-off for very high-value endpoints; overkill for read-only ops.

The main argument **against** htpasswd — no per-user audit trail — is
moot in a one-user environment. It becomes a real argument the moment a
second admin appears, at which point this ADR gets superseded.

### Why this stays manifesto-aligned

- "Permissions defined in IaC and reviewed in PRs": the ingress
  annotations are committed in Helm values and go through PR review. The
  Secret manifest itself is the only piece that does not live in the
  repo (because the rendered htpasswd hash is sensitive — see secret
  storage below).
- "Every service has its own credentials": each htpasswd Secret is
  scoped to its ingress namespace and only consumed by ingress-nginx for
  that hostname. Not shared with grid/api, game/api, or worker.
- "Threat modeling for auth changes": this section.

## Decision

### 1. Auth mechanism

All three admin Ingresses (`errors`, `dashboard`, `analytics` under
`wordsparrow.io`) gain three nginx-ingress annotations:

```yaml
nginx.ingress.kubernetes.io/auth-type: basic
nginx.ingress.kubernetes.io/auth-secret: admin-htpasswd
nginx.ingress.kubernetes.io/auth-realm: "WordSparrow ops"
```

The `admin-htpasswd` Secret holds an `auth` key with bcrypt-hashed
htpasswd lines. Username is fixed to `admin`. Password is a 32-character
URL-safe random string.

### 2. Secret bootstrap

A new script at `infra/observability/scripts/bootstrap-admin-htpasswd.sh`
performs the following on a fresh cluster (or rotation):

1. Generate a 32-character password from `/dev/urandom`
   (`openssl rand -base64 24` or equivalent).
2. Compute the bcrypt htpasswd line via `htpasswd -B -nb admin <pwd>`.
3. Create the k8s Secret in the `observability` namespace via
   `kubectl create secret generic admin-htpasswd --from-literal=auth=<line>`
   (or apply if it already exists).
4. Print the **plaintext password** to stdout, exactly once, with a clear
   instruction for the operator: "Copy the line above into your password
   manager. It will not be shown again."

The script does not write the plaintext to disk. The Secret in the cluster
holds only the bcrypt hash. The plaintext lives only in the maintainer's
password manager.

The same Secret is referenced by Matomo's existing Ingress (PR-G in the
rollout sequence) so all three admin URLs share one credential.

### 3. Reproducibility from cold cluster

If the cluster is destroyed and recreated, `bootstrap-admin-htpasswd.sh`
is re-run as part of the cluster-bring-up runbook. The new password is
saved to the password manager; the old entry is archived. The Helm charts
themselves are unchanged — they reference the Secret by name only.

This is **not full GitOps purity**: the auth credential is the one piece
of system state that is not declaratively reproducible from the repo. The
trade-off is documented and accepted because:

- Adding sealed-secrets or SOPS would solve this but adds a platform
  component this project does not need yet.
- The bootstrap script is itself in code (committed, reviewable).
- Cluster recreation is a rare event (none scheduled).

A follow-up ADR will revisit this when sealed-secrets becomes the right
addition (e.g. when there are multiple Secrets that need this treatment).

### 4. Threat model (STRIDE-lite)

| Category | Threat | Severity | Mitigation |
|---|---|---|---|
| **Spoofing** | Attacker guesses or phishes the shared password | Medium | 32-char random password (≥160 bits entropy); rotated annually via the same bootstrap script; never emailed, only in password manager. |
| **Tampering** | Man-in-the-middle modifies traffic between operator and dashboard | Low | TLS via cert-manager + Let's Encrypt is already mandatory in `infra/platform/`; ingress-nginx serves only HTTPS. |
| **Repudiation** | "Who logged in at 03:00?" | Acceptable | No per-user audit trail. Acceptable in a single-user environment; reconsider when a second admin joins. |
| **Information Disclosure** | Attacker with the password sees error messages, request URLs, traces — which can include PII | High → mitigated to Medium | PII scrubbing happens at the OTel collector layer (ADR-0027 §8): drop `?email=` / `?token=` / `?session=` query params; redact `Authorization` and `Cookie` request headers; no request bodies captured. Matomo is already cookieless and IP-anonymised (ADR-0025). |
| **Denial of Service** | Brute force the password endpoint | Low | Existing per-IP rate limit on the ingress (10 RPS, 5× burst — see `grid/api/deploy/chart/values-prod.yaml:25`) extends to admin Ingresses. With 160-bit entropy and rate-limiting at 10 RPS, brute force is not feasible. No fail2ban-style auto-block today; tracked as a future hardening item. |
| **Elevation of Privilege** | Attacker leverages dashboard access to write back to the cluster | None | All three URLs are read-only observability surfaces. SigNoz alerts can trigger SMTP send, but the SMTP secret is held by SigNoz itself, not exposed via the UI. No API tokens with cluster write permission are visible from these UIs. |

**Net residual risk:** if the password leaks, an attacker reads
operational data (potentially containing PII despite scrubbing) until
the leak is detected and the password rotated. The scrubbing layer
keeps that exposure inside known-bounded categories.

### 5. Username and password rotation

- Username: hard-coded to `admin`. Not a secret — it's in this ADR.
- Password: 32 characters, generated at bootstrap. Rotation policy:
  annually, or immediately after suspected compromise, or when offboarding
  a new admin who was given access (none today).
- Rotation procedure: re-run `bootstrap-admin-htpasswd.sh`. The script
  detects an existing Secret and replaces it. Operator updates password
  manager.

## Consequences

**Positive:**

- Zero new platform components. Ships alongside SigNoz/Matomo charts.
- One credential gates all three admin URLs.
- Reusable for future admin URLs (e.g. if sealed-secrets ever lands its
  own UI, same Secret reference).
- Bootstrap is one command, rerunnable for rotation.

**Negative / trade-offs:**

- One bootstrap step that is not pure-GitOps. Tracked above.
- No per-user audit trail. Acceptable today; this ADR gets superseded
  when that stops being acceptable.
- htpasswd Secret rotation requires editing one Secret and bouncing
  ingress-nginx to pick up new auth files (ingress-nginx caches secrets;
  worst case, restart the controller pod).

**Future ADRs unblocked:**

- A future ADR on sealed-secrets or SOPS adoption when the cluster
  accumulates more such bootstrap-secret patterns.
- A future ADR superseding this one when admin user count grows past 1
  and per-user identity becomes a real requirement.
