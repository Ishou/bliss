# Identity User Roles — Orchestration Log

Append-only log of decisions the orchestrator made during this rollout. For human review when convenient.

## Standing decisions

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on `LGTM` + green blocking CI | Auto-merge-default grant (2026-05-27): merge on green+LGTM without asking |
| Polling cadence | 120 s (every 2 min via `*/2 * * * *` CronCreate) | Matches dispatch-skill cadence; `claude-review` IN_PROGRESS = wait |
| Continuity | `CronCreate` (session-only in practice; `durable` flag ignored by runtime; auto-expires 7 days) | Runtime constraint |
| Fix-cycle budget per phase | 3 | dispatch-skill default |
| Review-pass cap per PR | 5 | ADR-0001 §6a |
| Cap-override | Orchestrator may invoke §4 soft-target override proactively, citing the standing grant | "the 400 cap MAY be by-passed by YOUR call even without my call" (2026-05-25) |
| Phase order | Strictly sequential A1 → A2 → A3; A2 gated on A1 MERGED (cites ADR-0060); A3 stacked on A2 | ADR-0001 §3/§7 + plan PR-sequencing |
| Escalation trigger | 3 failed fix-cycles, or identical-finding loop on a non-cap finding, or CLOSED-not-merged PR | Stops chain; logs `ACTION`; `CronDelete` self |

## Pre-orchestration state

- Spec + plan committed on branch `docs/identity-user-roles` (commits `3a59f5af`, `e52df053`),
  not yet pushed at procedure-authoring time. This PR bundles spec + plan + procedure + log.
- No phase branches/PRs exist yet (`docs/identity-roles-adr-event`, `feat/identity-role-persistence`,
  `feat/identity-role-bootstrap` are unborn).
- Dedicated worktree at `.claude/worktrees/docs+identity-user-roles` (per always-use-worktree rule).
- No relevant stashes or untracked files to recover.

## Event log

(entries appended chronologically by the cron)
