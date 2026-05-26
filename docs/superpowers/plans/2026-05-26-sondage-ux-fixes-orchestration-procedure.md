# Sondage UX fixes — Orchestration Procedure (cron-driven)

Cron-fired tick procedure for the 2026-05-26 sondage-UX-fixes rollout. Single wave (5 PRs in parallel, no inter-PR dependencies).

**Cron schedule:** `*/2 * * * *` (every 2 minutes; auto-expires after 7 days; session-only).

**CWD:** repo root (`cd "$(git rev-parse --show-toplevel)"`).

**State source of truth:** `docs/superpowers/plans/2026-05-26-sondage-ux-fixes-orchestration-log.md` (append-only).

## Source documents

- **Plan:** `docs/superpowers/plans/2026-05-26-sondage-ux-fixes.md`
- **Dispatch skill:** `.claude/skills/dispatch/SKILL.md`
- **CLAUDE.md** — engineering rules (ADR pre-read, comment-style preflag, no-cap-bypass-without-justification).

## Standing maintainer authorizations

- 400-line cap: soft target. None of A-E expected to exceed; if E grows for cookie-policy ripple-tests, may invoke override with justification.
- Autonomy: decide and log; push back on framing/scope per `user-autonomy-preference` memory.
- CLAUDE.md self-edits permitted (not relevant to this rollout's scope).
- No `@Ishou`-impersonating comments.

## Wave map

Single wave. All 5 PRs dispatch simultaneously, merge independently.

| PR  | Branch                                       | Title prefix                                                                         |
|-----|----------------------------------------------|--------------------------------------------------------------------------------------|
| A   | `feat/frontend-nav-sondage`                  | `feat(frontend-nav): add /sondage to AppHeader main nav`                            |
| B   | `fix/frontend-prerender-noindex-routes`      | `fix(frontend-prerender): prerender /sondage + /compte despite noindex`             |
| C   | `fix/frontend-sondage-labels`                | `fix(frontend-sondage): French labels for pos/categorie/style + difficulté wording` |
| D   | `refactor/frontend-flagpicker-ark-ui`        | `refactor(frontend-sondage): FlagPicker uses Ark UI Select`                         |
| E   | `fix/survey-compte-patch-401`                | `fix(survey-...): reproduce + remediate /compte PATCH 401`                          |

Per-PR specs live in `docs/superpowers/plans/2026-05-26-sondage-ux-fixes.md` under the matching `## PR <letter>` sections.

## Tick procedure

### 1. Refresh

```sh
cd "$(git rev-parse --show-toplevel)"
git fetch origin --quiet
```

### 2. Walk the wave map

For each row:

```sh
gh pr list --head <branch> --state all --json number,state,mergeStateStatus,reviewDecision -L 1
```

- `MERGED` → skip.
- `OPEN` → assess (step 3).
- `CLOSED` (not merged) → log "ACTION: PR <#> closed without merging on <branch>. Slot paused."
- No PR found → dispatch implementer (step 4).

### 3. Open PR — assess

```sh
gh pr view <pr#> --json state,mergeStateStatus,mergeable,reviews,statusCheckRollup
gh pr checks <pr#>
```

Decision tree (mirrors dispatch-skill §6a):

- **mergeStateStatus = CLEAN + claude-review LGTM** → `gh pr merge <pr#> --squash`. Log "MERGED <pr#>".
- **mergeStateStatus = UNSTABLE + pending checks** → wait one tick.
- **mergeStateStatus = UNSTABLE + actual failed blocking checks** AND no fix-push since the failure → dispatch fixer agent with the failure logs inlined. If the failures look like GH-side infra (codeload tarball download, action setup), `gh run rerun <id> --failed` instead of dispatching a fixer.
- **claude-review posted findings AND no fix push since** → dispatch fixer agent with the findings inlined.
- **mergeStateStatus = CONFLICTING** → dispatch rebase fixer.
- **Same finding recurs across 2+ cycles** → apply 3c-loop-terminator (minimal targeted fixer; do NOT redispatch with the same prompt).
- **3 fix passes exhausted on a slot** → log "ACTION: 3-pass budget exhausted on PR <#>. Slot paused."

### 4. Dispatch implementer

Use `Agent` tool with `isolation: "worktree"`, `run_in_background: true`. Prompt structure follows `.claude/skills/dispatch/SKILL.md` §"Self-contained prompts" (11-step template):

- Step 3 (ADR pre-read): paste `scripts/adr-context.sh <touched-paths>` output verbatim under "MANDATORY READING".
- Step 8 (comment-style preflag): paste the dispatch-skill's snippet.
- Step 10 (CI auto-fix loop): paste the dispatch-skill's snippet.

Log: "DISPATCHED PR <letter> agent <id> on branch <branch>".

### 5. Cleanup

When all 5 PRs are MERGED:

1. Log "WAVE COMPLETE 2026-05-26 sondage-ux-fixes".
2. Log "ACTION: maintainer triggers `deploy-api-k8s.yml context=identity` if PR E modified identity-api, AND `deploy-api-k8s.yml context=survey` if E modified survey-api. Frontend auto-deploys on Cloudflare Pages from main."
3. `CronDelete` self.
4. Exit.

## Failure modes

- Implementer agent blocked (e.g. can't reproduce E's repro headlessly): log the report verbatim; do NOT auto-redispatch. Next tick is maintainer's call.
- `gh` CLI auth issue: retry once, log "ACTION: gh CLI auth issue", `CronDelete` self.
- Two consecutive quiet ticks (no progress on any slot): single "all slots quiet" log entry; don't spam.
- GH Actions silent-drop pattern (push event creates no workflow runs): identified by zero check_suites for the head commit AND non-zero recent runs on `main`. Resolution: empty `chore(ci): re-trigger ...` commit + force-push (per the 2026-05-26 incident).

## Done condition

5 PRs merged AND:

- A: nav shows Sondage entry (manual smoke against deployed frontend).
- B: `curl -s https://wordsparrow.io/sondage | grep '<title>'` returns "Sondage — WordSparrow" at first byte.
- C: chips display human French labels; no UPPERCASE.
- D: FlagPicker visually matches Likert / Button.
- E: toggling "Supprimer aussi mes corrections proposées" returns 204 + UI confirms "Enregistré.".
