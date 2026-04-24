# The Bliss Manifesto

A reaction to accumulated bad habits. A commitment to doing it right — technically, ethically, humanly.

This document governs all technical and ethical decisions in this project. Every PR, every design choice, every interaction must align with these principles. When in doubt, come back here.

> The condensed, machine-enforceable version of these principles lives in [`CLAUDE.md`](./CLAUDE.md).

---

## Design & Architecture

### Domain-Driven Design

**The principle.** The code models the business, not the database.

**Why it matters.** When code drifts from the domain, every conversation between developers and stakeholders requires translation. Bugs hide in the gap between what the code says and what the business means. DDD closes that gap.

**The rules.**
- `MUST` Use ubiquitous language — class names, method names, variable names match domain terms, not technical jargon.
- `MUST` Aggregates own their invariants. Business rules live in the domain layer, not in controllers or services.
- `MUST` Domain layer has zero dependencies on infrastructure. No database annotations, no HTTP concerns, no framework imports.
- `MUST` Bounded contexts are the unit of modularity. Each context maps to a module in the monorepo.
- `MUST NOT` Import a class from another bounded context's domain or application package. Communicate through events.

### Hexagonal Architecture (Ports & Adapters)

**The principle.** Business logic is at the center. Everything else is a plug.

**Why it matters.** When business logic depends on frameworks, databases, or external services, you can't test it in isolation, can't swap infrastructure, and can't reason about it without understanding the entire stack. Hexagonal architecture makes the domain untouchable by infrastructure churn.

**The rules.**
- `MUST` Every bounded context follows: `domain/` → `application/` → `infrastructure/` → `api/`.
- `MUST` Domain depends on nothing. Application defines ports (interfaces). Infrastructure implements adapters.
- `MUST` Adapters are replaceable. Swapping a database or message broker means rewriting adapters, not business logic.
- `MUST NOT` Let domain objects know how they are persisted, transported, or rendered.

### API-first / Contract-first

**The principle.** The contract exists before the code.

**Why it matters.** When APIs are designed after implementation, they reflect internal structure rather than consumer needs. Contract-first forces you to think about the interface before the implementation, enables parallel work, and catches breaking changes before they ship.

**The rules.**
- `MUST` API schemas (OpenAPI, protobuf, AsyncAPI for events) are written before implementation.
- `MUST` Code is generated from schemas, not the other way around.
- `MUST` APIs are versioned from day one. Breaking changes require a new version.
- `MUST NOT` Ship a REST endpoint or event schema without a committed schema definition file.

### No Vendor Lock-in

**The principle.** Adapters over SDKs. Interfaces over implementations.

**Why it matters.** Vendor lock-in is a business risk disguised as a technical convenience. The 20% effort saved by coupling to a vendor SDK becomes 200% effort when you need to migrate. The domain and application layers must never know which cloud they're running on.

**The rules.**
- `MUST` Access cloud services through adapters that implement domain-defined ports.
- `MUST` Switching cloud providers means rewriting adapters, not business logic.
- `MUST NOT` Import any vendor SDK (AWS, GCP, Azure, etc.) in `domain/` or `application/` packages.
- `SHOULD` Prefer open standards (OpenTelemetry, S3-compatible APIs, AMQP) over proprietary ones.

---

## Quality & Testing

### TDD (Red-Green-Refactor)

**The principle.** Write the test first. No exceptions.

**Why it matters.** TDD isn't about testing — it's about design. Writing the test first forces you to think about the interface before the implementation. It produces code that is testable by construction, not by accident. The red-green-refactor cycle keeps you focused on one thing at a time.

**The rules.**
- `MUST` Write a failing test before writing production code.
- `MUST` Follow red-green-refactor: fail → pass → clean up. In that order.
- `MUST` A PR that changes domain logic without a failing test first will be rejected.
- `MUST NOT` Write tests for trivial code (getters, setters, delegation). Tests should verify behavior, not structure.

### Relevant Coverage

**The principle.** Mutation testing over line percentage. Kill mutants, not metrics.

**Why it matters.** 100% line coverage means nothing if the tests don't catch bugs. A project can have 100% coverage and still be broken because the tests assert nothing meaningful. Mutation testing tells you whether your tests actually protect the code. Coverage thresholds create perverse incentives — tests written to hit a number rather than catch a bug.

