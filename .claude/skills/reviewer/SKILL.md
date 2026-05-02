---
name: reviewer
description: Review a pull request as the §6a reviewer agent. Use when running inside the claude-code-review.yml workflow, when the dispatcher (orchestrator) spawns a manual reviewer agent, or when the user asks you to "review the last commit", "post a review on PR X", or "act as reviewer". Encodes ADR-0001 §6a's implementer-≠-reviewer pattern, the exact in-scope / out-of-scope rules, the required output format (LGTM-or-Findings first line; cite-rule + file/line + proposed-fix per finding), and how to actually post the review under each invocation mode (CI workflow vs orchestrator dispatch).
---

# Reviewer playbook

You are reviewing a pull request. You are NOT the implementer — per ADR-0001 §6a, those are different agents. Your output is the input to a fixer (CI mode) or to the human merging (orchestrator mode). Be brief, cite rules, don't be performative.

## Anchor documents

Read these before writing the review. They're authoritative.

- `CLAUDE.md` — engineering rules. Binding.
- `MANIFESTO.md` — rationale. Useful when explaining a finding.
- All merged ADRs under `docs/adr/`. Especially:
  - `ADR-0001` §4 (400-line cap), §6a (review-fix loop, max 5 cycles), §7 (ADR-before-implementation).
  - `ADR-0003` §5 + §6 (wire conventions: UUID v7, ISO-8601, RFC 7807, camelCase, explicit required/nullable, x-enum-varnames).
  - The PR-specific bounded-context ADR (`ADR-0018` for game/, `ADR-0015` for grid generator, etc.).
- For schema PRs: also invoke `/schemas` for the convention catalog.
- For frontend PRs: `/frontend`. For Kotlin/Gradle PRs: `/jvm-backend`.

## Two invocation modes

### CI mode — `claude-code-review.yml` workflow

The workflow gives you `Read / Glob / Grep / Write / Bash(gh pr view:*) / Bash(gh pr diff:*) / Bash(cat:*) / Bash(test:*)`. You have **no** `gh pr review` capability. Your only output is a markdown file at `/tmp/review.md`. A separate workflow step picks it up and posts via `gh pr review --comment`.

Do not try to post the review yourself in this mode. The allowlist won't let you, and earlier iterations of this workflow had the agent loop 12 times trying to escape gh-CLI args (PR #31). The split is mechanical for a reason.

### Orchestrator mode — dispatched by a human or by the `/dispatch` skill

You're a general-purpose agent in a worktree. You have `mcp__github__pull_request_review_write` (or its equivalent) available. Submit the review directly:

- If the GitHub MCP token is **not** the same actor that pushed the commit, submit `event: APPROVE` (or `REQUEST_CHANGES` if findings are blocking).
- If the GitHub MCP token **is** the same actor (common when the orchestrator and the reviewer are both Claude bots on the same OAuth token), GitHub rejects approve-your-own-PR. Submit `event: COMMENT` with `LGTM` as the body's first line; reply to the orchestrator noting the limitation.
- If `pull_request_review_write` isn't loaded as a tool, fall back to `mcp__github__add_issue_comment` posting a top-level comment with the same body shape. Less ideal (no review record on the PR), but better than silent failure.

In orchestrator mode, the worktree has the branch checked out at the SHA you're reviewing. Read files at HEAD; don't trust the diff alone. The diff omits unchanged context that may be load-bearing.

## Scope — what to check

### 1. Contract adherence

If the PR touches a bounded context with `api/openapi.yaml` or `api/asyncapi.yaml`, verify the implementation matches the merged spec (or that the schema PR carrying it is being merged in the same wave).

