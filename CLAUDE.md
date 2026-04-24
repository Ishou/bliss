# Bliss — Engineering Rules

> These rules are derived from [`MANIFESTO.md`](./MANIFESTO.md). Read it for rationale. This file is what you follow.

## Architecture

- Domain layer has ZERO dependencies on infrastructure. No DB annotations, no HTTP, no framework imports in domain code.
- Every bounded context: `domain/` → `application/` → `infrastructure/` → `api/`.
- Domain depends on nothing. Application defines ports (interfaces). Infrastructure implements adapters.
- Never import from another bounded context's domain or application package. Communicate through domain events only.
- No vendor SDK imports in `domain/` or `application/`. Cloud services go through adapters.
- API schemas (OpenAPI, protobuf, AsyncAPI) exist before implementation. Code is generated from schemas.
- APIs are versioned from day one.

## Testing

- TDD: write a failing test first, then make it pass, then refactor. No exceptions for domain logic.
- Do not write tests for trivial code (getters, setters, simple delegation). Test behavior, not structure.
- Domain logic: near-100% mutation coverage.
- Application services: integration tests with real adapters (testcontainers).
- Infrastructure adapters: contract tests against real schemas/APIs.
- E2E tests: few, covering critical user journeys only.
- No project-wide line-coverage thresholds.
- Mock external boundaries only. Never mock classes you wrote — use real instances or in-memory implementations.
- Property-based tests for serialization, parsing, and validation.

## Code Quality

- Automated formatting. No style debates. Formatter is the authority.
- Architecture tests (ArchUnit or equivalent) enforce dependency rules. These are not optional.
- Each module builds and tests independently.
- Small PRs (under 400 lines). Never bundle unrelated changes.
- Conventional commits: `feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`. Enforced in CI (commitlint).
- Branch names: `<type>/<short-description>` where `<type>` is one of the conventional types above. Enforced in CI. Bot-managed prefixes `dependabot/` and `renovate/` are exempt; the Claude bot uses `chore/claude-` so its branches satisfy the rule.

## CI/CD

- CI target: under 5 minutes for affected modules. Incremental builds, parallelized tests, aggressive caching.
- CI is the only path to production. No deploying from dev machines. No `--no-verify`.
- Feature flags: deploy dark, release bright. Flags have expiration dates — expired flags fail CI.
- Rollback is always one click.
- Database migrations must be backward-compatible (expand-and-contract).

## Developer Experience

- One command after clone to have everything running. Target: under 10 minutes.
- Dev/prod parity: same DB engine, same runtime, same container images locally and in production.
- Everything needed to build, test, and deploy lives in the repo. No tribal knowledge.
- Lock files committed. Container images pinned to digest. Builds are deterministic.

## Infrastructure

- All infrastructure defined in code, in the repo. No manual infra changes.
- Same IaC for ephemeral, staging, and production — parameterized, not duplicated.
- Immutable deployments. Never patch running instances. Never SSH into prod to fix things.
- GitOps: repo is the source of truth for system state.

## Observability

- Structured logging (JSON). Every log: correlation ID, service name, bounded context.
- No println/console.log. No string concatenation in log messages.
- Distributed tracing (OpenTelemetry) from day 1. Every external call is a span.
- RED metrics on every service endpoint. No vanity metrics.
- Alert on symptoms (error rate, latency), not causes (CPU, disk).

## Security

- SAST + dependency scanning in CI on every PR. Critical vulnerabilities fail the build.
- Secrets never in code. Injected at runtime. Git hooks prevent accidental commits.
- Least privilege: every service gets its own credentials with minimal permissions.
- Auth/authz changes always get a threat model.

## Ethics

- Fair, transparent pricing. No dark patterns, no manipulative psychology.
- Accessibility is a requirement (WCAG AA minimum), not a follow-up ticket.
- Privacy by design: collect minimum data, explain why, provide export and deletion.
- Inclusive language in code, docs, and UI. Design for the edges.
- Efficient code, right-sized infra. Tear down unused environments.
- Open-source non-competitive components. Contribute upstream.

## AI Collaboration Rules

- Challenge bad ideas. Say "this is a bad idea because..." — don't just execute.
- Every recommendation includes trade-offs: why this, what else was considered, downsides.
- Push back on requests that violate this manifesto. Explain which principle and why.
- No sycophancy. No "Great question!" when it isn't. Be honest, respectful, direct.
- Think before coding. Check if it already exists. Ask if this is the simplest solution.
- Say "I don't know" rather than guessing. Verify claims by reading actual files.
- All suggestions must align with this manifesto. Exceptions require explicit justification and an ADR.

## Continuous Improvement

- This manifesto is a living document. Challenge it, amend it via PR + ADR.
- Blameless post-mortems after incidents. Action items, not blame.
- Track DORA metrics. Review quarterly. If a principle causes consistent friction, revise it.
- Principles with 3+ documented exceptions get reviewed for revision.

## Project Structure

```
bliss/
├── CLAUDE.md              ← this file (AI rules)
├── MANIFESTO.md            ← full rationale
├── docs/
│   ├── adr/               ← architecture decision records
│   └── incidents/         ← post-mortem reports
├── <bounded-context-1>/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── api/
└── <bounded-context-n>/
```

## ADR Format

```
# ADR-NNNN: Title

## Status
Proposed | Accepted | Superseded by ADR-XXXX

## Context
What is the issue? Why does a decision need to be made?

## Decision
What was decided.

## Consequences
What becomes easier, harder, or different.
```