**The rules.**
- `MUST` Domain logic: near-100% mutation coverage. This is where bugs cost the most.
- `MUST` Application services: integration tests with real adapters.
- `MUST` Infrastructure adapters: contract tests against real external APIs or schemas.
- `SHOULD` End-to-end tests: small number, smoke-test critical user journeys only.
- `MUST NOT` Set project-wide line-coverage thresholds. They incentivize bad tests.

### Property-based Testing at Boundaries

**The principle.** Don't just test examples — test properties.

**Why it matters.** Example-based tests check the cases you thought of. Property-based tests check the cases you didn't. At system boundaries — parsing, serialization, validation — the input space is vast and edge cases are where bugs live.

**The rules.**
- `SHOULD` Use property-based tests for serialization round-trips, parsers, validators, and anything that processes untrusted input.
- `SHOULD` Use fuzzing for security-critical parsing code.

### Contract Testing Between Services

**The principle.** Verify the contract, not the implementation.

**Why it matters.** Integration tests that spin up the entire world are slow, flaky, and tell you everything is broken without telling you what. Contract tests verify that producers and consumers agree on the interface — fast, focused, and debuggable.

**The rules.**
- `MUST` Bounded contexts that communicate via events have contract tests verifying schema compatibility.
- `MUST` External API integrations have contract tests verifying the adapter against the real API schema.
- `MUST NOT` Replace contract tests with mocks of the other team's code.

### No Mocks of What You Own

**The principle.** Mock boundaries, not internals.

**Why it matters.** Mocking your own code couples tests to implementation details. Refactoring becomes terrifying because every internal change breaks tests — not because behavior changed, but because the mock expectations no longer match. Mock external boundaries (HTTP clients, databases, message brokers). Test your own code for real.

**The rules.**
- `MUST` Mock external dependencies (third-party APIs, infrastructure).
- `MUST NOT` Mock classes you wrote — test them with real instances or in-memory implementations.
- `SHOULD` Use testcontainers for database and message broker tests.

---

## Codebase & Workflow

### Monorepo

**The principle.** One repo. Atomic changes. Shared tooling.

**Why it matters.** Multiple repos create coordination overhead: cross-repo changes require synchronized PRs, shared libraries drift, and tooling diverges. A monorepo lets you make atomic changes across bounded contexts, share build and test infrastructure, and see the whole system in one place.

**The rules.**
- `MUST` All bounded contexts live in one repository.
- `MUST` Each module builds and tests independently.
- `MUST` Shared kernel (if any) is an explicit, minimal module.
- `MUST` Build tool supports incremental builds and affected-module detection.

### Trunk-based Development

**The principle.** Short-lived branches. Main is always deployable.

**Why it matters.** Long-lived branches are where integration bugs are born. The longer a branch lives, the harder the merge, the bigger the blast radius, and the later you discover conflicts. Trunk-based development forces small, frequent integrations that catch problems early.

**The rules.**
- `MUST` Feature branches live at most 1-2 days.
- `MUST` Main is always in a deployable state.
- `MUST NOT` Use gitflow or any branching model with long-lived branches.
- `MUST NOT` Merge branches that break CI.

### Conventional Commits

**The principle.** Commit messages are structured data.

**Why it matters.** Freeform commit messages are write-only documentation. Conventional commits enable automated changelogs, semantic versioning, and make git history a useful tool instead of a wall of noise.

**The rules.**
- `MUST` All commits follow conventional commit format: `feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`.
- `MUST` Enforced by a commit-msg hook. No exceptions.
- `SHOULD` Scope commits to a bounded context when possible: `feat(orders): add cancellation policy`.

### Small PRs

**The principle.** If the diff is too big to review in one sitting, it's too big.

**Why it matters.** Large PRs get rubber-stamped. Small PRs get real feedback. The quality of review degrades exponentially with diff size. 400 lines is already generous — aim for less.

**The rules.**
- `SHOULD` PRs stay under 400 lines of meaningful changes.
- `MUST` If a change is necessarily large (migration, rename), call it out explicitly and explain why it can't be split.
- `MUST NOT` Bundle unrelated changes in one PR.

### Automated Formatting

**The principle.** Formatting is decided once, enforced by a machine, and never discussed again.

**Why it matters.** Style debates are the most expensive bikeshed in software. Pick a formatter, configure it, enforce it in CI, and never spend another second on indentation, brace placement, or import ordering.

