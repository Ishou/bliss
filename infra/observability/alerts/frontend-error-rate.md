# Alert: Frontend error rate

Source-of-truth spec for the frontend uncaught-error alert (PR-F.3 /
ADR-0033 follow-up). SigNoz stores the rule in its own DB once it's
created via the UI; this file is the version-controlled definition so
the rule survives a SigNoz reinstall.

Sibling spec: [`api-5xx-error-rate.md`](./api-5xx-error-rate.md)
(ADR-0032). Same notification channel (`gmail-relay`), different
service.

## Identity

| Field         | Value                             |
|---------------|-----------------------------------|
| Name          | `frontend-error-rate-high`        |
| Severity      | `warning`                         |
| Owner         | maintainer                        |
| Channels      | `gmail-relay` (Email)             |
| Created       | 2026-05-10 (PR-F.3)               |

## Trigger

Fires when, over a rolling **5-minute** window, more than **5 spans**
on `service.name = frontend` carry `status.code = ERROR`.

| Aspect           | Value                              |
|------------------|------------------------------------|
| Evaluation freq  | every 1 minute                     |
| Pending duration | 2 consecutive evaluations          |
| Window           | 5 minutes (rolling)                |
| Threshold        | `> 5` (absolute count)             |
| Comparator       | `>`                                |

## Why absolute count, not ratio

Frontend traffic is much lower volume than backend at launch — a
ratio threshold (e.g. `errors / total > 0.01`) would be very noisy
because the denominator is tiny on quiet hours. A flat "more than 5
errors per 5 minutes" surfaces real outages without firing on
single-user transient blips.

Revisit when the frontend has consistent traffic patterns (likely
≥ 4 weeks of public traffic) and a ratio becomes computable.

## What spans match

PR-F.2's auto-instrumentations and PR-F.3's window-level error
capture both produce spans with `status.code = ERROR` for:

- **Uncaught JS exceptions** (`window.error`) — captured by PR-F.3.
  Span name `window.error`, attributes include exception type,
  message, stack, source location.
- **Unhandled promise rejections** (`window.unhandledrejection`) —
  captured by PR-F.3. Span name `window.unhandledrejection`,
  attributes include rejection reason + stack when the reason is an
  `Error`.
- **Fetch failures** (network-level) — `FetchInstrumentation` from
  PR-F.2 marks the span with `status.code = ERROR` when fetch
  throws (TLS error, DNS failure, offline, CORS preflight rejection).
- **HTTP 4xx and 5xx responses** observed by the browser —
  `FetchInstrumentation` marks `status.code = ERROR` automatically.
  Note this is *also* the same incident that triggers
  `api-5xx-error-rate-high` from the backend side; both alerts may
  fire for one outage. That's fine — different signal sources.

## Query (Builder mode)

| Builder field | Value |
|---|---|
| Data source | `Traces` |
| Aggregation | `count` |
| Filter | `service.name = frontend` |
| Filter | `has_error = true` *(SigNoz alias for `status.code = ERROR`)* |
| Time aggregation window | `5 min` |

In raw ClickHouse SQL form (use only if Builder mode doesn't render
something equivalent):

```sql
SELECT
  count() AS errors
FROM
  signoz_traces.distributed_signoz_index_v3
WHERE
  resource_string_service$$name = 'frontend'
  AND status_code = 2  -- OTel SpanStatusCode.ERROR
  AND timestamp >= now() - INTERVAL 5 MINUTE
```

> Column names follow SigNoz 0.122's ClickHouse schema. Adjust if a
> later version renames them. The intent (count error spans on the
> frontend service over 5 minutes) is the contract.

## Notification

Reuses the `gmail-relay` Email channel created for ADR-0032's API
alert. No new channel needed.

## Sampler note

The browser SDK is configured with a 10% trace sampler ratio
(`VITE_OTEL_SAMPLER_RATIO=0.1`) to bound legitimate traffic volume
on the public OTLP endpoint. Error spans **bypass** the ratio via
the `ErrorAwareSampler` in `otelTracer.ts` — every `window.error`
and `window.unhandledrejection` span is always sampled. Fetch-error
spans are sampled at the configured ratio because they piggyback on
the existing fetch span (already-ratio-sampled root). In practice
this means:

- A user hitting an uncaught JS exception generates 1 error span,
  every time.
- A user hitting a 500 from the API generates 1 error span at
  10% probability.

The threshold (5 errors / 5m) is set with the second case in mind;
genuine JS error storms push past 5 fast.

## Smoke test

Open Chrome DevTools console on `https://wordsparrow.io` and run:

```js
for (let i = 0; i < 6; i++) setTimeout(() => { throw new Error(`smoke test ${i}`); }, i * 50);
```

Expected: spans appear in SigNoz under `service.name=frontend` with
`status.code=ERROR` within ~30s, alert fires within ~5–7m, email
arrives in the maintainer's inbox.

## Re-importing after a SigNoz reinstall

Same drill as `api-5xx-error-rate.md`:

1. Recreate the `gmail-relay` channel using the SMTP table in the
   sibling spec (already done if PR-H is in place).
2. Recreate the alert rule using the Trigger + Builder tables above.
3. Smoke-test from a Chrome console as above.

## Future revisions

- Add a JS error storms upper bound (e.g. > 50 errors / 5m) at a
  higher severity once this baseline is stable.
- Wire a React error boundary that emits `react.error_boundary` spans
  with the component stack — useful for "the grid crashed" scenarios
  where the JS error is caught by React and never reaches `window.error`.
- Migrate channel + rule definitions out of SigNoz's UI and into a
  declarative IaC layer (HTTP API + a small `Job`) once we have more
  than two of either.
