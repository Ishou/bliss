# Survey Rating Undo — Orchestration Log

Append-only log of decisions the orchestrator made during this rollout. For human review when convenient.

## Standing decisions

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on `LGTM` + green blocking CI (standard 3a) | Maintainer invoked `/orchestrate` in response to an explicit "hands-off cron-driven dispatch + auto-merge" offer (2026-05-30) |
| Polling cadence | 120 s (`*/2 * * * *` via CronCreate) | Matches dispatch-skill default; `claude-review` IN_PROGRESS = wait |
| Continuity | `CronCreate` (session-only in practice; `durable` flag ignored by runtime) | Recreate if the rollout outlives the session |
| Fix-cycle budget per phase | 3 | Dispatch-skill default |
| Phase order | Strictly sequential: bootstrap → 0.5 → 1 → 2a → 2b → 2c → 3; each branches off `main` after predecessor merges | 0.5 (ADR amendment) gates 1 (ADR-0001 §7); 2b needs 2a's tx helper; 2c needs 2b's repos; 3 needs 1 (types) + 2c (server behavior) |
| Cap override | Standing; orchestrator may invoke proactively to short-circuit 3c-loop-terminator. PR 2c pre-flagged cap-heavy | memory `feedback-standing-cap-override`, `feedback-cap-override-short-circuit` |
| Escalation trigger | 3 failed fix-cycles on any PR, OR identical non-cap finding across two §6a cycles | Stops the chain; logs an `ACTION` entry + `CronDelete` self |

## Pre-orchestration state

- Spec + plan authored on `fix/sondage-no-campaign-ux` (an unrelated campaign-UX branch); carried onto this clean `docs/survey-undo-orchestration` branch off `origin/main` so implementer agents branch from `main`.
- Campaign lifecycle (ADR-0059, `campaigns` table, 423-lock) is already on `main` — the undo rollout depends on it and branches cleanly.
- **Stashed:** `docs/superpowers/plans/2026-05-30-survey-campaign-lock-orchestration-log.md` wip (unrelated) — `git stash` entry "campaign-lock-log wip (unrelated)" on `fix/sondage-no-campaign-ux`. Restore after the rollout (or whenever returning to that branch).
- Bootstrap docs PR (this branch) carries: spec, plan, this procedure + log. Merges via standard 3a (memory `feedback-bootstrap-pr-uses-3a`). Phase 1 does not dispatch until the bootstrap PR is on `main`.

## Event log

(entries appended chronologically by the cron)