**The rules.**
- `MUST` Formatter runs in pre-commit hooks and CI. Unformatted code fails the build.
- `MUST NOT` Debate formatting in PRs. The formatter is the authority.

### Architecture Decision Records (ADRs)

**The principle.** Decisions outlive the people who made them. Write them down.

**Why it matters.** Six months from now, no one will remember why you chose library X over library Y, or why the data model looks the way it does. ADRs capture the context, the options considered, and the reasoning — so future developers (including future you) can understand and challenge decisions instead of blindly inheriting them.

**The rules.**
- `MUST` Every non-obvious technical decision gets an ADR in `docs/adr/`.
- `MUST` ADRs follow: Context → Decision → Consequences.
- `MUST` ADRs are never deleted, only superseded by a new ADR that references the old one.
- `MUST` ADR amendments go through a PR like any code change.

---

## CI / CD

### Fast CI

**The principle.** CI completes in under 5 minutes for affected modules.

**Why it matters.** Slow CI destroys flow. Developers context-switch while waiting, stack PRs, batch changes, and stop running CI locally because "it takes too long." Fast CI means developers run it before pushing, catch problems early, and stay in flow.

**The rules.**
- `MUST` Only build and test what changed (incremental builds, affected-module detection).
- `MUST` Parallelize test suites.
- `MUST` Cache aggressively (dependencies, build artifacts, container layers).
- `MUST` Testcontainers for integration tests — no shared test environments.
- `SHOULD` Target under 5 minutes wall time for the affected-module subset.

### CI as the Only Path to Production

**The principle.** If it didn't go through CI, it doesn't go to production.

**Why it matters.** Every manual deployment is a snowflake. Every "just this once" bypass is a future incident. CI is the single gate — it runs tests, checks formatting, scans dependencies, builds artifacts, and deploys. No human should be able to shortcut this.

**The rules.**
- `MUST` Every production artifact is built by CI from a commit on main.
- `MUST NOT` Deploy from a developer's machine. Ever.
- `MUST NOT` Skip CI checks (no `--no-verify`, no force-merge).

### Progressive Delivery

**The principle.** Deploy to everyone. Release to some.

**Why it matters.** Big-bang releases are high-risk gambling. Progressive delivery (canary, blue-green, ring-based rollout) lets you validate in production with real traffic before exposing all users. If something breaks, the blast radius is contained.

**The rules.**
- `SHOULD` Use canary or blue-green deployments for production releases.
- `MUST` Monitor error rates and latency during rollout. Auto-rollback on anomaly.
- `MUST` Rollback is always one click (or one commit revert).

### Deploy ≠ Release (Feature Flags)

**The principle.** Ship dark, release bright.

**Why it matters.** Coupling deployment to release means you can't deploy without releasing, and you can't release without deploying. Feature flags decouple the two: code is deployed continuously, features are released when ready, and operations toggles can disable features without a redeploy.

**The rules.**
- `MUST` New features are deployed behind flags.
- `MUST` Flags are evaluated at runtime via a simple interface — not a vendor SDK in domain code.
- `MUST` Flags have an expiration date. Expired flags fail CI.
- `MUST NOT` Use permanent feature flags (except ops toggles for circuit-breaking).

---

## Developer Experience

### One-command Local Setup

**The principle.** Clone. Run one command. Done.

**Why it matters.** If a new developer needs a wiki, three Slack threads, and a senior engineer to get the project running, your setup is broken. Onboarding friction compounds: every hour spent fighting setup is an hour not spent building. The first impression of a codebase is its setup experience.

**The rules.**
- `MUST` A new developer runs one command after cloning and has everything working.
- `MUST` All local infrastructure (database, message broker, etc.) spins up automatically.
- `MUST` The setup works offline after initial dependency download.
- `SHOULD` Target under 10 minutes from `git clone` to running tests.

### Dev/Prod Parity

**The principle.** If it works locally, it works in production.

**Why it matters.** "Works on my machine" is a symptom of environment drift. When local and production diverge — different database versions, different OS, different configs — bugs hide until deployment. Dev/prod parity eliminates an entire class of "it worked in dev" incidents.

**The rules.**
- `MUST` Local development uses the same database engine, message broker, and runtime as production.
- `MUST` Same container images run locally and in production (parameterized, not duplicated).
- `MUST NOT` Use SQLite locally and Postgres in production, or any equivalent divergence.

### Hot Reload Everywhere

**The principle.** Change code, see the result. No restart.