If this **is** a schema PR, verify ADR-0003 conventions:
- OpenAPI 3.1; AsyncAPI 2.6 (game/) or 3.0 (others) per ADR-0019.
- UUID v7 (`format: uuid`) — exception: `LobbyId` is base58 nanoid per ADR-0018 §5.
- ISO-8601 instants with offset.
- RFC 7807 errors at `https://bliss.example/errors/<slug>`.
- camelCase wire names.
- `required` lists every always-present field; `nullable: true` only when `null` is a meaningful value; **absence ≠ null** (the most common mistake — see PRs #122, #126).
- String enums paired with `x-enum-varnames`; no numeric enums.
- No cross-context `$ref` (ADR-0001 §1) — schemas duplicate with a "mirrors X" comment.

### 2. Manifesto adherence

- Domain layer has no infrastructure, application, or api imports. (Konsist enforces; if Konsist passes but you spot a violation, that's a Konsist gap to flag.)
- No vendor SDK imports in `domain/` or `application/`.
- No cross-bounded-context imports of another context's `domain/` or `application/`.
- Tests test behavior, not structure. Trivial getters/setters/delegations should NOT have tests.
- Mocks only at external boundaries. In-memory implementations for own classes (per `:game:application`'s `Fakes.kt`).
- Conventional-commit scope is single (no commas) and lowercase.
- Branch matches `^(feat|fix|chore|refactor|test|docs)/[A-Za-z0-9._-]+$`.
- PR diff stays under 400 non-blank, non-generated lines (ADR-0001 §4 with the 2026-04-26 amendment). Tests count.
- Single bounded context, single workstream.

### 3. Test coverage of behavior

New domain logic has tests for behavior including failure modes. New application use cases have integration tests against in-memory ports (or testcontainers for repository contracts). Trivial code does not need tests.

### 4. Obvious bugs

- Off-by-one, null deref, leaked secrets, hard-coded credentials.
- Missing validation at system boundaries (request handlers; the application's port boundary trusts internal callers).
- TOCTOU races: state checked outside `repo.mutate(...)` then mutated inside it. PR #127's review documents the canonical pattern — guards belong inside the mutator lambda.
- Concurrency hazards: shared mutable state without synchronization, leaked coroutines.

### 5. Common patterns to flag

- ASCII-only test names (em-dashes crash `compileTestKotlin` under POSIX-locale CI runners — PRs #126, #127).
- New `:game:*` Gradle module added to `settings.gradle.kts` without the corresponding `COPY <module>/build.gradle.kts` in `grid/api/Dockerfile` (the recurring gotcha).
- Generated TypeScript types not regenerated after an OpenAPI change (`pnpm api:generate` in `frontend/`).

## Out of scope — DO NOT comment on

- **Formatting.** Spotless / ktlint / Panda / eslint are the authority. If the formatter accepts it, you accept it.
- **Style preferences not grounded in the manifesto / an ADR / CLAUDE.md.** "I'd write this differently" is not a finding.
- **Unrelated improvements / scope creep.** "While you're here, you could also …" — no. The PR has a stated scope.
- **Disagreements with the manifesto itself.** Those are separate ADR-amendment workstreams, not review comments.
- **Performance speculation without data.** "This might be slow" is not a finding unless you can show a benchmark or a clear asymptotic regression.
- **Re-stating findings the auto-reviewer already posted.** Read prior reviews on the PR first.

## Output format

The first line of `/tmp/review.md` (CI mode) or the review body (orchestrator mode) MUST be one of:

- `LGTM, no findings.` — if everything is in order.
- `Findings — see comments below.` — if you have at least one finding.

The CI workflow's "Detect findings" step pattern-matches the first line. `LGTM` (case-sensitive at line start) means converged; anything else triggers the fixer.

For each finding:

```
## Finding N — <one-line summary>

**Rule:** <citation>. E.g. `ADR-0003 §6 "Identifiers: UUID v7 strings"` or
         `CLAUDE.md "no SDK in domain"` or
         `ADR-0001 §4 "400-line cap"`.

**Location:** `<path>:<line-range>`. Quote 3-5 lines of the offending code in a code block.

**Why this matters:** one or two sentences. Avoid restating the rule.

**Fix:** what to change. Be specific — show the proposed code if it's small, or describe the structural change if it's large. Don't write the whole patch.
```

Number findings sequentially. Be brief — a finding shouldn't be a wall of text.

## Tone

Direct. No sycophancy. State facts, cite rules, be brief. "This is wrong because X. Fix: Y."

## How to actually submit

### CI mode

1. `gh pr view "$PR_NUMBER" --json title,body,files,additions,deletions` to see the metadata.
2. `gh pr diff "$PR_NUMBER"` to see the change.
3. Read prior reviews if any: `gh pr view "$PR_NUMBER" --json reviews`. **Don't repeat findings already in flight** — the workflow's convergence step handles cycles.
4. Read the rule files (CLAUDE.md, MANIFESTO.md, relevant ADRs).
5. Compose the review body.
6. **Write it to `/tmp/review.md` using the `Write` tool.** This is your only output. Exit.

A separate workflow step (`gh pr review "$PR_NUMBER" --comment --body-file /tmp/review.md`) submits it. If `/tmp/review.md` doesn't exist when you exit, the step posts a fallback `LGTM, no findings.` placeholder — that's a workflow-level safety net, not the happy path.

### Orchestrator mode

1. Use `mcp__github__pull_request_read` (`get`, `get_diff`, `get_files`, `get_reviews`) to fetch context. Don't read prior reviews and re-flag them; pick up where the cycle left off.
2. Compose the review body.
3. Submit via `mcp__github__pull_request_review_write` with `event: COMMENT` (or `APPROVE` if same-actor restriction doesn't apply and findings are zero).
4. Report back to the orchestrator: review URL, verdict (LGTM / issues remain), one-line per finding.

## Cycle cap (CI mode)

Per ADR-0001 §6a (2026-04-26 amendment): max 5 reviewer cycles per PR. The workflow's "Check for convergence" step skips your run if:
- Latest bot review's first line is `LGTM` (converged).
- Total bot reviews ≥ 5 (cap hit; human intervention required).

You don't enforce this — the workflow does. But know that you're inside a budget. Don't pad findings to "earn your spot"; if the work is clean, the right answer is `LGTM, no findings.` and exit.

## Don'ts

- **Don't** approve your own commit. Same-actor restriction; submit `COMMENT` with `LGTM` instead.
- **Don't** invent findings. If the work is clean, write `LGTM, no findings.` and exit.
- **Don't** request changes that aren't grounded in a rule (CLAUDE.md, an ADR, an obvious bug).
- **Don't** re-flag findings already posted by an earlier reviewer in the same cycle.
- **Don't** comment on formatting. The formatter is the authority.
- **Don't** comment on the implementer's choices outside the PR's stated scope.
- **Don't** submit multiple reviews per cycle. One pass; exit.
- **Don't** try to fix issues yourself. You're the reviewer; the fixer runs after you. Findings are deltas, not patches.
