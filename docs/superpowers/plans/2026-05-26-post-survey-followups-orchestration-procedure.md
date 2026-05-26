# Post-survey follow-ups — Orchestration Procedure (cron-driven)

Self-contained procedure the cron-fired orchestrator follows on every tick. This rollout is a single wave (5 PRs in parallel, no inter-PR dependencies), so the procedure is simpler than the survey-module's 9-phase variant.

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7 days).

**CWD:** repo root. `cd "$(git rev-parse --show-toplevel)"` if needed.

**State source of truth:** `docs/superpowers/plans/2026-05-26-post-survey-followups-orchestration-log.md` (append-only event log).

## Source documents

- **Plan:** `docs/superpowers/plans/2026-05-26-post-survey-followups.md` — the five PRs, their files, their ADR pre-reads.
- **Dispatch skill:** `.claude/skills/dispatch/SKILL.md` — the canonical playbook (auto-fix loop, §6a cycle, branch + commit conventions).
- **CLAUDE.md** — engineering rules with operational bite, esp. "ADR pre-read by path" + "Comments document non-obvious WHY".

## Standing maintainer authorizations (cite when invoking)

- **400-line cap:** soft target, not hard. PR may invoke override in its body with a defensible reason not to split. None of the five PRs in this rollout are expected to need the override (max is ~100 LOC).
- **Autonomy:** decide and log, don't ask unless truly blocking. Apply with candor on framing per memory `user-autonomy-preference`.
- **CLAUDE.md self-edits:** allowed without per-edit approval, for codifying maintainer instructions / recurring-incident rules / bounded-contexts table sync. (Not relevant to this rollout's scope.)
- **No `@Ishou`-impersonating comments.** Standing rule from prior rollouts.

## Wave map

Single wave. All five PRs dispatch simultaneously and merge independently as each goes green.

| PR  | Branch                                       | Title prefix                                                                |
|-----|----------------------------------------------|-----------------------------------------------------------------------------|
| α   | `feat/frontend-confidentialite-sondage-rgpd` | `feat(frontend-confidentialite): document sondage RGPD posture`            |
| β   | `fix/frontend-route-flash-sondage-compte`    | `fix(frontend-routes): gate /sondage + /compte on auth-session hydration`  |
| γ   | `test/survey-konsist-cors-wildcard`          | `test(survey-api): Konsist guard for credentialed-CORS wildcard predicate` |
| δ   | `chore/ci-cors-preflight-smoke`              | `chore(ci-deploy): post-deploy CORS preflight smoke per ADR-0048`          |
| ε   | `fix/survey-identity-client-cookie-name`     | `fix(survey-infrastructure): correct session cookie name to __Secure-`     |

Each PR's spec lives in `docs/superpowers/plans/2026-05-26-post-survey-followups.md` under the matching `## PR <letter>` section.

## Tick procedure

### 1. Refresh local view

```sh
cd "$(git rev-parse --show-toplevel)"
git fetch origin --quiet
```

### 2. Walk every PR in the wave map

For each row:

```sh
gh pr list --state all --head <branch> --json number,state,reviewDecision,mergeStateStatus,mergeable,statusCheckRollup -L 1
```

- `state: "MERGED"` → that slot is done; nothing to do this tick.
- `state: "OPEN"` → assess (step 3).
- `state: "CLOSED"` (not merged) → **escalation:** append to log "**ACTION:** PR <#> for <branch> was closed without merging. Slot paused." Move on; do NOT redispatch automatically.
- No PR found → if the implementer agent hasn't been dispatched yet for this slot, dispatch it (step 4).

### 3. Open PR — assess and act

```sh
gh pr view <pr#> --json number,state,reviewDecision,mergeStateStatus,mergeable,reviews,statusCheckRollup
gh pr checks <pr#>
```

Decision tree (mirrors `.claude/skills/dispatch/SKILL.md` §6a):

- **CI red** AND no auto-fixer cycle running → wait one tick; the auto-fixer (`claude-code-review.yml`) usually kicks in. If still red after 10 minutes, dispatch a targeted fixer agent.
- **`claude-review` IN_PROGRESS** → wait.
- **`claude-review` posted a "Findings" comment AND no fix has been pushed since** → dispatch a fixer agent with the findings inlined into its prompt. Apply the 3c-loop-terminator rule: if the *same* finding text recurs across two cycles, dispatch a minimal targeted fixer (don't loop).
- **`claude-review` LGTM (no outstanding findings) AND all CI green** → merge with squash:
  ```sh
  gh pr merge <pr#> --squash --auto
  ```
  Append "MERGED <pr#>" to the log.
- **Mergeable = CONFLICTING** → dispatch a rebase fixer agent (it can rebase against latest main and force-push-with-lease).
- **3c-loop-terminator triggered** on a cap-only finding → dispatch a body-edit fixer that adds the cap-override citation to the PR body (no code change).

### 4. Dispatch an implementer

Use the `Agent` tool with `isolation: "worktree"` and `run_in_background: true`. Prompt structure follows `.claude/skills/dispatch/SKILL.md` §"Self-contained prompts" (11-step template), including:

- Step 3 (ADR pre-read): paste `scripts/adr-context.sh <touched-paths>` output verbatim under "MANDATORY READING".
- Step 8 (comment-style preflag): paste the snippet from the dispatch skill's "Comment-style preflag" section.
- Step 10 (CI auto-fix loop): paste the snippet from the dispatch skill's "CI auto-fix loop" section.

Append to the log: "DISPATCHED PR <letter> agent <id> on branch <branch>".

### 5. Cleanup

When all five PRs are MERGED:

1. Append "WAVE COMPLETE 2026-05-26 post-survey follow-ups" to the log.
2. Append "ACTION: maintainer should manually trigger `deploy-api-k8s.yml context=survey` so PR ε's cookie fix reaches prod and authenticated routes start working."
3. `CronDelete` this cron.
4. Exit.

## Failure modes

- **Implementer agent reports it was blocked** (e.g. couldn't reproduce the flash, ADR pre-read empty): append the agent's report verbatim to the log; do NOT auto-redispatch. The next tick is a human's call.
- **The `gh` CLI returns an unauthenticated/timeout error:** retry once, then log "ACTION: gh CLI auth issue, orchestration paused." `CronDelete` self.
- **Two consecutive ticks with no progress on any slot:** post a single "all slots quiet — waiting on review or maintainer attention" entry. Don't spam the log.

## Done condition

Five PRs merged AND the deploy smoke step from PR δ has run successfully on the next survey deploy (verifies the prevention layer actually fires in CI).