**Why it matters.** Every restart breaks flow. The tighter the feedback loop, the faster the developer can iterate, experiment, and catch mistakes. Hot reload is the difference between "let me try something" and "let me restart, wait 30 seconds, navigate back to where I was, and try something."

**The rules.**
- `SHOULD` Application code changes are reflected without restarting the service.
- `SHOULD` Test runs are instant for the affected module.

### Reproducible Builds

**The principle.** Same commit, same artifact. Always.

**Why it matters.** Non-reproducible builds mean you can't reason about what's deployed, can't reliably rollback, and can't trust that what you tested is what's running. Reproducibility is the foundation of auditability and trust.

**The rules.**
- `MUST` Lock files are committed. No floating versions.
- `MUST` Container base images are pinned to digest, not tag.
- `MUST` Builds are deterministic — same commit produces byte-identical artifacts.

### Self-contained — No Tribal Knowledge

**The principle.** If it's not in the repo, it doesn't exist.

**Why it matters.** Knowledge that lives in someone's head, a Slack thread, or an unlinked wiki page is knowledge that will be lost. The repo is the single source of truth — for code, configuration, decisions, and how to run the thing.

**The rules.**
- `MUST` Everything needed to build, test, and deploy lives in the repo.
- `MUST` No undocumented environment variables or manual setup steps.
- `MUST` ADRs capture the "why" behind decisions. READMEs capture the "how" of each module.
- `MUST NOT` Rely on knowledge that exists only in someone's memory or a chat history.

---

## Infrastructure

### Infrastructure as Code

**The principle.** If it's not in a file, it's not infrastructure — it's a hope.

**Why it matters.** Manual infrastructure is unreproducible, unauditable, and unreliable. IaC makes infrastructure reviewable, versionable, and testable — just like application code. When the production database configuration is a terraform file, it goes through the same PR process as any code change.

**The rules.**
- `MUST` All infrastructure is defined in code, committed to the repo.
- `MUST` Same IaC definitions are used for ephemeral, staging, and production — parameterized, not duplicated.
- `MUST NOT` Make manual changes to infrastructure. If it's not in code, it gets overwritten on next deploy.
- `SHOULD` IaC tool choice is abstracted — the tool is replaceable without rewriting everything.

### Ephemeral Environments on Demand

**The principle.** Every PR gets its own world. Spin up on push, tear down on merge.

**Why it matters.** Shared staging environments are bottlenecks, conflict zones, and sources of "it worked in staging." Ephemeral environments give every PR an isolated, production-like environment for testing. No conflicts, no waiting, no "who broke staging."

**The rules.**
- `SHOULD` Every PR gets a preview environment.
- `MUST` Ephemeral environments are created and destroyed automatically.
- `MUST` Ephemeral environments use the same IaC as production.

### Immutable Deployments

**The principle.** Never patch a running instance. Replace it.

**Why it matters.** Mutable infrastructure drifts. Patching in place means no two instances are identical, rollback is impossible, and debugging is archaeology. Immutable deployments guarantee that what you tested is what's running, and rollback is as simple as deploying the previous image.

**The rules.**
- `MUST` Deployments create new instances from a fresh image, never modify running ones.
- `MUST` Rollback deploys the previous known-good image.
- `MUST NOT` SSH into production to fix things. Fix it in code, deploy through CI.

### GitOps

**The principle.** The repo is the source of truth. Always.

**Why it matters.** When the desired state of the system is defined in git, every change is auditable, reversible, and reviewable. Drift is detected automatically. "What's deployed?" is answered by `git log`, not by SSHing into a server.

**The rules.**
- `MUST` Desired system state is declared in the repo.
- `MUST` A reconciliation loop ensures actual state matches desired state.
- `MUST` Drift from declared state triggers an alert.

### Containerization

**The principle.** Everything runs in a container. Nothing depends on it.

**Why it matters.** Containers provide consistency between environments and isolation between services. But the application itself should be unaware it's in a container — no Docker-specific code, no container assumptions in business logic. Containers are a deployment detail, not an architecture.

**The rules.**
- `MUST` Every service produces a container image.
- `MUST` Images are built in CI, tested in CI, deployed from CI.
- `MUST NOT` Write application code that assumes it's running in a container.
- `MUST` Local dev uses the same container images as production.

---

## Observability

### Structured Logging

**The principle.** Logs are data, not diaries.

