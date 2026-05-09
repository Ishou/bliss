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
    AND attributes_string['http.method'] != ''
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
3. Smoke-test using the fault-injection procedure in the Smoke test
   section below and wait 5–7 minutes for the alert email.

## Smoke test

### Verifying OTel span capture (does NOT trigger the alert)

```sh
# Send malformed JSON to POST /v1/lobbies. game-api's SerializationException
# handler maps this to HTTP 400 (not 500) — see LobbiesRoute.kt:58-59.
# Use this to confirm the OTel agent is capturing spans and they appear
# in SigNoz Traces Explorer, but do not expect the 5xx alert to fire.
curl -sS -X POST https://api.wordsparrow.io/v1/lobbies \
     -H 'Content-Type: application/json' \
     --data '{"this is not": "valid json'
```

Expected: HTTP 400 response, and a span with `http.status_code=400`
visible in the SigNoz Traces Explorer within ~30s. This confirms the
OTel pipeline is healthy. It will **not** trigger the 5xx alert.

### Triggering a real 5xx to smoke-test the alert threshold

To confirm the alert fires, inject a fault at the infrastructure layer:

```sh
# 1. Scale down the Postgres StatefulSet so game-api loses its DB.
kubectl scale statefulset -n game <postgres-sts-name> --replicas=0

# 2. Send a valid CreateLobbyRequest — the API will reach the DB,
#    fail, and record a 5xx span.
curl -sS -X POST https://api.wordsparrow.io/v1/lobbies \
     -H 'Content-Type: application/json' \
     -d '{"ownerSessionId":"smoke-test","ownerPseudonym":"tester"}'

# 3. Repeat step 2 enough times over 5 minutes to push error_rate > 1%.

# 4. Restore Postgres.
kubectl scale statefulset -n game <postgres-sts-name> --replicas=1
```

Expected: after 2 consecutive 1-minute evaluations with error_rate > 1%
(~7 minutes from first fault), an alert email arrives in the maintainer's
inbox. Restore Postgres immediately after confirming the email.

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
