# ADR-0052: Matomo admin auth — basic-auth retained; oauth2-proxy not applied

## Status

Accepted

## Context

ADR-0028 chose ingress-nginx basic auth (htpasswd) for all three admin
URLs, including `analytics.wordsparrow.io` (Matomo). ADR-0030 superseded
ADR-0028 by deploying oauth2-proxy for the SigNoz URLs
(`errors.wordsparrow.io`, `dashboard.wordsparrow.io`) to solve repeated
browser credential dialogs caused by XHR/WebSocket calls from the SigNoz
React SPA.

ADR-0030's decision section is explicitly scoped to SigNoz. This ADR
records why the same substitution is not applied to Matomo's admin
Ingress (`analytics.wordsparrow.io /` catch-all).

## Decision

Matomo's admin Ingress retains ingress-nginx basic auth referencing the
`admin-htpasswd` Secret. The per-namespace bootstrap procedure from
ADR-0028 §2 (`bootstrap-admin-htpasswd.sh --namespace=matomo`) is
unchanged.

oauth2-proxy is **not** added to the Matomo namespace because:

- Matomo is server-rendered PHP, not a React SPA. The browser prompt
  problem that drove ADR-0030 arises only with JavaScript-initiated
  XHR/WebSocket calls: browsers do not attach `Authorization: Basic …`
  headers to those calls automatically. For Matomo the browser issues
  ordinary navigation requests for each page; the credential header
  travels with every request and the dialog appears exactly once per
  session, which is acceptable.
- Adding oauth2-proxy here would introduce a second deployment with no
  usability benefit, at the cost of an extra component to operate and
  an extra image tag to keep current (the same trade-off ADR-0028
  documented and ADR-0030 only accepted for SigNoz).

## Consequences

**Positive:**
- No new components in the `matomo` namespace.
- A single browser-credential prompt per session, matching the
  server-rendered PHP request shape.

**Negative / trade-offs:**
- If Matomo ever migrates to an SPA-heavy frontend that issues
  JavaScript-driven XHR calls, the credential re-prompt issue will
  resurface. Apply the oauth2-proxy pattern from ADR-0030 at that point.