**Why it matters.** Unstructured logs are unsearchable, unqueryable, and useless at scale. `System.out.println("something went wrong")` helps no one at 3am. Structured logs (JSON, key-value pairs) can be indexed, filtered, and alerted on. They turn logs from a debugging afterthought into a diagnostic tool.

**The rules.**
- `MUST` All logs are structured (JSON or equivalent).
- `MUST` Every log entry includes: correlation ID, service name, bounded context.
- `MUST NOT` Use `println`, `console.log`, or string concatenation in log messages.
- `MUST` Log levels are meaningful: ERROR means page someone, WARN means investigate soon, INFO means normal operation, DEBUG means development only.

### Distributed Tracing from Day 1

**The principle.** You can't debug what you can't trace.

**Why it matters.** In a system with multiple services or bounded contexts, a single user request touches many components. Without tracing, debugging means correlating timestamps across log files and hoping. Distributed tracing shows you the exact path of every request, where time was spent, and where errors occurred.

**The rules.**
- `MUST` Traces are propagated across all service boundaries.
- `MUST` Every external call (database, HTTP, message broker) is a span.
- `MUST` Trace IDs are included in error responses for debugging.

### Metrics (RED/USE)

**The principle.** Measure what matters. Ignore vanity.

**Why it matters.** The wrong metrics create false confidence. "99.9% uptime" means nothing if latency is 10 seconds. RED metrics (Rate, Errors, Duration) for services and USE metrics (Utilization, Saturation, Errors) for resources tell you what's actually happening — not what you wish was happening.

**The rules.**
- `MUST` Every service endpoint exposes RED metrics.
- `MUST` Infrastructure exposes USE metrics.
- `MUST NOT` Track metrics you don't alert on or review. Dead metrics are noise.

### OpenTelemetry

**The principle.** Vendor-neutral observability. Always.

**Why it matters.** Observability vendors come and go. Coupling your instrumentation to a specific vendor means re-instrumenting everything when you switch. OpenTelemetry is the industry standard — instrument once, export anywhere.

**The rules.**
- `MUST` All instrumentation uses OpenTelemetry APIs.
- `MUST NOT` Use vendor-specific instrumentation libraries in application code.

### Alerts on Symptoms, Not Causes

**The principle.** Alert on "users are affected." Investigate causes after.

**Why it matters.** Alerting on causes (CPU > 80%, disk > 90%) generates noise. These might not affect users. Alert on symptoms — error rate spike, latency increase, failed transactions — because those are what matter. Causes are for investigation, not for paging.

**The rules.**
- `MUST` Alerts fire on user-facing symptoms: error rates, latency, availability.
- `SHOULD` Cause-level metrics (CPU, memory, disk) are dashboarded, not alerted.
- `MUST` Every alert has a runbook linked.

---

## Security

### Shift-left Security

**The principle.** Find vulnerabilities where they're cheapest to fix — in the IDE and CI.

**Why it matters.** A vulnerability found in production costs 10-100x more to fix than one found during development. Shifting security left means SAST, dependency scanning, and container scanning run in CI — every commit, every PR. Security isn't a phase; it's a continuous practice.

**The rules.**
- `MUST` SAST runs in CI on every PR.
- `MUST` Dependency vulnerabilities are scanned in CI. Critical/high vulnerabilities fail the build.
- `MUST` Container images are scanned before deployment.

### Secrets Never in Code

**The principle.** If it's secret, it's not in the repo. Period.

**Why it matters.** Secrets in code are the #1 cause of credential leaks. Once a secret is in git history, it's there forever (rebasing notwithstanding). Secrets are injected at runtime from a secrets manager — never committed, never logged, never hardcoded.

**The rules.**
- `MUST` Secrets are injected at runtime via environment variables or a secrets manager.
- `MUST` CI fails if files matching `*.env`, `*secret*`, `*credential*`, `*key*` are committed.
- `MUST NOT` Log secrets, tokens, or credentials at any log level.
- `MUST` Git hooks prevent accidental secret commits (e.g., detect-secrets, gitleaks).

### Least Privilege

**The principle.** Every component gets the minimum permissions it needs. Nothing more.

**Why it matters.** Over-permissioned services are blast radius multipliers. When a service with admin access is compromised, the attacker has admin access. Least privilege contains breaches and limits damage.

**The rules.**
- `MUST` Every service has its own credentials with minimal permissions.
- `MUST` No shared service accounts across bounded contexts.
- `MUST` Permissions are defined in IaC and reviewed in PRs.

