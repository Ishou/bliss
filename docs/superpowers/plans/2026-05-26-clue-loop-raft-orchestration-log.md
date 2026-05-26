# Clue-loop RAFT — Orchestration Log

Append-only log of decisions the orchestrator made during this rollout. For human review.

## Standing decisions

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on LGTM + green CI | User grant 2026-05-26 |
| Polling cadence | 120 s (every 2 min via `*/2 * * * *` CronCreate) | Memory `feedback-cron-orchestration-cadence` |
| Fix-cycle budget per phase | 3 | Dispatch-skill default |
| Phase order | Parallel; no inter-PR code dependencies | All three touch disjoint files |
| Cap-override expectation | NONE | All three PRs estimated under 300 LOC |
| Single-rater training | `extract_winners.py` filters `r.user_id = <maintainer>` | Maintainer decision 2026-05-26 |
| Algorithm choice | RAFT, not DPO | Maintainer decision 2026-05-26 after framing pushback; DPO deferred to ≥500 preferences |

## Pre-orchestration state

- Branch: `chore/clue-loop-tooling-plan` (plan + procedure + log).
- No uncommitted code changes.
- UX wave (`post-survey-followups`) is in flight: PRs #649, #650, #651, #652 OPEN; B (prerender) still running. The clue-loop wave touches `modal_jobs/`, `scripts/clue_generation/`, `docs/runbooks/`, `data/lora/` — **zero overlap** with the UX wave's `frontend/` + `survey/` + `identity/` paths. No merge conflicts expected.
- A second plan wave (`sondage-simplification`) will be drafted in parallel; it heavily rewrites `RatingCard.tsx` and depends on UX wave fully merging first.

## Event log

(entries appended chronologically by the cron)
