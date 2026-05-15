# ADR-0042: Daily-puzzle pre-generation worker

## Status
Accepted

## Context
- Today, `GET /v1/puzzles/daily` runs the bitmask-CSP generator
  (ADR-0039) on-demand on the first hit for a given date. PR #381
  tightened the BLACK-cell invariant so that no decorative arrow-less
  black cell is allowed; against the production word corpus,
  ~70% of date seeds now exhaust the 5s/Luby budget under the strict
  rule (measurements in PR #425).
- Failed attempts surface to the user as 422, write nothing back, and
  burn ~30s per request because the next visit re-runs the generator
  from scratch.
- The strict BLACK rule is a product requirement; relaxing it on a
  fallback path was rejected in PR #424.
- Prior related ADRs:
  - ADR-0013 (words-clues worker) — different scope: it reloads the
    word corpus, it does not pre-generate puzzles.
  - ADR-0040 (observability worker topology) — same deployment shape
    (k8s CronJob), different domain.
- This ADR exists to satisfy ADR-0001 §7 ahead of the implementation
  PRs: PR #426 (OpenAPI 404 schema), and the upcoming worker (B),
  CronJob (C), and route refactor (D).

## Decision
Pre-generate the daily puzzle for a rolling 7-day window in a
Kubernetes CronJob. The route becomes a pure read.

Concretely:
- New use case `EnsureUpcomingDailiesUseCase` in `:grid:application`
  walks `[today, today+6]` **sequentially**. For each missing puzzle
  id, it runs the generator under the strict BLACK rule with seed
  iteration — varying the random source via
  `seedFor(date, attempt) = date.toEpochDay() * 1_000_000_000L + attempt * innerAttempts`
  — up to N outer attempts until convergence; each outer attempt owns
  `innerAttempts` (default 50) contiguous inner seeds passed to the
  CSP solver's Luby restarts. `SEED_DAY_MULTIPLIER = 1_000_000_000L`
  was chosen so that even the last outer attempt's last inner seed for
  a given day cannot reach the first seed of the next day (fixed from
  the original `1_000L`, which caused inner-seed blocks from
  consecutive outer attempts to collide once `attempt * innerAttempts`
  exceeded `1_000`). On success it persists.
- New worker entry in `:grid:worker` (`--ensure-dailies`) wires the
  use case to the production repositories. Exit code 0 on full
  success, 1 if any date in the window fails after exhausting
  attempts.
- New k8s CronJob (Helm template under `infra/k8s`) runs the worker
  daily at 03:00 UTC with `activeDeadlineSeconds` covering the
  worst-case walk (~70 minutes for 7 days at ~10 minutes worst-case
  per date).
- `GET /v1/puzzles/daily` becomes a pure repository read. Returns
  `200` with the puzzle, or `404` (RFC 7807 problem body) when no
  row exists for the date — transient before the cron has run, or
  after exhaustive cron failure. The legacy `422` (generator failed
  at request time) is removed in PR D.

The four-PR rollout is expand-and-contract: the schema gains `404`
first (PR #426), the worker exists and produces rows (PR B + C),
then the route drops the on-demand path and the `422`
(PR D). No flag flip is needed because PR D lands after the cron is
populating rows.

Sequential walk is non-negotiable: each successful generation
updates clue cooldown state (ADR-0031) that the next day's
generator reads. Parallel walk would corrupt the ordering.

Seed iteration preserves the deterministic puzzle id per date
(UUID v7 of date midnight UTC, ADR-0021); only the generator's
randomization varies on retries.

## Consequences
- Easier: users never see a 30s 422 cliff; failures are detected
  overnight via the cron's exit code feeding a SigNoz alert
  (ADR-0027 / ADR-0032).
- Easier: `/daily` latency floor is a single indexed `SELECT`
  (~130ms measured) regardless of seed difficulty.
- Easier: a strict BLACK rule that fails 70% of seeds remains
  shippable because the cron has the budget to keep trying.
- Harder: a new cron deployment surface to maintain — Helm template,
  monitoring, on-call runbook.
- Harder: a 7-day rolling window means a deploy-day fix to the
  generator does not propagate immediately — it takes one cron tick
  (24h) to refresh the next 6 days.
- Different: the legacy `422` is removed from the contract in PR D,
  which is a breaking client-side change. The frontend handles `404`
  instead. Coordinated via the four-PR sequence above.
