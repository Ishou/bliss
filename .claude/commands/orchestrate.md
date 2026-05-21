---
description: Bootstrap autonomous cron-driven multi-PR orchestration for a feature. Generates the procedure + log files, sets up the 2-minute cron, drops the watchdog script.
---

# /orchestrate — autonomous rollout bootstrap

Sets up the cron-driven autonomous orchestration mode for a feature with an existing spec + plan. Reads the dispatch skill's "Cron-driven autonomous orchestration" section as the source of truth. After this command runs, the cron polls every 2 minutes, dispatches implementer agents phase-by-phase, runs the auto-fixer loop, dispatches manual reviewer/fixer when the loop hangs, and merges PRs when CI is green + bot LGTM. Maintainer remains the escalation backstop via the log file.

## Invocation

The user typically invokes one of:

- `/orchestrate <plan-file-path>` — bootstrap from an existing spec + plan.
- `/orchestrate <feature-slug>` — find `docs/superpowers/plans/<date>-<feature-slug>.md` (most recent date wins).
- `/orchestrate` (no args) — pick up the most recently modified plan file under `docs/superpowers/plans/` that doesn't already have a `-orchestration-procedure.md` companion.

## Procedure

Before doing anything, invoke the `dispatch` skill to load the patterns.

### 1. Locate the spec and plan

Read the plan file the user named (or auto-located). Verify the matching spec exists under `docs/superpowers/specs/`. Both must be on `origin/main` OR on a spec branch awaiting merge.

If the spec PR is still open, the orchestration runs against the spec branch first (the cron reads its source-of-truth files from `origin/<spec-branch>` until they merge to `main`).

### 2. Derive the phase map

From the plan, extract the phase sequence: branch name, base (`main` or stacked), PR title prefix, brief scope per phase. The plan should already have a "PR sequencing" or "Phases" section; if it doesn't, ask the user to clarify the sequence before continuing.

For stacked phases, label them explicitly in the phase map.

### 3. Write the orchestration procedure file

Path: `docs/superpowers/plans/<date>-<slug>-orchestration-procedure.md`. Use this skeleton:

```markdown
# <Feature> — Orchestration Procedure (cron-driven)

Cron-fired tick procedure for the <feature> multi-PR rollout.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7 days; recreate if rollout exceeds 7 days).

**CWD:** run from the repo root (`cd "$(git rev-parse --show-toplevel)"`).

**State source of truth:** `docs/superpowers/plans/<date>-<slug>-orchestration-log.md`.

## Standing maintainer authorization (recorded <YYYY-MM-DD>)

<verbatim grant from the user, if any; cite-able by §6a reviewer>

## Phase map

| Phase | Branch | Base | PR title prefix |
|---|---|---|---|
| ... | ... | ... | ... |

## Tick procedure

<copy the decision-tree from the dispatch skill's "Cron-driven autonomous orchestration > Tick decision tree" section, customised with this rollout's phase map>

## Implementer agent prompt template

<copy from the dispatch skill's "Implementer agent dispatch" section; fill the BRACKETS for each phase as the cron dispatches it>

## Manual reviewer dispatch prompt
## Manual fixer dispatch prompt

<copy from the dispatch skill>

## Logging format

<copy from the dispatch skill>
```

The dispatch skill at `.claude/skills/dispatch/SKILL.md` has the canonical templates; this command's job is to instantiate them with the feature-specific phase map.

### 4. Write the orchestration log file

Path: `docs/superpowers/plans/<date>-<slug>-orchestration-log.md`. Initial content:

```markdown
# <Feature> — Orchestration Log

Append-only log of decisions the orchestrator made during this rollout. For human review when convenient.

## Standing decisions

| Decision | Value | Rationale |
|---|---|---|
| Merge authority | Orchestrator merges on LGTM + green CI | User grant in-session |
| Polling cadence | 120 s (every 2 min via `*/2 * * * *` CronCreate) | ... |
| Continuity | `CronCreate` (session-only in practice; durable flag is ignored by the runtime) | ... |
| Fix-cycle budget per phase | 3 | Matches dispatch-skill default |
| Phase order | Strictly sequential per plan; stacked phases noted in the map | |
| Escalation trigger | 3 failed fix-cycles on any PR | Stops chain; logs ACTION entry |

## Pre-orchestration state

<note any uncommitted local mods, stashes, untracked files relevant to recovery>

## Event log

(entries appended chronologically by the cron)
```

