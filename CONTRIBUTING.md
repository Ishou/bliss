# Contributing to Bliss

Bliss is a sandbox project run with a parallel-agent workflow. The
authoritative rules live in [`CLAUDE.md`](./CLAUDE.md) and the ADRs
under [`docs/adr/`](./docs/adr/) — read those first. This file is the
short, practical onboarding pointer for a new contributor (human or
agent) who has just cloned the repo.

## One-time setup

Activate the repo-managed git hooks:

```sh
./scripts/setup-hooks.sh
```

This points `core.hooksPath` at `.githooks/`. The `pre-commit` hook
runs [`gitleaks`](https://github.com/gitleaks/gitleaks) against staged
changes and aborts the commit on any finding. Install gitleaks first
(`brew install gitleaks` on macOS or Linux Homebrew; otherwise grab a
release binary from upstream and put it on your `PATH`). The same scan
runs in CI via `.github/workflows/secret-scan.yml`, so a bypassed local
hook is still caught at the server side — but the hook is what keeps
secrets off your local machine's history in the first place.

## Branches

Branch names must match `<type>/<short-description>` where `<type>` is
one of `feat`, `fix`, `chore`, `refactor`, `test`, `docs` (enforced in
CI by `.github/workflows/branch-name.yml`). For agent workstreams,
ADR-0001 §2 prescribes `<type>/<context>-<slug>` — e.g.
`feat/frontend-grid-canvas`, `chore/ci-secret-scanning`.

The `dependabot/` and `renovate/` prefixes are exempt; the Claude bot
uses `chore/claude-` to satisfy the rule.

## Commits

Conventional commits, enforced by `commitlint` in CI:

```
<type>(<scope>): <subject>
```

`<type>` is one of `feat`, `fix`, `chore`, `refactor`, `test`, `docs`.
`<scope>` is the bounded context plus layer when relevant
(`grid-application`, `frontend-grid`, `api-grid`, `ci-secret-scan`, …).
Keep subjects under 72 characters; describe the *why* in the body.

## Pull requests

- One workstream per PR. Hard cap: 400 lines of hand-written diff
  (generated code excluded). See ADR-0001 §4.
- Squash-merge only into `main`. No merge commits.
- The PR description names the workstream, the bounded context and
  layer touched, and any schemas shipped first (ADR-0001 §3).
- Architecture tests, CI, and the secret-scan workflow must be green
  before merge.

## Where to read more

- [`CLAUDE.md`](./CLAUDE.md) — the engineering rules. Authoritative.
- [`MANIFESTO.md`](./MANIFESTO.md) — the rationale behind the rules.
- [`docs/adr/`](./docs/adr/) — every non-trivial decision and its
  trade-offs. Read the index in order; later ADRs assume earlier ones.
