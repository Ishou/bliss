# Survey Campaign Lock — Orchestration Log

Append-only log of decisions the orchestrator made during this rollout. For human review when convenient.

## Standing decisions

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on LGTM + green CI | Standing memory + in-session re-grant ("auto-merge cron is the default") |
| Polling cadence | 120 s (every 2 min via `*/2 * * * *` CronCreate) | Standing memory: cron-orchestration-cadence |
| Continuity | `CronCreate` (session-only in practice; the `durable` flag is ignored by the runtime) | Per dispatch-skill autonomous-mode section |
| Fix-cycle budget per phase | 3 | Matches dispatch-skill default |
| Phase order | Strictly sequential A → B → C; D and E parallel after C | Plan §12 |
| Cap override | Orchestrator may invoke proactively without escalating | Standing memory: cap-override-short-circuit; ADR-0001 §4 2026-05-25 soft-target amendment |
| Escalation trigger | 3 failed fix-cycles on any PR, OR 3c-loop-terminator on non-cap finding, OR CLOSED-not-merged PR | Procedure §5 |

## Pre-orchestration state

Snapshot at 2026-05-30 (bootstrap commit):

- Branch in flight: `docs/survey-campaign-lock-spec` (carries spec, plan, ADR-0059, INDEX update, procedure, log, TODO.md).
- Untracked / modified files in main checkout that are NOT part of this rollout:
  - `M scripts/clue_generation/pipeline_v2/run_pipeline.py` — pre-existing modification, unrelated to campaign lock.
  - `?? data/curated/round_{5,6,8,9}_pos_lemmas.csv` — pre-existing untracked corpus files.
  - `?? modal_jobs/0{2,3b,3c,4}_*command_r.py` — pre-existing untracked Modal jobs.
  - `?? scripts/clue_generation/build_{dpo_pairs,pos_lemmas}.py` — pre-existing untracked scripts.
  - `?? .DS_Store`, `?? .playwright-mcp/` — local-only noise.
- Existing stashes (NOT to be touched by this rollout):
  - `stash@{0}` On fix/frontend-keyboard-touch-action-allow-pull-to-refresh: tick-temp-2
  - `stash@{1}` On feat/frontend-keyboard-minimap-parity: tick-temp
  - `stash@{2}` WIP on feat/frontend-mobile-keyboard-polish
  - `stash@{3}` On feat/game-lobby-race-free-writes: preexisting-workspace-modifications
  - `stash@{4}` On feat/oauth2-identity-begin-login: wip identity oidc files

The agents work in `.claude/worktrees/agent-<id>/` isolation; the main checkout's untracked-list cannot affect their branches.

## Event log

(entries appended chronologically by the cron)

- 2026-05-30T<HH:MM>Z BOOTSTRAP committed (spec + plan + ADR-0059 + INDEX + procedure + log + TODO) on `docs/survey-campaign-lock-spec`.