### 5. Commit the procedure + log

```sh
git checkout -b docs/<slug>-orchestration
git add docs/superpowers/plans/<date>-<slug>-orchestration-procedure.md docs/superpowers/plans/<date>-<slug>-orchestration-log.md
git commit -s -m "docs(<scope>): <slug> orchestration procedure + log

Cron-driven autonomous procedure for the <feature> rollout. Encodes the
phase map, tick decision tree, and standing maintainer authorization
from the in-session grant.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
git push -u origin docs/<slug>-orchestration
gh pr create --base main --title "docs(<scope>): <slug> orchestration procedure + log" --body "<short PR body>"
```

The procedure + log commit can go on its own small PR, OR be folded into the spec/plan PR if it's still open. Use the simpler path.

### 6. Set up the watchdog (optional)

Drop a bash script in `~/<slug>-orchestration-watchdog.sh` that polls GitHub for activity stalls. Template:

```bash
#!/usr/bin/env bash
set -u
REPO="<owner>/<repo>"
POLL_INTERVAL_SECS=600
STALL_THRESHOLD_SECS=1200
SEARCH_BRANCHES=(<list of phase branches>)
notify() {
  if command -v osascript >/dev/null 2>&1; then
    osascript -e "display notification \"$1\" with title \"<feature> orchestration\""
  else
    printf '\a[WATCHDOG] %s\n' "$1" >&2
  fi
}
# ... rest from the mobile-keyboard rollout's watchdog as a reference ...
```

Tell the user the script path and how to start it: `bash ~/<slug>-orchestration-watchdog.sh &`.

### 7. Create the cron

```
CronCreate({
  cron: "*/2 * * * *",
  recurring: true,
  durable: true,  // note: silently ignored by the runtime; cron is session-only
  prompt: <bootstrap prompt below>
})
```

Bootstrap prompt:

```
Bliss <feature> orchestration tick.

CWD: $(git rev-parse --show-toplevel)

Read and follow the procedure at:
  docs/superpowers/plans/<date>-<slug>-orchestration-procedure.md

If that file is not on `origin/main` yet, read it from `origin/<branch-with-procedure>` via:
  git fetch origin --quiet && git show origin/<branch>:<path>

Execute the tick procedure exactly as written: refresh remote state, walk the phase map, assess the active PR, take at most one action (merge / dispatch / wait / escalate), then stop. Do not improvise outside the procedure.

If the procedure file is missing entirely or its content is unparseable, stop and post a single comment on the spec PR with the issue.

Be concise in your tick output — one-line decision per phase examined, plus the action taken (if any). Don't re-explain the procedure back to me.
```

### 8. Report to user

After all of the above:

- Confirm: orchestration procedure + log committed (link to PR).
- Confirm: cron `<id>` created, polling every 2 min.
- Surface: watchdog script path (if dropped).
- Surface: any pre-orchestration recovery items (stashed mods, untracked files).
- Surface: ACTION items the maintainer might need to take manually (e.g., approving a cap-override comment on a docs PR that exceeds 400 lines, if applicable).

## Constraints

- Do NOT bootstrap orchestration for a feature without an existing spec + plan. If they're missing, point the user at `superpowers:brainstorming` and `superpowers:writing-plans`.
- Do NOT create the cron before the procedure file is committed (or at least pushed to a branch on origin). The cron's first tick must be able to read the procedure.
- Do NOT impersonate the maintainer in any PR comment or commit message. See the dispatch skill's "Don'ts" list.

## When NOT to invoke

- Single-PR features. Just dispatch one agent directly.
- Features that don't have a clear phase sequence yet. Run brainstorming + writing-plans first.
- Tight time pressure where the maintainer wants to drive PR-by-PR manually. Autonomous mode is for hands-off multi-day rollouts.