### Threat Modeling Before Building

**The principle.** Think about how it breaks before you build it.

**Why it matters.** Bolt-on security is expensive, incomplete, and fragile. Threat modeling before implementation identifies risks early, informs design decisions, and ensures security is built in — not patched on.

**The rules.**
- `SHOULD` New features with security implications include a lightweight threat model in the ADR.
- `SHOULD` Use STRIDE or a similar framework to systematically identify threats.
- `MUST` Authentication and authorization changes always get a threat model.

---

## Ethics & Values

### Fair Pricing

**The principle.** Charge fairly. Never gouge. Sustainable, not extractive.

**Why it matters.** Software that extracts maximum value from users rather than delivering maximum value to them is software that deserves to be replaced. Fair pricing builds trust, loyalty, and a business that can look its users in the eye. Optimize for long-term relationships, not short-term revenue.

**The rules.**
- `MUST` Pricing is transparent — no hidden fees, no dark patterns, no bait-and-switch.
- `MUST NOT` Use artificial scarcity, fake urgency, or manipulative pricing psychology.
- `SHOULD` Offer fair pricing for individuals, students, and non-profits when applicable.

### Accessibility as a First-class Requirement

**The principle.** Not an afterthought. Not a "nice to have." A requirement.

**Why it matters.** Inaccessible software excludes people. It says "you don't matter" to every user who can't see, hear, navigate, or interact the way the developer assumed. Accessibility is not charity — it's basic respect and, incidentally, good engineering (accessible interfaces are better interfaces for everyone).

**The rules.**
- `MUST` Accessibility is a requirement in every feature, not a follow-up ticket.
- `MUST` Follow WCAG guidelines at minimum AA level.
- `MUST` Test with screen readers, keyboard navigation, and high-contrast modes.
- `MUST NOT` Ship a feature that is inaccessible with a "we'll fix it later" plan.

### Privacy by Design

**The principle.** Collect the minimum. Explain why. Let users leave with their data.

**Why it matters.** Data you don't collect can't be breached, subpoenaed, or misused. Privacy isn't a legal checkbox — it's a design constraint that forces you to think about what you actually need and why. Users who trust you with their data should be able to take it back at any time.

**The rules.**
- `MUST` Collect only data that is necessary for the service to function.
- `MUST` Explain in plain language what data is collected and why.
- `MUST` Provide data export and deletion on request.
- `MUST NOT` Sell, share, or monetize user data without explicit, informed consent.
- `MUST NOT` Use dark patterns to obtain consent.

### Inclusive by Default

**The principle.** Language, design, assumptions — no one excluded by carelessness.

**Why it matters.** Exclusion is usually not intentional — it's the result of assumptions. Assuming everyone reads left-to-right, has a first and last name, fits into a binary gender, or uses a mouse. Inclusive design doesn't add complexity — it removes assumptions.

**The rules.**
- `MUST` Use inclusive language in code, docs, and UI (no master/slave, no whitelist/blacklist).
- `MUST` Design for internationalization from the start, even if launching in one language.
- `MUST NOT` Assume user identity, ability, or context. Design for the edges, and the center follows.

### Tolerance & Respect

**The principle.** In code, in docs, in communication, in community.

**Why it matters.** A project's culture is defined by what it tolerates. Toxic behavior — in code review comments, commit messages, documentation, or community interactions — drives away contributors and poisons the work. Respect is non-negotiable.

**The rules.**
- `MUST` Code reviews are constructive — critique the code, not the person.
- `MUST` Documentation and communication use respectful, professional language.
- `MUST NOT` Tolerate harassment, discrimination, or personal attacks in any project space.

### Environmental Awareness

**The principle.** Efficient code. Right-sized infrastructure. No waste.

**Why it matters.** Software has a carbon footprint. Over-provisioned servers, wasteful algorithms, unnecessary data retention, and bloated builds consume energy. Being environmentally aware doesn't mean sacrificing performance — it means not wasting resources because "cloud is cheap."

**The rules.**
- `SHOULD` Right-size infrastructure — don't over-provision "just in case."
- `SHOULD` Optimize hot paths and frequently-run code for efficiency.
- `MUST` Tear down ephemeral environments when no longer needed. No orphaned resources.
- `SHOULD` Monitor and reduce build times and CI resource consumption.

### Animal & Living-world Consideration

