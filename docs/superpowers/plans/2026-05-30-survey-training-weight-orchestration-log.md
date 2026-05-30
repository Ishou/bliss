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

### 2026-05-30 — COMPLETE: Spec B fully merged to `main`

All seven phase PRs merged in dependency order:

| Phase | PR | Branch | Notes |
|---|---|---|---|
| B0 | #697 | `docs/survey-training-weight` | Bundle: spec + plan + procedure + log |
| B1 | #699 | `feat/survey-training-weight-migration` | V8 migration + `maintainer_roles` table |
| B2 | #700 | `feat/survey-gold-window-policy` | `GoldWindowPolicy` (inclusive cutoff) |
| B3 | #701 | `feat/survey-training-weight-repos` | `updateTrainingWeight` + `MaintainerRoleRepository` + Pg adapters |
| B4 | #702 | `feat/survey-recompute-use-case` | `RecomputeTrainingWeightUseCase` (3 triggers, out-of-order guard) |
| B5 | #704 | `feat/survey-role-changed-consumer` | `UserRoleChangedConsumer` mirroring UserDeleted slice |
| B6 | #705 | `feat/survey-training-weight-wiring` | Trigger-2 stamp + UserDeleted role erasure + api/worker/chart wiring |

Fix cycles consumed (all bot auto-fixer, no escalation):
- B1: `truncateAll` missing `maintainer_roles` — auto-fixed.
- B3: `(Spec B)` task-reference in a KDoc — auto-fixed (comment-rot rule).
- B5: ADR-0049 durable-registry row missing — auto-fixed (`b3fb96ce`).
- B6 cycle 1: `toDoubleOrNull()` swallowing invalid `SURVEY_GOLD_MULTIPLIER` — auto-fixed (`d007425e`, fail-fast on bad env).
- B6 cycle 2: two 2-line `#` comment blocks in `values.yaml` — auto-fixed (`93ffb5c7`, collapsed to one-liners citing ADR-0056).
- Commitlint PascalCase-subject gotcha hit B2/B4 (plan literal subjects); each amended to lowercase first word.

Cap-override (ADR-0001 §4): invoked on B6 from first push (coherent integration layer; splitting would leave half-wired constructors that don't compile). No other phase exceeded the target.

Orchestration cron `dba7eb0f` deleted on completion.

**Remaining in the gold-weighting rollout (NOT part of Spec B):** Spec C (`ExportDatasetUseCase` reads/emits the frozen `training_weight`) and Spec D (wire survey export into `build_modal_corpus.py`). Each needs its own spec → plan → orchestration.
