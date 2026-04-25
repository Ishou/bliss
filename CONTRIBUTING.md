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
CI by `.github/workflows/branch-name.yml`). Examples:
`feat/frontend-grid-canvas`, `chore/ci-secret-scanning`. The
authoritative convention is [`CLAUDE.md`](./CLAUDE.md), which
supersedes ADR-0001 §2 on branch naming — the CI-enforced
`<type>/<short-description>` form is what matters.

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
- Rebase-merge into `main` (default; squash for noisy histories). No
  merge commits. See ADR-0001 §6.
- The PR description names the workstream, the bounded context and
  layer touched, and any schemas shipped first (ADR-0001 §3).
- Architecture tests, CI, the secret-scan workflow, and the DCO check
  must be green before merge.

## Sign-off (DCO)

Every commit must be signed off under the
[Developer Certificate of Origin 1.1](https://developercertificate.org/).
Sign-off is a single line at the end of the commit message:

```
Signed-off-by: Your Name <you@example.com>
```

Use `git commit -s` to add it automatically; `git commit -s --amend` or
`git rebase --signoff <base>` to fix existing commits. The CI workflow
`.github/workflows/dco.yml` enforces this on every PR.

By signing off, you certify that you have the right to submit your
contribution under the project's license (FSL-1.1-MIT — see
[`LICENSE`](./LICENSE)). Bot-authored commits (`*[bot]`) are exempt.

## OpenAPI

Each bounded context with an HTTP surface owns its spec at
`<context>/api/openapi.yaml` (ADR-0003 §1). The committed TypeScript types
under `frontend/src/infrastructure/api/<context>/types.ts` are generated from
that spec and must stay in sync.

After any change to a `<context>/api/openapi.yaml`, regenerate the client and
commit the diff:

```sh
pnpm --filter frontend api:generate
# or
frontend/scripts/generate-api-client.sh
```

Two CI workflows back this up: `openapi-lint.yml` runs Spectral against every
spec (warnings fail the build), and `openapi-typescript-drift.yml`
regenerates the types and fails if the committed file differs.

## Where to read more

- [`CLAUDE.md`](./CLAUDE.md) — the engineering rules. Authoritative.
- [`MANIFESTO.md`](./MANIFESTO.md) — the rationale behind the rules.
- [`docs/adr/`](./docs/adr/) — every non-trivial decision and its
  trade-offs. Read the index in order; later ADRs assume earlier ones.
