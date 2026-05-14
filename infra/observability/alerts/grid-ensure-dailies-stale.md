# Alert: grid ensure-dailies CronJob stale

Source-of-truth spec for the daily-pre-generation freshness alert
(ADR-0042). Same convention as `api-5xx-error-rate.md`: SigNoz stores
the rule in its own DB once it's created via the UI; this file is the
version-controlled definition so the rule survives a SigNoz reinstall.

## Identity

| Field         | Value                                  |
|---------------|----------------------------------------|
| Name          | `grid-ensure-dailies-stale`            |
| Severity      | `warning`                              |
| Owner         | maintainer                             |
| Channels      | `gmail-relay` (Email)                  |
| Created       | 2026-05-14 (ADR-0042)                  |

## Trigger

Fires when the `wordsparrow-api-ensure-dailies` CronJob has not had a
successful Job completion in the last **26 hours**. The 26h threshold
gives the daily 03:00 UTC schedule a 2h grace window before paging on
a single missed run.

| Aspect           | Value                                        |
|------------------|----------------------------------------------|
| Evaluation freq  | every 5 minutes                              |
| Pending duration | 1 evaluation                                 |
| Window           | instantaneous (gauge age check)              |
| Threshold        | 93600 seconds (26 hours)                     |
| Comparator       | `>`                                          |

## Query (PromQL via kube-state-metrics)

`kube-state-metrics` exposes `kube_cronjob_status_last_successful_time`
as the Unix timestamp of the most recent successful Job; subtracting
from `time()` gives staleness in seconds.

```promql
time()
  - max(
      kube_cronjob_status_last_successful_time{
        namespace="grid",
        cronjob="wordsparrow-api-ensure-dailies"
      }
    )
  > 93600
```

> If `kube-state-metrics` is not yet deployed in the observability
> stack, this alert is non-functional until it is. Track that as a
> follow-up; the CronJob still works, it just lacks the symptom alarm.

## Notification

**Channel:** `gmail-relay` (Email). Reuse the channel created for
`api-5xx-error-rate-high` (see `api-5xx-error-rate.md` "Notification").

## Re-importing after a SigNoz reinstall

1. Confirm the `gmail-relay` channel exists (re-create from
   `api-5xx-error-rate.md` if not).
2. Recreate the alert rule using the Trigger + Query tables above.
3. Smoke-test by suspending the CronJob for >26h in a staging
   environment, or by manually deleting recent successful Jobs from
   the namespace and waiting one evaluation cycle.

## Operational notes

- A failed run does NOT auto-retry (chart sets `backoffLimit: 0`); the
  next day's CronJob picks up the work. This alert is the safety net.
- To backfill manually after a failure or before the next scheduled
  run, create a one-off Job from the CronJob template:
  `kubectl create job --from=cronjob/wordsparrow-api-ensure-dailies
  ensure-dailies-backfill-$(date +%Y%m%d) -n grid`.
