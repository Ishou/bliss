# bliss-signoz-alerts

A Helm chart that applies SigNoz alert rule definitions via an in-cluster
`post-install,post-upgrade` hook Job — not via `kubectl port-forward` from a
GitHub Actions workflow. The 2026-05-20 SigNoz workflow incident (the
external workflow had to oauth2-proxy-bypass the SigNoz Ingress, then
port-forward, then curl) is the trigger; the fix is the rule encoded in
CLAUDE.md: **Configure-in-cluster, not push-from-CI.** The chart-Job
pattern is the canonical shape (mirrors `infra/nats/templates/stream-bootstrap-job.yaml`).

The previous external workflow (`apply-signoz-alerts.yml`) and the
external `apply.sh` script are deleted by this PR.

## Per-alert spec

| File                                                                       | Metric                                                                   | Threshold | Window | Severity |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------ | --------- | ------ | -------- |
| [`files/nats-consumer-lag-warning.json`](./files/nats-consumer-lag-warning.json)   | `nats_consumer_num_pending` (max by consumer + stream)                   | `> 100`   | 5m     | warning  |
| [`files/nats-consumer-lag-critical.json`](./files/nats-consumer-lag-critical.json) | `nats_consumer_num_pending` (max by consumer + stream)                   | `> 1000`  | 1m     | critical |
| [`files/nats-dlq-non-empty.json`](./files/nats-dlq-non-empty.json)                 | `nats_stream_messages{stream_name="WORDSPARROW_USER_EVENTS_DLQ"}`        | `> 0`     | 1m     | warning  |

All three are `threshold_rule` with `compositeQuery.queryType=promql`,
`op="1"` (greater-than), and `matchType="1"` (at-least-once during the
window). Notification channel binding is out-of-band in the SigNoz UI
(see the sibling `api-5xx-error-rate.md` for the `gmail-relay` channel).

The sibling `*.md` specs document alerts that live in the SigNoz UI
today. Helm ignores files outside `Chart.yaml` / `values.yaml` /
`templates/` / `files/`, so they ride along as docs.

## How it works

The chart bundles `files/*.json` into a ConfigMap (install-phase, NOT
a hook — so the CM exists before the Job runs; lesson from PR #557).
A `post-install,post-upgrade` hook Job mounts the CM at `/rules`,
reads the API key via `envFrom`, and reconciles each rule: PUT to
`/api/v1/rules/<id>` if a rule with the same `alert` name exists,
POST otherwise. Hook delete policy is
`before-hook-creation,hook-succeeded`.

## Bootstrap (one-time, per cluster)

The SigNoz API key never leaves the cluster. Create the Secret once:

```sh
kubectl create secret generic signoz-alerts-apply-key \
  -n observability \
  --from-literal=apiKey='<paste-SigNoz-API-key>'
```

Get the key from SigNoz UI → **Settings → API Keys** (role:
`signoz-admin`), or from the in-cluster Secret if you've already issued
one:

```sh
kubectl -n observability get secret signoz-api-key \
  -o jsonpath='{.data.key}' | base64 -d
```

The Secret's data key MUST be `apiKey` — `envFrom` maps it to the
`$apiKey` env var the Job's script reads.

## Install / upgrade

```sh
helm upgrade --install bliss-signoz-alerts ./infra/observability/alerts \
  -n observability --wait --timeout 5m
```

CI runs the same command — see `.github/workflows/deploy-observability-alerts.yml`,
which triggers on every push to `main` touching
`infra/observability/alerts/**`, plus manual `workflow_dispatch`.

## Removing a rule

Delete the JSON file from `files/` and re-run `helm upgrade`. Note that
Helm does not auto-clean SigNoz rules: the rule will linger in SigNoz's
DB until manually removed via the UI (Alerts → select rule → Delete) or
via the SigNoz API (`DELETE /api/v1/rules/<id>`). Accept the orphan
between deletion and manual cleanup.

## Schema version

JSON bodies target SigNoz **v0.122** (pinned in
`infra/observability/Chart.lock`). Re-verify on upgrade.

## Viewing + silencing

SigNoz UI → Alerts. Filter by `component=nats`. Silences live in
SigNoz's DB; reapply after a SigNoz reinstall.
