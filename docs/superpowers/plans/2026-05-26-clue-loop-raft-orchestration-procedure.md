# Clue-loop RAFT — Orchestration Procedure (cron-driven)

Cron-fired tick procedure for the clue-loop RAFT tooling rollout (`2026-05-26-clue-loop-raft.md`).

**Cron schedule:** `*/2 * * * *` (every 2 minutes; recreate if rollout exceeds 7 days).

**CWD:** run from the repo root (`cd "$(git rev-parse --show-toplevel)"`).

**State source of truth:** `docs/superpowers/plans/2026-05-26-clue-loop-raft-orchestration-log.md`.

## Standing maintainer authorization (recorded 2026-05-26)

- 400-line cap may be invoked by the orchestrator with justification (no cap-override expected for this wave; all PRs estimated under 300 LOC).
- Bot LGTM + green CI = merge authority for the orchestrator.
- Round-0 training is **single-rater** (maintainer's user_id only); `extract_winners.py` filters on `r.user_id` accordingly.
- DPO is **deferred**; this wave ships RAFT only.

## Phase map

| Phase | Branch                           | Base          | PR title prefix                                                      |
|-------|----------------------------------|---------------|----------------------------------------------------------------------|
| α     | `feat/clue-loop-04-generate`     | `origin/main` | `feat(clue-gen-modal): 04_generate.py palier for round-N candidates` |
| β     | `feat/clue-loop-extract-winners` | `origin/main` | `feat(clue-gen-tools): extract_winners.py + data/external/ guard`    |
| γ     | `feat/clue-loop-05-raft`         | `origin/main` | `feat(clue-gen-modal): 05_raft_finetune.py + clue-loop runbook`      |

All three are independent (no code-level dependencies); can run in parallel.

## Tick procedure

Refer to `.claude/skills/dispatch/SKILL.md` "Cron-driven autonomous orchestration > Tick decision tree" for the canonical flow. Customised for this rollout:

1. `git fetch origin --quiet`.
2. For each phase in `[α, β, γ]`:
   - If the phase branch does not exist on `origin`: dispatch implementer (one assistant turn, `isolation: "worktree"`, `run_in_background: true`).
   - If the PR exists and `gh pr checks <pr>` shows blocking checks `IN_PROGRESS`: wait (no action this tick).
   - If checks failed and fix-cycle budget not exhausted: dispatch fixer (one assistant turn, manual-fixer template).
   - If checks green AND a bot `LGTM` comment exists OR `claude-review` is informational-only: merge via `gh pr merge --squash --auto`.
   - If checks green for >15 min with no review action: dispatch the manual reviewer agent.
3. Log every action with timestamp + decision rationale to the log file.
4. At most ONE action per tick. Stop after the first action.

## Implementer agent prompt template

```
You are an implementation agent. PR <phase> of the clue-loop RAFT tooling wave.

Your full spec is in `docs/superpowers/plans/2026-05-26-clue-loop-raft.md` under
`## PR <phase> — <title>`. Read it in full.

# 1. MANDATORY READING — before any code edit

Run:
  scripts/adr-context.sh <files-from-the-spec>

Read every ADR body in full. They are binding rules for the paths you touch.

# 2. Spec

<paste the entire PR section from the plan file — file list, ADR pre-read,
spec, success criteria, risks>

# 3. Comment-style pre-flag

Comments document non-obvious WHY, in one line. Default to no comment.
Single-line, non-obvious why only. NO multi-paragraph docstrings, NO multi-line
comment blocks. NO PR/task/caller references.

# 4. How to ship

- Branch: <from the phase map above> (from `origin/main`).
- Validation: <PR-specific test commands>.
- Commit: <prefix from phase map>. DCO sign-off (`git commit -s`). Lower-case
  first word after colon. Subject < 100 chars.
- PR title: same as commit subject. PR body: 1-2 sentence summary +
  Test plan (checkbox list) + any non-obvious decisions.

# 5. Constraints

- ADR-0001 §4 cap: under 400 LOC.
- Conventional commits, single bounded-context scope, DCO.
- No new dependencies without an ADR.

# 6. CI auto-fix loop (after push)

Wait ~60s after pushing, then poll `gh pr checks <pr#>` every ~30s.
Blocking checks: build, submit-gradle, commitlint, branch-name, dco,
gitleaks, dependency-review, regen-and-diff, spectral.
Informational (don't block, don't auto-fix): claude-review, CodeQL.
Budget: 3 fix passes max.

# 7. Report back (≤ 250 words)

Branch URL, PR number + URL, line counts, test/build outputs, decisions,
blockers if any.
```

## Manual reviewer dispatch prompt

```
You are a code reviewer. PR #<n> opened by the clue-loop RAFT orchestrator.

Review against:
- The plan file `docs/superpowers/plans/2026-05-26-clue-loop-raft.md`,
  specifically the PR <phase> section.
- The ADRs listed in that section's "Mandatory ADR pre-read".
- CLAUDE.md's comment-style and 400-line cap rules.

Post one consolidated review comment on the PR via `gh pr review --comment`.
If the PR is good to merge, post a comment starting with `LGTM` so the
orchestrator can squash-merge on its next tick.

Be concise. Cite file:line for every finding.
```

## Manual fixer dispatch prompt

```
You are a fixer agent. PR #<n> has CI failures after the implementer agent
hit its 3-pass auto-fix budget.

Review:
- The plan file's PR <phase> spec.
- The failing check logs via `gh pr view <n>` and `gh run view <run-id> --log-failed`.

Fix the underlying cause, NOT the symptom. Commit the fix on the same branch,
push, and report:
- Root cause (1-2 sentences).
- Fix description.
- New PR check status (run `gh pr checks <n>` after push + a brief wait).

If the failure is non-trivial and points to a real design problem, escalate
by posting a comment on the PR explaining the issue and STOP. Do not force
through a workaround.
```

## Logging format

Append to the log file with each tick that takes an action:

```markdown
### YYYY-MM-DD HH:MM UTC — <ACTION | NOTE> — phase <α/β/γ>

- **Decision**: <what was decided>
- **Rationale**: <why>
- **Artifacts**: <PR #, branch, agent ID, etc.>
```

Ticks that took no action don't need log entries (reduce noise).

## Cap-override short-circuit (proactive)

For this wave: NOT EXPECTED. All three PRs estimated under 300 LOC. If any
agent reports a cap exceedance, the orchestrator should evaluate whether
to invoke the override OR ask the agent to split (runbook can absorb the
split into a follow-up PR cheaply for γ).
