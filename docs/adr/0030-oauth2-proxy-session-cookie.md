# ADR-0030: oauth2-proxy session-cookie wrapper for SigNoz UI auth

## Status

Accepted — supersedes ADR-0028

## Context

ADR-0028 chose ingress-nginx basic auth (htpasswd) over oauth2-proxy.
The recorded rejection rationale was:

> *"vs. OAuth2-Proxy: delivers per-user identity that is unnecessary today
> (one user) and adds two new things to operate (the proxy, the OIDC
> integration). Reconsider if/when there are 3+ admin users with different
> scopes."*

That decision was correct for a static HTML/API backend. SigNoz's frontend
is a React SPA: after the initial page load, every XHR call and every
WebSocket frame is initiated by JavaScript. Browsers do **not**
automatically attach an `Authorization: Basic …` header to
`XMLHttpRequest` or `fetch` calls — that header only travels with the
initial browser-issued navigation request. The result is that every API
call triggered a browser credential dialog, making the SigNoz UI
effectively unusable in a browser.

The fix must:

1. Issue a session cookie on first login so subsequent XHR and WS calls
   carry credentials automatically (browsers always send cookies).
2. Keep the single credential source — the `admin-htpasswd` Secret
   bootstrapped by `scripts/bootstrap-admin-htpasswd.sh` (ADR-0028 §2).
3. Not add an OIDC provider or any external identity dependency.

ADR-0028's rejection of oauth2-proxy cited **two** new things to operate:
the proxy **and** the OIDC integration. This ADR accepts only the proxy,
not the OIDC integration.

### Options reconsidered

| Option | Addresses XHR/WS prompt spam | New components | Operator cost |
|---|---|---|---|
| **oauth2-proxy htpasswd-only** | Yes — issues a session cookie | 1 Deployment + 1 Service | Pin + upgrade image tag; no IDP |
| Nginx auth_request with Lua cookie layer | Yes — but requires OpenResty or nginx-lua module not available in stock ingress-nginx | ingress-nginx recompile | Not feasible without image change |
| Cloudflare Access | Yes — edge-issued JWT cookie | 0 in-cluster | CF account, rule configuration; couples auth to third-party edge |
| Authelia | Yes | Authelia + DB + Redis | Operate a full IDP for 1 user; strictly worse than oauth2-proxy |

oauth2-proxy in standalone htpasswd mode is the minimum addition that
closes the browser-prompt problem without a new identity provider.

## Decision

Deploy oauth2-proxy alongside the SigNoz chart in
**standalone htpasswd mode**. No OIDC, no IDP, no external OAuth client
registration.

### Configuration summary

- `--provider=keycloak-oidc` with `--skip-oidc-discovery=true` is used as
  a nominal provider flag (oauth2-proxy requires a provider value); the
  provider endpoint is never contacted because `--htpasswd-file` is the
  sole credential source.
- `--htpasswd-file` mounts the existing `admin-htpasswd` Secret (the same
  Secret ADR-0028 authorised).
- `--upstream=static://200` — oauth2-proxy is used in auth-only mode via
  nginx `auth-url`; it does not proxy traffic to SigNoz.
- Cookie scoped to `.wordsparrow.io` (7-day lifetime, 24-hour refresh),
  `HttpOnly`, `Secure`, `SameSite=Lax`.
- A chart-managed `oauth2-proxy-cookie` Secret holds the HMAC signing key,
  auto-generated on first install and preserved across upgrades via Helm's
  `lookup()`.

### ingress-nginx wiring

Both SigNoz hosts (`errors.wordsparrow.io`, `dashboard.wordsparrow.io`) use
a **two-Ingress-per-host** layout:

