# Mobile Custom Keyboard — Orchestration Log

Append-only log of decisions the orchestrator made during the 6-PR rollout. For human review when convenient; nothing here requires an action unless flagged "**ACTION:**".

## Standing decisions (set at orchestration start)

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on LGTM + green checks | User's explicit override of dispatch skill's "user merges" default |
| Polling cadence | 120 s (every 2 min, via `*/2 * * * *` CronCreate) | User directive: "verifying the PR does not take that much effort, you could poll every 2 minutes" |
| Continuity | `CronCreate` durable routine | User chose CronCreate over manual re-invocation; durable persists across session restarts |
| Reviewer/fixer hang detection | If a PR has CI done + no auto-review within 15 min → dispatch a manual reviewer; if findings exist + no fixer activity within 15 min → dispatch a manual fixer | User directive: "sometimes the reviewer/fixer loop stops before the LGTM, if that's the case, be sure to dispatch a reviewer or fixer manually to avoid being stuck" |
| Fix-cycle budget per phase | 3 | Matches the `claude-code-review` skill's default; user said retries are OK to decide |
| Phase order | Strictly sequential per plan | Each phase depends on the previous; no parallelisation possible |
| Phase 2 + 3 coordination | Stack (Phase 3 branches off Phase 2); merge 2 then 3 once both LGTM + green | Plan calls these out as "must merge together" — stacked is cleaner than holding 2 open |
| Agent isolation | `isolation: "worktree"` | Dispatch skill mandate; never run agent work in main checkout |
| Agent type | `general-purpose` (with skill pointer to `frontend`) | All work is frontend; the `frontend` skill encodes ADR-0002 §4 + Panda/Vitest conventions |
| Commit types in use | `refactor`, `feat`, `test`, `chore`, `docs` | Plan respects `.commitlintrc.yml` allowlist |
| Escalation trigger | 3 failed fix-cycles on any PR | Stops the chain, posts to this log, notifies user via ScheduleWakeup |

## Pre-orchestration state

- **Spec + plan branch:** `docs/mobile-keyboard-spec` (3 commits ahead of `origin/main`).
- **User's pre-existing workspace mods:** stashed as `stash@{0}: preexisting-workspace-modifications` (modifications to `frontend/tests/grid-input.test.tsx` and `infra/nats/templates/stream-bootstrap-job.yaml` on the `feat/game-lobby-race-free-writes` branch). **ACTION (at end of run):** remind user to `git checkout feat/game-lobby-race-free-writes && git stash pop stash@{0}` to recover them.
- **Untracked dirs at start (not orchestrator-introduced):** `.playwright-mcp/`, `grid/worker/src/main/resources/words/`.

## Event log

(Entries appended in chronological order. Each entry has timestamp, event, decision/outcome.)

### 2026-05-21 — orchestration start

- Spec + plan committed to `docs/mobile-keyboard-spec` (3 commits: `1ace411c`, `e2ed76e4`, `506efc10`).
- Squash-on-merge will collapse the 3 commits into one on `main`; left as-is (no rebase).
- Orchestration log file added (this file) as the 4th commit on the spec branch.
- Next: push branch, open spec PR, wait for auto-reviewer + user LGTM + green checks before dispatching Phase 1.
