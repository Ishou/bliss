# SigNoz alerts

Version-controlled SigNoz alert rules. Two flavours coexist in this directory:

- **Markdown specs** (`*.md`) — human-curated specs for rules that live in
  the SigNoz UI (created manually, re-imported from the spec after a
  reinstall). Used for the launch-symptom alerts in ADR-0032 and the
  pre-existing frontend / grid-worker checks.
- **JSON rule definitions** (`*.json`) — declarative SigNoz rule bodies
  applied via [`apply.sh`](./apply.sh). Used for alerts that have a
  stable, machine-applyable shape (currently the NATS consumer-lag +
  DLQ alerts).

## Why NATS alerts live here, not as `PrometheusRule`

The chart `infra/nats/` previously shipped a
`monitoring.coreos.com/v1 PrometheusRule` (PR #544) gated behind
`alerts.enabled`. The flag stayed `false` permanently because
`infra/platform/` does not install kube-prometheus-stack — and won't:
SigNoz is the observability backend (ADR-0027). The CRD was therefore
unusable in prod. The PR #557 incident note in `infra/nats/README.md`
records the trigger for the migration.

The NATS exporter metrics already reach SigNoz: the
`prometheus-nats-exporter` sidecar exposes
`nats_consumer_num_pending` + `nats_stream_messages{stream_name=...}`
on port 7777 of `bliss-nats-0`, and the SigNoz k8s-infra collector's
`prometheus` preset (PR #549) scrapes pods annotated with
`prometheus.io/scrape=true`. The metrics are in ClickHouse; the alerts
are now defined here.

## Per-alert spec

| File | Metric | Threshold | Window | Severity |
| --- | --- | --- | --- | --- |
| [`nats-consumer-lag-warning.json`](./nats-consumer-lag-warning.json) | `nats_consumer_num_pending` (max by consumer + stream) | `> 100` | 5m | warning |
| [`nats-consumer-lag-critical.json`](./nats-consumer-lag-critical.json) | `nats_consumer_num_pending` (max by consumer + stream) | `> 1000` | 1m | critical |
| [`nats-dlq-non-empty.json`](./nats-dlq-non-empty.json) | `nats_stream_messages{stream_name="WORDSPARROW_USER_EVENTS_DLQ"}` | `> 0` | 1m | warning |

All three are `threshold_rule` with `compositeQuery.queryType=promql`,
`op="1"` (greater-than) and `matchType="1"` (at-least-once during the
window). Notification channel is bound out-of-band in the SigNoz UI
(see `api-5xx-error-rate.md` for the `gmail-relay` Email-channel setup).

## Applying

### Canonical path: CI workflow (recommended)

Per MANIFESTO.md §6 ("CI is the only path to production"), the alerts
are applied via the `Apply SigNoz Alerts` workflow
(`.github/workflows/apply-signoz-alerts.yml`):

1. GitHub → Actions → **Apply SigNoz Alerts** → **Run workflow**.
2. Optionally set the `ref` input to pin the apply to a specific
   commit / tag (defaults to `main`).

The workflow reads `SIGNOZ_URL` + `SIGNOZ_API_KEY` from GitHub
**repository secrets** (one-time human bootstrap; see
[`docs/secrets.md`](../../../docs/secrets.md) for the general pattern):

| Secret name      | Source |
| ---------------- | ------ |
| `SIGNOZ_URL`     | e.g. `https://errors.wordsparrow.io` |
| `SIGNOZ_API_KEY` | SigNoz UI → Settings → API Keys, or `kubectl -n observability get secret signoz-api-key -o jsonpath='{.data.key}' \| base64 -d` |

The workflow serializes runs via the `apply-signoz-alerts` concurrency
group, so two manual triggers cannot race.

### Fallback: local shell (dev-loop only — not the recommended prod path)

Useful while iterating on the JSON rule bodies against a non-prod
SigNoz, or for emergency apply if GitHub Actions is unavailable:

```sh
export SIGNOZ_URL=https://errors.wordsparrow.io       # or dashboard.wordsparrow.io
export SIGNOZ_API_KEY=$(kubectl -n observability get secret signoz-api-key \
  -o jsonpath='{.data.key}' | base64 -d)              # or generate in SigNoz UI -> Settings -> API Keys
./apply.sh
```

`apply.sh` is idempotent: it lists existing rules, PUTs an update if a
rule with the same `alert` name exists, POSTs a new one otherwise. Each
rule reports `ok` or `FAIL` with the HTTP code and response body.

## Viewing + silencing

- **View**: SigNoz UI → Alerts. Filter by `component=nats` label.
- **Silence**: SigNoz UI → Alerts → select rule → "Mute" for a duration.
  Silences are stored in SigNoz's DB, not in this repo — a planned mute
  needs re-applying after a SigNoz reinstall.
- **History**: SigNoz UI → Alerts → rule → "Timeline" shows firings.

## Schema version

The JSON files were authored against the SigNoz **v0.122** rules API
shape (the version pinned in `infra/observability/Chart.lock` at the
time of writing). Field names (`evalWindow`, `frequency`, `condition`,
`compositeQuery.queryType`, `op`, `matchType`) are stable across the
0.5x-0.7x line per the SigNoz changelog, but the operator should
re-verify against the deployed SigNoz version on upgrade. TODO: smoke
the JSON shape against a live SigNoz POST and fix up if anything
rejects.

## Future

If `infra/platform/` ever adds the Prometheus Operator (e.g. to host
non-SigNoz Alertmanager routing), the same three NATS alerts could
also be expressed as a `PrometheusRule`. The canonical source remains
this directory; a `PrometheusRule` would be a secondary export, not a
replacement.
