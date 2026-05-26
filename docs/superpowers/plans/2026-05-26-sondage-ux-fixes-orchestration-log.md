# Sondage UX fixes — Orchestration Log

Append-only log of decisions the orchestrator made during this rollout. For human review.

## Standing decisions

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on LGTM + green CI | Per `user-autonomy-preference` |
| Polling cadence | 120 s | Established cadence |
| Fix-cycle budget per phase | 3 | Dispatch-skill default |
| Phase order | All 5 PRs parallel | No inter-PR deps |
| Escalation trigger | 3 failed fix cycles OR 2 identical-finding cycles | Loop-terminator rule |

## Pre-orchestration state

- Last wave (`2026-05-26-post-survey-followups`) merged 5 PRs (#643-#647).
- Bug reports gathered from maintainer on 2026-05-26 ~14:00Z. Five items in scope (A-E); "only 8 mots cycle" deferred to maintainer-driven prod DB inspection (separate, not in this wave).
- Prod survey-api on commit `58b0cb8` (includes the ε cookie-name fix); the 401 in Bug 6 is therefore NOT the cookie-name discrepancy — different root cause to be diagnosed in PR E.

## Event log

(entries appended chronologically by the cron)
