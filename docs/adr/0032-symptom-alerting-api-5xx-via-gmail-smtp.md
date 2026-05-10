# ADR-0032: Symptom-based alerting — API 5xx > 1% / 5m via Gmail SMTP

## Status

Accepted

## Context

The MANIFESTO requires *"alerting on symptoms (error rate, latency), not
causes (CPU, disk)"*. PR-D wired SigNoz; PR-E wired the OTel Java agent
on `grid-api` + `game-api`. Spans now flow continuously into ClickHouse
with `http.status_code` and `service.name` resource attributes. Nothing
yet wakes the maintainer when the API starts returning 500s.

The site is opening to the public. We need at least **one** working alert
before the door opens, and we need a delivery channel that:

- Reaches a human at any time of day.
- Has no monthly fee at our volume (one alert, hopefully zero pages).
- Doesn't introduce a new sub-processor (per [ADR-0025](./0025-product-analytics-matomo-rgpd.md)
  zero-sub-processors stance — privacy is part of the product).
- Doesn't require a Slack / Discord workspace we don't have.

## Decision

### 1. The rule

A single symptom alert at launch:

> `service.name in (grid-api, game-api)` AND `http.status_code` ≥ 500
> exceeds **1% of all traced HTTP spans over a rolling 5-minute window**.

Evaluated every 1 minute, fires when the threshold is crossed and
remains pending for at least 2 consecutive evaluations (so a single
brief spike doesn't page).

The rule is documented in
[`infra/observability/alerts/api-5xx-error-rate.md`](../../infra/observability/alerts/api-5xx-error-rate.md)
in human-readable form. SigNoz stores the rule itself in its own DB
once created via the UI; the markdown spec is the version-controlled
source of truth so the rule survives a SigNoz reinstall (re-import is
a copy-paste from the spec).

### 2. The channel: Gmail SMTP relay

SigNoz's built-in alertmanager (chart 0.122 default,
`signoz_alertmanager_provider: signoz`) supports Email channels with
arbitrary SMTP credentials. We point it at:

| Field             | Value                          |
|-------------------|--------------------------------|
| SMTP host         | `smtp.gmail.com`               |
| SMTP port         | `587`                          |
| Authentication    | STARTTLS, PLAIN                |
| Username          | the maintainer's Gmail address |
| Password          | a **Google App Password**, not the account password |
| From address      | same as username               |
| Recipient         | the maintainer's email         |

Google App Passwords (https://myaccount.google.com/apppasswords) are
16-character one-purpose-only credentials revocable independently of
the account password — exactly the right granularity for "one mail
relay used by one alertmanager".

### 3. Why Gmail SMTP and not …

| Alternative                 | Verdict                                              |
|-----------------------------|------------------------------------------------------|
| Self-hosted Postfix sidecar | Adds operational load (port 25 reputation, DKIM, DMARC, SPF) for one alert. Veto. |
| Transactional service (Brevo, SendGrid, Mailgun) | New sub-processor; veto per ADR-0025. Revisit if alert volume grows past Gmail's 500/day relay limit. |
| Pushover / Pushbullet       | Ergonomic for mobile but introduces a vendor + an eventual paid tier. Revisit when alert volume justifies it. |
| Slack / Discord webhook     | No workspace today. Revisit if one materializes.     |
| PagerDuty / OpsGenie        | Massive overkill for one solo-maintainer service.    |

Gmail SMTP is the lowest-friction option that respects the
sub-processor constraint (Google is already a sub-processor — Workspace
hosts the maintainer's own mail).

### 4. Why no k8s Secret for the SMTP creds

SigNoz UI is the system of record for alert-channel credentials. The
app password gets entered once via the SigNoz Settings → Channels form
and persists in SigNoz's internal DB. Mirroring it into a Kubernetes
Secret would:

- Duplicate the credential (drift risk: rotate Gmail password, forget
  to update Secret, alert silently breaks but the rule still evaluates
  — failure mode is worse than no Secret).
- Add a bootstrap step with no consumer (SigNoz wouldn't read the
  Secret; it'd be a backup-by-side-effect).

The maintainer's password manager already holds the app password. A
SigNoz reinstall requires re-pasting it into the Channels form; the
spec markdown documents the steps.

### 5. Threshold rationale

1% / 5m was chosen because:

- At launch volume (estimated < 100 req/min), a true 1% error rate is
  a real symptom (not noise) — single 500s won't trip it.
- 5-minute window smooths transient deploy-time blips (rolling pod
  restart, probe flake) without being so wide that we wait 20 minutes
  to learn about a real outage.
- The 2-consecutive-evals guard adds a small additional damper without
  meaningfully delaying real fires.

Once we have a few weeks of real traffic, revisit. SLO-style burn-rate
alerts become viable when there's enough baseline traffic to compute
them; today there isn't.

## Consequences

**Easier:**
- One rule, one channel — debugging the alerting system itself stays
  trivial. When the test alert doesn't arrive, there's exactly one
  SMTP path to investigate.
- App passwords are revocable per-purpose; rotating the alerting cred
  doesn't invalidate any other Google integration.

**Harder:**
- Adding a second channel (e.g. push to phone) requires a second click-
  through plus a second cred. The UI walk-through doesn't scale to
  many channels — at that point we'd lift channel definitions into IaC,
  probably via SigNoz's HTTP API.
- Alert rule lives in SigNoz's DB; a chart reinstall loses it unless
  re-imported from `infra/observability/alerts/`. Mitigated by the
  spec being version-controlled, but it's a manual step.
- Gmail's 500-mail/day SMTP-relay quota is far above expected alert
  volume but a runaway alert loop could burn through it fast enough
  to break personal mail relay too. Mitigated by the 5-minute
  evaluation window + the 2-consecutive-eval guard — at most ~12
  fires/hour for the same rule.

**Different:**
- Severity model is binary: alert fires → email arrives → maintainer
  reads it. No on-call rotation, no escalation, no acknowledgement
  flow. For a one-person service this is the right shape; if the
  team grows past one operator we need a real on-call tool.

## Alternatives in detail

(See §3 above. None offer a clear win at our current scale + privacy
posture. Decision is reversible: Email channels in SigNoz coexist with
webhook channels, so adding a second-line tool later doesn't require
removing this one.)
