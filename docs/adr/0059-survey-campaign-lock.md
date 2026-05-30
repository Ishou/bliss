# ADR-0059: Campaign-bounded survey rating sessions

## Status
Accepted (2026-05-30). Companion spec: `docs/superpowers/specs/2026-05-29-survey-campaign-lock-design.md`. Companion plan: `docs/superpowers/plans/2026-05-30-survey-campaign-lock.md`.

## Context

The survey context (ADR-0056) collects clue ratings continuously. There is no first-class notion of "the data window for training batch N" — the export job converts whatever has accumulated to CSV on demand, ratings landing during an export window mix into the next training batch with no provenance, and the maintainer cannot pause data collection while a batch is being frozen.

We need a lifecycle for rating-collection windows so that:

1. Each rating belongs to exactly one batch, queryable for export.
2. The maintainer can pause `/sondage` (no new ratings) while a batch is being prepared.
3. The pause uses the simplest possible admin path — a 1-line SQL statement runnable from psql — without precluding HTTP wrappers later.

## Decision

Introduce a **`campaigns`** table in the survey schema. Each row represents a rating-collection window with `(campaign_id, batch_label, opened_at, closed_at)`. A partial unique index `WHERE closed_at IS NULL` enforces at most one open campaign at any time.

`ratings.campaign_id` and `pair_ratings.campaign_id` are added as nullable UUID FKs. The application stamps the currently open campaign's id at insert time. When no open campaign exists, `POST /v1/items/{itemId}/rating` and `POST /v1/pair-ratings` return **423 Locked**.

A new `GET /v1/campaign/current` returns the current row (the open one if any, otherwise the most recently opened) so the frontend can render a lock banner.

Campaign open and close are performed by direct SQL in v1. No admin HTTP endpoints ship in v1. The 1-line verbs are:

```sql
INSERT INTO campaigns (campaign_id, batch_label)
     VALUES (gen_random_uuid(), 'round-7') RETURNING campaign_id, opened_at;

UPDATE campaigns SET closed_at = now()
 WHERE closed_at IS NULL
RETURNING campaign_id, batch_label, closed_at;
```

The maintainer runs these from psql when prepping a training batch. Wrapping them in HTTP later is an additive change.

Post-commit undo of ratings is deliberately **out of scope** here. The schema introduced is forward-compatible: a follow-up ADR will add `submission_id` and `withdrawn_at` for post-commit undo. The companion spec §10 enumerates the additive columns and endpoints the undo follow-up will need; nothing in v1 forecloses them.

## Consequences

- The export query gains a clean `WHERE campaign_id = ?` predicate. Existing exports continue to work against backfilled ids.
- `/sondage` becomes a stateful surface — the page reflects an operational state ("collecting" vs "paused") in addition to per-item state.
- A backfill is required for historical ratings. See `scripts/survey/backfill_campaigns.py` and the spec §7. The backfill is a one-shot operational script, deliberately NOT a Flyway migration — a bug in Modal-log parsing cannot wedge the cluster.
- Two operational risks to be mindful of:
  - "Maintainer closes a campaign and forgets to open the next one" — symptom is users see the lock banner indefinitely. A SigNoz alert on `findOpen() == null` for > 1 h is recommended; not in the v1 PR sequence.
  - A rating-submit transaction that holds a row lock on the open campaign at the moment of close commits inside the closing transaction's window. Acceptable: that rating is the last entry in the closing campaign.
