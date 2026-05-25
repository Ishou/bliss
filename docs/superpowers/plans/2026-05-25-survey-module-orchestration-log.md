# Survey Module — Orchestration Log

Append-only log of decisions the orchestrator made during the 9-PR survey-module rollout. For human review when convenient; nothing here requires an action unless flagged "**ACTION:**".

## Standing decisions (set at orchestration start)

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on LGTM + green checks | User's prior in-session override of dispatch skill's "user merges" default, carried forward per memory note `feedback-cron-orchestration-cadence` |
| Polling cadence | 120 s (every 2 min, via `*/2 * * * *` CronCreate) | Standing user directive: "verifying the PR does not take that much effort, you could poll every 2 minutes" |
| Continuity | `CronCreate` durable routine (note: `durable` flag is currently silently ignored by the runtime; cron is effectively session-only) | Same standing directive |
| Reviewer/fixer hang detection | If a PR has CI done + no auto-review within 15 min → dispatch a manual reviewer; if findings exist + no fixer activity within 15 min → dispatch a manual fixer | Standing directive: "sometimes the reviewer/fixer loop stops before the LGTM, if that's the case, be sure to dispatch a reviewer or fixer manually to avoid being stuck" |
| Fix-cycle budget per phase | 3 | Matches the `claude-code-review` skill's default; standing user grant for retries |
| Phase order | Strictly sequential per plan; no stacked PRs in this rollout | Each phase depends on the previous (schema-first: ADR → openapi → domain → application → infrastructure → api → worker+chart → frontend → nats-live) |
| Agent isolation | `isolation: "worktree"` | Dispatch skill mandate; never run agent work in main checkout |
| Agent type | `general-purpose` (with skill pointer to `frontend` for Phase 8) | Backend phases are Kotlin/Ktor — implementer reads CLAUDE.md + ADRs directly; frontend phase needs the `frontend` skill loaded |
| Commit types in use | `feat`, `fix`, `chore`, `refactor`, `test`, `docs` | Plan respects `.commitlintrc.yml` allowlist |
| Cap-override authority | Pre-flagged for PR4 (application + filters port + CSV codec) and PR7 (worker + chart + db-chart + bootstrap); standing maintainer authorization in the procedure file's named section | Per memory note `feedback-standing-cap-override`; each PR's body must cite the procedure file's "Standing maintainer authorization" section |
| Escalation trigger | 3 failed fix-cycles on any PR | Stops the chain, posts ACTION to this log, self-deletes the cron |
| Impersonation | Orchestrator never posts `@Ishou`-authored comments; cites the in-repo standing authorization instead | Per memory note `feedback-no-impersonation-comments` |

## Pre-orchestration state

- **Spec + plan + procedure on `main`:** spec commits `8f805839` and `fa449735` (design doc with anon mode), plan commit `8c311054` (9-PR implementation plan). All on `main` at orchestration-start time. The orchestration procedure + this log will land on their own small PR (branch `docs/survey-module-orchestration`).
- **No prior survey-module branches on remote.** First implementer dispatch will create `feat/survey-adr`.
- **Pre-existing local workspace mods (NOT touched by this rollout):**
  - `M .claude/skills/clue-ai/SKILL.md` — stays modified locally, untouched by the orchestration.
  - Untracked: `.DS_Store`, `.claude/.DS_Store`, `.playwright-mcp/`, `data/.DS_Store`, `data/eval/.DS_Store`, `data/eval/production/.DS_Store`, `infra/.DS_Store` — macOS / browser-tooling cruft; not orchestrator-introduced.

  None of the above is blocking. The orchestrator works in `.claude/worktrees/` isolated checkouts, so local mods are unaffected.

- **`bliss-clue-ai` repo:** lives at `../bliss-clue-ai` (sibling). Out-of-scope for this rollout — manual CSV exchange at training boundaries per spec §9.3. No orchestration coordination needed across repos.

## Event log

(Entries appended in chronological order. Each entry has timestamp, event, decision/outcome.)

### 2026-05-25 — orchestration start

- Spec + plan already on `main` via three direct commits (`8f805839`, `fa449735`, `8c311054`).
- Orchestration procedure (`2026-05-25-survey-module-orchestration-procedure.md`) and this log added on branch `docs/survey-module-orchestration`.
- Cron created via `CronCreate` with the `*/2 * * * *` schedule and the bootstrap prompt from the procedure file's tick procedure.
- Next: push orchestration branch, open small PR, let CI clear, then cron's first tick dispatches Phase 1 (ADR-0056) once the orchestration PR is on origin (the procedure file just needs to be readable from `origin/<branch>` for the cron to find it).

### Cron creation

- `2026-05-25` · cron `7623bd90` created (`*/2 * * * *`, session-only — runtime ignores `durable: true` per dispatch skill note). First tick will look up Phase 1 PR (none yet), dispatch the `feat/survey-adr` implementer.
- Orchestration PR: https://github.com/Ishou/wordsparrow/pull/611.

### Tick 1 — 2026-05-25

- `2026-05-25T<tick>` · phase `1 (feat/survey-adr)` · `dispatched-implementer` · Agent launched in worktree to write ADR-0056 + open PR. No prior PR existed. Phases 2-9 skipped (sequential).
- `2026-05-25T<tick2>` · phase `1 (feat/survey-adr)` · `waiting-implementer` · no PR yet; implementer agent from tick 1 still running. No action.
- `2026-05-25T<tick3>` · phase `1 (feat/survey-adr)` · `waiting-ci` · PR #612 OPEN; build/spectral/claude-review IN_PROGRESS, no reviews. No action.