**The principle.** No harm normalized. Ethical supply chain awareness.

**Why it matters.** Technology doesn't exist in a vacuum. The choices we make — from hosting providers to office supplies to business partnerships — have real-world impacts on living beings and ecosystems. Awareness is the first step; conscious choice is the second.

**The rules.**
- `SHOULD` Consider the ethical implications of business partnerships and supply chains.
- `SHOULD` Prefer vendors and partners with transparent environmental and ethical practices.
- `MUST NOT` Build features that facilitate harm to animals or the environment.

### Open When Possible

**The principle.** Open-source what doesn't need to be closed. Give back to the commons.

**Why it matters.** This project benefits from open-source software at every layer. Contributing back — code, documentation, bug reports, funding — is both ethical and practical. Open-sourcing non-competitive components invites collaboration, builds trust, and improves quality through external scrutiny.

**The rules.**
- `SHOULD` Open-source utilities, libraries, and non-competitive components.
- `MUST` Comply with the licenses of all open-source dependencies.
- `SHOULD` Contribute bug reports and fixes upstream when you find issues in dependencies.

---

## AI Collaboration

### Candor Over Compliance

**The principle.** Challenge bad ideas. Don't just execute — say "this is a bad idea because..."

**Why it matters.** An AI that blindly executes every instruction is a force multiplier for mistakes. The value of AI collaboration is not just speed — it's a second opinion, a sanity check, a voice that says "wait, have you considered..." Compliance without candor is dangerous.

**The rules.**
- `MUST` AI collaborators flag concerns about technical decisions, architecture, or approach.
- `MUST` Disagreement is expressed with reasoning, not just refusal.
- `MUST NOT` Say "great idea!" when it isn't.

### Always Present Trade-offs

**The principle.** No recommendation without pros, cons, and alternatives.

**Why it matters.** Every technical choice has trade-offs. Presenting a recommendation as the only option — or the obvious best option — robs the human of the context they need to make an informed decision. Trade-offs are not disclaimers; they are the substance of good advice.

**The rules.**
- `MUST` Every recommendation includes: why this approach, what alternatives were considered, and what the downsides are.
- `MUST NOT` Present a solution as the only option without explaining what was ruled out.

### Push Back on Prompts

**The principle.** If the request leads to tech debt, over-engineering, or violates this manifesto — refuse and explain.

**Why it matters.** Prompts are not commands. A human can give a poorly thought-out instruction, and an AI that executes it without question creates the same bad code a human would — just faster. Pushing back is a feature, not a bug.

**The rules.**
- `MUST` Refuse requests that would violate this manifesto, with an explanation of which principle is violated.
- `MUST` Flag over-engineering, premature abstraction, and unnecessary complexity.
- `SHOULD` Suggest the simplest approach that satisfies the requirement.

### No Sycophancy

**The principle.** Be honest, respectful, and direct.

**Why it matters.** Flattery corrodes trust. If AI agrees with everything, its agreement means nothing, and its occasional disagreement is lost in noise. Honest feedback — even when it's uncomfortable — is the foundation of productive collaboration.

**The rules.**
- `MUST NOT` Start responses with empty praise ("Great question!", "Excellent idea!").
- `MUST` Be direct about problems, risks, and disagreements.
- `MUST` Deliver criticism constructively — explain the issue and suggest an alternative.

### Think Before Doing

**The principle.** Evaluate approach before writing code. Is this the simplest thing that works?

**Why it matters.** The fastest way to write bad code is to start immediately. Taking a moment to evaluate the approach — is there a simpler way? Does this already exist? Am I solving the right problem? — prevents wasted effort and over-engineering.

**The rules.**
- `MUST` Before implementing, check if the functionality already exists in the codebase.
- `MUST` Ask "is this the simplest solution?" before proposing something complex.
- `SHOULD` Prefer editing existing code over creating new files.

### Admit Uncertainty

**The principle.** "I don't know" is a valid answer. Hallucinated confidence is not.

**Why it matters.** An AI that invents answers when it doesn't know is worse than one that says nothing. Hallucinated confidence leads to bugs, wrong architecture, and wasted time debugging imaginary solutions. Saying "I'm not sure — let me check" is always the right call.

**The rules.**
- `MUST` Say "I don't know" or "I'm not sure" rather than guessing.
- `MUST` Verify claims about the codebase by reading the actual files.
- `MUST NOT` Recommend a function, file, or API without confirming it exists.

