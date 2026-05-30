# Survey training_weight — Orchestration Log

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
| Phase order | B0 gate → {B1, B2} parallel → B3 (needs B1) → B4 (needs B2+B3) → B5 (needs B4) → B6 (needs B4+B5). No stacking — all phases base off `main`, gated on deps MERGED | plan PR-sequencing |
| Escalation trigger | 3 failed fix-cycles, or identical-finding loop on a non-cap finding, or CLOSED-not-merged PR | Stops chain; logs `ACTION`; `CronDelete` self |

## Pre-orchestration state

- Spec + plan committed on branch `docs/survey-training-weight` (spec `f0831fea`, plan
  `bc7323c5`), pushed; PR #697 open. This PR bundles spec + plan + procedure + log.
- No phase branches/PRs exist yet (`feat/survey-training-weight-migration`,
  `feat/survey-gold-window-policy`, `feat/survey-training-weight-repos`,
  `feat/survey-recompute-use-case`, `feat/survey-role-changed-consumer`,
  `feat/survey-training-weight-wiring` are unborn).
- Dedicated worktree at `.claude/worktrees/docs+survey-training-weight` (per always-use-worktree rule).
- Spec A (identity user-roles, the upstream `UserRoleChanged` producer) fully landed on
  `main` (A0-A3 merged 2026-05-30). Spec B can consume the event contract it shipped.
- No relevant stashes or untracked files to recover.

## Event log

(entries appended chronologically by the cron)
