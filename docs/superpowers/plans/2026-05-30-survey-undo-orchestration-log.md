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

- **2026-05-30 — Bootstrap PR #693 §6a cycles (all resolved by the auto-fixer loop):** Cycle 1 — ADR-0059 amendment bundled with implementation (Phase 2c) violated ADR-0001 §7; fixed `a0483eb2` by extracting standalone **Phase 0.5** (`docs/survey-undo-adr-amendment`, gates Phase 1). Cycle 2 — stale "ADR amendment" refs in Phase 4 intro / line 422; fixed `15519878`. Cycle 3 — multi-line KDoc in the Phase 2a `withTxConnection` snippet (pre-empts an implementer cycle); fixed `f563c60b`. Cycle 4 — **LGTM, no findings**.
- **2026-05-30 17:09 UTC — MERGED PR #693** (squash) → spec, plan, procedure, log now on `main`. Bootstrap branch auto-deleted. Phase 0.5 unblocked; dispatches next tick.
- **Note on log delivery:** `main` is branch-protected, so post-bootstrap log entries ride this orchestrator-owned branch (`docs/survey-undo-orch-log`) via PR rather than a direct push. One log PR for the rollout, appended each loggable tick.
- **2026-05-30 — Phase 0.5 · DISPATCHED implementer** (branch `docs/survey-undo-adr-amendment`, base `main`; docs-only ADR-0059 amendment). Bootstrap merged → dependency satisfied. ADR-context preflight empty for `docs/adr/**` (INDEX globs target source paths); implementer pointed at ADR-0059 + ADR-0001 §7. Flagged the `adr-index-coherence` may-reject-nonexistent-path caveat for the INDEX.md entry.