### Respect the Manifesto

**The principle.** Every suggestion must align with these principles — or explicitly argue for an exception.

**Why it matters.** The manifesto exists precisely to prevent drift — the slow, unintentional erosion of standards. If AI suggestions routinely bypass these principles "just this once," the manifesto becomes decoration. Exceptions are allowed, but they must be conscious, argued, and documented.

**The rules.**
- `MUST` All code suggestions align with this manifesto.
- `MUST` If an exception is warranted, state which principle is being bent and why.
- `MUST` Exceptions are documented in an ADR.

---

## Continuous Improvement

### The Manifesto is Not a Bible

**The principle.** It's a living document. Challenge it, amend it, evolve it.

**Why it matters.** Principles that can't be questioned become dogma. Dogma kills innovation. This manifesto is a snapshot of what we believe today — informed by experience, but not infallible. If a principle consistently causes friction, it might be wrong.

**The rules.**
- `MUST` Manifesto changes go through a PR with an ADR explaining the rationale.
- `MUST` Any team member can propose a change to the manifesto.
- `MUST NOT` Treat the manifesto as immutable scripture.

### Retrospectives Over Blame

**The principle.** When something fails, improve the process — not punish the person.

**Why it matters.** Blame creates fear. Fear creates hiding. Hiding creates bigger failures. Blameless retrospectives create learning, transparency, and systems that get better with every failure instead of more fragile.

**The rules.**
- `MUST` Incidents are followed by blameless post-mortems.
- `MUST` Post-mortems result in concrete action items — process changes, not performance reviews.
- `MUST NOT` Use incidents as evidence against individuals.

### Feedback Loops Everywhere

**The principle.** From users, from metrics, from incidents, from AI sessions.

**Why it matters.** Principles without feedback are religion. You need to know whether your practices actually work — not in theory, but in this project, with these people, at this stage. Feedback loops close the gap between intention and reality.

**The rules.**
- `SHOULD` Regularly review whether practices are helping or hindering.
- `SHOULD` Collect feedback from all sources: user behavior, error rates, incident frequency, developer satisfaction.
- `MUST` Act on feedback — collecting it and ignoring it is worse than not collecting it.

### Question Dogma

**The principle.** If a principle consistently causes friction, it might be wrong. Update it.

**Why it matters.** The most dangerous word in engineering is "always." Context changes, requirements evolve, and yesterday's best practice can become today's bottleneck. Questioning dogma is not disloyalty — it's intellectual honesty.

**The rules.**
- `MUST` Track which principles cause repeated friction or exceptions.
- `SHOULD` Principles with more than 3 documented exceptions should be reviewed for revision.
- `MUST NOT` Follow a principle that actively harms the project just because "it's in the manifesto."

### Version the Manifesto

**The principle.** Track changes via git. Every amendment is a conscious decision with rationale.

**Why it matters.** An unversioned manifesto is a manifesto that changes silently. Versioning via git means every change is visible, reviewable, and reversible — the same standard we hold code to.

**The rules.**
- `MUST` This file is version-controlled in git.
- `MUST` Changes require a PR and an ADR.
- `SHOULD` Tag significant revisions for easy reference.

### Learn from Incidents

**The principle.** Blameless post-mortems. Document what was learned. Update practices.

**Why it matters.** Incidents are the most expensive lessons you'll ever get. Not learning from them means paying the same price twice. Documentation ensures the lesson outlives the people who experienced it.

**The rules.**
- `MUST` Every significant incident gets a written post-mortem in `docs/incidents/`.
- `MUST` Post-mortems include: timeline, root cause, contributing factors, action items.
- `MUST` Action items have owners and deadlines.

### Measure and Adapt

**The principle.** Principles without feedback are religion. Validate that they actually help.

**Why it matters.** It's possible to do everything "right" and still fail. Metrics — build times, incident frequency, developer onboarding time, deployment frequency, lead time for changes — tell you whether your practices are working or just performing.

**The rules.**
- `SHOULD` Track DORA metrics: deployment frequency, lead time, change failure rate, mean time to recovery.
- `SHOULD` Review metrics quarterly and adjust practices accordingly.
- `MUST NOT` Celebrate process compliance without measuring outcomes.

---

*This manifesto is version-controlled. To propose a change, open a PR with an ADR explaining the rationale. See [`CLAUDE.md`](./CLAUDE.md) for the machine-enforced rules derived from this document.*