**Ingress 1 — `/oauth2/*` → oauth2-proxy, no `auth-url` applied.**
The `/oauth2` paths are exposed on a separate Ingress with no `auth-url`
annotation. Placing `/oauth2` behind `auth-url` creates a redirect loop:
`auth-signin` redirects to `/oauth2/sign_in`, which is itself validated by
`auth-url`, which redirects again (nginx detects the cycle and 500s).
Two Ingresses share the same TLS Secret; cert-manager is idempotent on the
`secretName` so only one Certificate object is issued per host.

**Ingress 2 — `/` → SigNoz frontend, behind oauth2-proxy.**
The main Ingress replaces the basic-auth annotations with:

```yaml
nginx.ingress.kubernetes.io/auth-url: "http://oauth2-proxy.<ns>.svc.cluster.local:4180/oauth2/auth"
nginx.ingress.kubernetes.io/auth-signin: "https://<host>/oauth2/sign_in?rd=$escaped_request_uri"
nginx.ingress.kubernetes.io/auth-response-headers: "X-Auth-Request-User,X-Auth-Request-Email,X-Auth-Request-Preferred-Username"
```

The split ensures the cookie is issued on the correct domain (`.wordsparrow.io`)
while avoiding the nginx redirect-loop that occurs when the auth provider
endpoint is itself protected by the auth check.

### Threat model delta (STRIDE-lite vs ADR-0028)

| Category | Delta |
|---|---|
| **Spoofing** | Session cookie introduces a second credential surface. Mitigated: cookie is HMAC-signed, `HttpOnly`, `Secure`, and 7-day expiry. Rotating the htpasswd via `bootstrap-admin-htpasswd.sh` invalidates existing sessions on next cookie refresh. |
| **Repudiation** | oauth2-proxy now emits structured auth events (log line per login). No change in audit depth vs ADR-0028 (still one user). |
| **Information Disclosure** | Cookie is not readable by JavaScript (`HttpOnly`). Cookie signing key is cluster-local. No new external call paths added. |
| **Elevation of Privilege** | oauth2-proxy's `--upstream=static://200` means a compromised proxy pod cannot re-route traffic; it only returns 200/401. |

Net residual risk is **lower** than ADR-0028's basic-auth configuration
because session-cookie theft requires TLS interception or browser compromise
(both higher-bar attacks than XHR credential-prompt phishing).

### Operator cost (new items only)

| Item | Cost |
|---|---|
| Deployment: 1 replica, 20 m CPU / 32 Mi RAM (requests) | Negligible on existing node |
| Image: `quay.io/oauth2-proxy/oauth2-proxy:v7.6.0` | Pin in `values.yaml`; renovate/dependabot picks up new tags |
| `oauth2-proxy-cookie` Secret | Chart-managed; no manual bootstrap step |
| Session invalidation on password rotation | Already handled: existing `bootstrap-admin-htpasswd.sh` rotates the htpasswd Secret; next cookie refresh forces re-login |

## Consequences

**Positive:**

- SigNoz SPA becomes usable in a browser without credential re-prompts on
  XHR and WebSocket calls.
- Single login per week (7-day cookie), matching the mental model of a
  dashboard accessed during on-call.
- Credential source unchanged: `admin-htpasswd` Secret and the
  `bootstrap-admin-htpasswd.sh` rotation procedure still apply.
- No OIDC, no IDP, no OAuth client to register or maintain.

**Negative / trade-offs:**

- One additional running component (1 pod) and one container image tag to
  keep current.
- `--provider` stub (`keycloak-oidc` + `--skip-oidc-discovery=true`) is
  necessary noise; documented in `templates/oauth2-proxy.yaml` with an
  inline comment.
- If oauth2-proxy has a security vulnerability, the window between upstream
  release and image-tag bump in `values.yaml` is the exposure period.
  Mitigated by Renovate/Dependabot automation already in the repo.

**ADR-0028 status:**

ADR-0028 is superseded by this ADR. The implementation (`infra/observability/templates/oauth2-proxy.yaml`,
`values.yaml` `oauth2Proxy:` block, and `ingress-signoz-ui.yaml` annotation
changes) ships in the same PR as this ADR.
