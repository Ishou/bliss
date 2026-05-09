# Alert: API 5xx error rate

Source-of-truth spec for the launch symptom alert (ADR-0032). SigNoz
stores the rule in its own DB once it's created via the UI; this file
is the version-controlled definition so the rule survives a SigNoz
reinstall (re-create it from this spec).

## Identity

| Field         | Value                             |
|---------------|-----------------------------------|
| Name          | `api-5xx-error-rate-high`         |
| Severity      | `warning`                         |
| Owner         | maintainer                        |
| Channels      | `gmail-relay` (Email)             |
| Created       | 2026-05-10 (ADR-0032)             |

## Trigger

Fires when, over a rolling **5-minute** window, the share of traced
HTTP spans on `grid-api` or `game-api` with `http.status_code ≥ 500`
exceeds **1%** of all traced HTTP spans on those two services.

| Aspect           | Value                              |
|------------------|------------------------------------|
| Evaluation freq  | every 1 minute                     |
| Pending duration | 2 consecutive evaluations          |
| Window           | 5 minutes (rolling)                |
| Threshold        | 0.01 (i.e. 1%)                     |
| Comparator       | `>`                                |

## Query (ClickHouse)

The query SigNoz evaluates. Adjust the table prefix
(`signoz_traces`) if your installation uses a different schema name.

```sql
SELECT
  countIf(
    resource_string_service$$name IN ('grid-api', 'game-api')
    AND attributes_number['http.status_code'] >= 500
  ) AS errors,
  countIf(
    resource_string_service$$name IN ('grid-api', 'game-api')
    AND attribute_string['http.method'] != ''
  ) AS total,
  errors / nullIf(total, 0) AS error_rate
FROM
  signoz_traces.distributed_signoz_index_v3
WHERE
  timestamp >= now() - INTERVAL 5 MINUTE
```

> The exact column names follow SigNoz 0.122's ClickHouse schema. If
> SigNoz upstream renames columns in a later version, regenerate this
> spec; the **intent** (5xx span count / total HTTP span count, both
> filtered to the two API services) is the contract.

The simpler "Builder" mode in the SigNoz UI achieves the same thing
without raw SQL — pick "Traces", filter `service.name in
[grid-api, game-api]` AND `http.status_code >= 500`, aggregate
`count`, group by nothing. Then add a second series for `total` (same
filter without the status_code clause), and a formula `A / B > 0.01`.
Either form works.

## Notification

**Channel:** `gmail-relay` (Email).

Configure once in SigNoz UI under **Settings → Channels → New
Channel → Email** with:

| Field          | Value                                                 |
|----------------|-------------------------------------------------------|
| Channel name   | `gmail-relay`                                         |
| To             | the maintainer's email                                |
| SMTP host      | `smtp.gmail.com`                                      |
| SMTP port      | `587`                                                 |
| Auth username  | the maintainer's Gmail address                        |
| Auth password  | a **Google App Password** (https://myaccount.google.com/apppasswords) |
| Auth identity  | (leave blank)                                         |
| From address   | same as username                                      |

Hit **Test** before saving. The expected outcome: a "test alert"
email lands in the maintainer's inbox within ~10s. If it doesn't:

- Gmail-side 535 (auth rejected) → app password is stale or copy-paste
  added a space. Regenerate, re-paste.
- Connection refused / timeout → the cluster's egress on port 587 is
  blocked. Verify with a `kubectl run --rm -it --image=alpine
  --restart=Never -- nc -vz smtp.gmail.com 587` from the
  `observability` namespace.

## Re-importing after a SigNoz reinstall

1. Recreate the Email channel using the Notification table above.
2. Recreate the alert rule using the Trigger + Query tables above.
3. Smoke-test by curling a guaranteed 500 against either API and
   waiting 5–7 minutes for the email.

## Smoke test

```sh
# Force a 5xx on game-api by sending malformed JSON to a known POST
# endpoint. game-api logs the failure; the OTel agent records the
# span with http.status_code=500; SigNoz's evaluator picks it up
# at the next eval tick.
curl -sS -X POST https://api.wordsparrow.io/v1/lobbies \
     -H 'Content-Type: application/json' \
     --data '{"this is not": "valid json'
```

Expected: error response from the API, an entry with HTTP 500 in
the SigNoz UI's traces explorer within ~30s, and an email in the
maintainer's inbox within ~7 minutes (5-min window + 2 evaluation
ticks).

## Future revisions

- Once ≥ 4 weeks of traffic accumulate, replace the static 1%
  threshold with an SLO-burn-rate alert (multi-window, multi-burn-rate
  per the Google SRE workbook). Today's traffic is too low to compute
  burn rates meaningfully.
- Add a latency symptom (`p99 > 500ms over 5m`) once we have a
  baseline.
- Migrate channel + rule definitions out of SigNoz's UI and into a
  declarative IaC layer (HTTP API + a small `Job`) once we have
  more than two of either.
