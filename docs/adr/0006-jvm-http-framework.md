# ADR-0006: JVM HTTP Framework

## Status

Accepted

## Context

ADR-0003 §3 deferred the choice of Kotlin HTTP framework to "the first
context's infrastructure ADR." That moment has arrived: the next
workstream introduces `grid/api/` (and the missing `grid/application/`)
so the first HTTP endpoint can land. The framework choice is a
precondition for the controllers, the content-negotiation wiring, and
any client-side dependency-graph review.

The selection space considered: **Spring Boot**, **Ktor**, **http4k**,
**Micronaut**.

ADR-0004 chose Cloudflare Pages for the static frontend and explicitly
deferred the JVM hello-world deployment. Cloudflare Workers do not run
the JVM, so framework choice does not have to optimize for an
edge-runtime constraint; it has to optimize for a small typed-Kotlin API
that will sit on a JVM host (ADR-0007 picks the deployment target). What
this ADR cares about is the in-process shape: how controllers are
written, how bodies are (de)serialized, and how live updates (SSE,
eventually WebSocket) are served.

## Decision

### 1. JVM HTTP framework: Ktor 3.x

Ktor is the framework. Trade-offs against each rejected alternative:

- **Spring Boot** — the JVM default; ecosystem depth (Spring Security,
  Spring Data, Actuator, broad observability auto-config) is real. It
  is also heavy: hello-world fat-jars around 50 MB, startup in the
  2–4 s range on a small VM, opinionated DI/AOP/auto-config that
  becomes another thing to learn before the first endpoint reads
  end-to-end. For a sandbox where the maintainer is deliberately
  learning the JVM stack, "less magic" wins. Spring stays defensible
  if the project ever grows multiple contexts that share a security
  model and benefit from Spring Data — a future ADR can re-evaluate.
- **http4k** — closest second. Pure-functional, immutable handlers,
  server-as-a-function — extremely testable, extremely small. The
  reason it loses is community size (smaller pool of examples,
  StackOverflow answers, blog posts) and a thinner adapter ecosystem,
  particularly around OpenAPI and OpenTelemetry where Ktor has
  first-party or well-maintained third-party plugins. A defensible bet
  on functional purity at the cost of slower lookup and slightly more
  custom integration code. Loses on community size, not quality.
- **Micronaut** — well-engineered, AOT compilation gives real
  cold-start advantages on serverless. Overlaps significantly with
  Spring Boot's positioning (DI-heavy, annotation-driven) without the
  ecosystem mass; less idiomatic in pure Kotlin than Ktor. No
  compelling differentiator for this project's shape.

Reasons to pick Ktor specifically:

- **Kotlin-first.** Designed by JetBrains, idiomatic coroutines,
  structured concurrency native. Reading a Ktor route handler reads
  like Kotlin, not Java with Kotlin syntax sugar.
- **Small surface.** A complete `Application` module fits on one
  screen — easy to read end-to-end, which matters under the
  manifesto's small-PR / readable-code stance.
- **First-party WebSocket and SSE.** Both ship as Ktor plugins, no
  external runtimes (see §5).
- **Plugin ecosystem covers v1 needs.** Routing, content negotiation,
  request validation, status pages, OpenTelemetry, and OpenAPI tooling
  are available without writing custom integrations.
- **Fast cold start.** ~200–500 ms on plain JVM, near-instant under
  GraalVM native image. Relevant if a future deploy target uses
  scale-to-zero.
- **Stable docs.** JetBrains-maintained, versioned, no disruptive
  churn across 2.x → 3.x.

Ktor 3.x is the version pinned. The implementation workstream commits
the exact version coordinate.

### 2. JSON serialization: kotlinx-serialization

`kotlinx-serialization-json` is the wire (de)serializer, wired into
Ktor via the `ContentNegotiation` plugin. Why:

- **First-party Kotlin.** No annotation processor; KSP-only.
- **Compatible with the OpenAPI-generated DTOs.** ADR-0003 §4 leaves
  the DTO-generation tool open; the candidates that emit
  kotlinx-serialization classes are first-class citizens in the Ktor
  ecosystem.
- **Avoids Jackson's reflection-heavy startup cost.** Jackson works
  fine on Ktor (the plugin supports it), but its reflection model is a
  tax this project does not need to pay.

Moshi was considered and rejected: smaller community on the JVM than
Jackson, no advantage over kotlinx-serialization for a Kotlin-only
producer.

### 3. Outbound HTTP: Ktor client (CIO)

For outbound HTTP — Anthropic's API for puzzle generation later, future
third-party integrations — use `io.ktor:ktor-client-cio` plus
`ktor-client-content-negotiation` and the same
`kotlinx-serialization-json` codec. Single coroutine-friendly client
across in/out, one serialization library, one set of timeouts and
retries to configure.

CIO over OkHttp/Apache: pure Kotlin, no extra non-coroutine-native
runtime, smaller dep footprint. OkHttp stays defensible if a specific
need (HTTP/3, mTLS quirks) demands it later — swap is local to
`grid/infrastructure/`.

### 4. OpenAPI integration: spec → code, not the other way

Per ADR-0003 §1 the spec is hand-written and authoritative. Ktor does
not auto-generate OpenAPI from routing, which is **correct for this
project**: generating OpenAPI from controller code makes Kotlin the
source of truth and undermines the parallel-agent contract layer
(ADR-0001 §3, ADR-0003 §1). Ktor's routing DSL is statically
inspectable enough that the contract test from ADR-0003 §8.3
(route ↔ spec parity) is tractable; the implementation workstream
wires it.

DTO generation from the spec into Kotlin classes uses a tool to be
picked by the implementation workstream (ADR-0003 §4 left this open).
The constraint added here: the generator must emit
kotlinx-serialization-compatible classes so they drop into the Ktor
`ContentNegotiation` plugin without an adapter layer.

### 5. Live-update posture: SSE for v1, WebSocket deferred

Ktor's first-party `Sse` plugin handles Server-Sent Events; the
`WebSockets` plugin handles WS. **SSE is the v1 default** for live
surfaces (scoreboard refresh, async puzzle-generation status, anything
one-way server→client).

Why SSE over WebSocket for v1:

- One-way is what v1 features need. Scoreboards, generation status,
  hints — none require a client→server channel that HTTP `POST`
  doesn't already cover.
- SSE is plain HTTP. Survives proxies, edge platforms, and corporate
  networks better than WS.
- No socket.io adapter, no Redis-for-pub-sub, no sticky-session
  configuration on the eventual deploy host (relevant for ADR-0007).
- Reconnection is trivial; the browser does it for us with a stable
  `Last-Event-ID` header.

WebSocket is reintroduced when the multiplayer feature ships (real-time
co-solve, opponent moves, latency-sensitive interactions). That gets
its own ADR with its own back-pressure, fan-out, and session-affinity
analysis. socket.io is **not** adopted here; if WS is needed it will be
raw Ktor WebSockets unless the multiplayer ADR finds a specific reason
otherwise.

### 6. Module layout

`grid/api/` is introduced as part of the implementation workstream
following this ADR — it does not exist today (ADR-0004 §1 noted the
absence). Layering:

- `grid/api/` depends on `grid/application/` (also new, also
  introduced by that workstream) which depends on `grid/domain/`.
- Ktor server and client deps live exclusively in `grid/api/` and
  `grid/infrastructure/` build files.
- `grid/domain/` stays pure Kotlin, zero framework deps, per
  ADR-0001 §1 and ADR-0003 §3. Architecture tests (ADR-0001 §5)
  enforce this.

The implementation workstream registers the new modules in
`settings.gradle.kts` and lands the first endpoint as a separate PR
under the 400-line cap.

### 7. What this ADR does NOT decide

Held for separate ADRs:

- **Server config** — port, host, request limits. Implementation
  detail of the first endpoint workstream.
- **Persistence** — Postgres or otherwise, schema, migrations. Its
  own ADR when the first persistent surface lands.
- **Authentication** — JWT, session cookies, OIDC. Decided when the
  `accounts/` bounded context is introduced.
- **Logging / tracing wiring** — structured JSON logs, OpenTelemetry
  exporter, correlation-ID plumbing. Belongs in a project-wide
  observability ADR; this ADR only commits to "Ktor has plugins."
- **Deployment target** — ADR-0007 (parallel workstream) picks where
  the JVM service runs.
- **Anthropic SDK choice** — official `anthropic-java` vs. raw HTTP
  via the Ktor client. Decided when puzzle-generation lands.

## Consequences

### Easier

- Reading a `grid/api/` module is reading Kotlin. No annotation magic
  to decode, no auto-config to mentally model.
- Coroutines compose cleanly across in (route handler) and out (HTTP
  client) boundaries. Cancellation propagates; structured concurrency
  catches forgotten work.
- Bundle and startup stay small. Container images are smaller, cold
  starts are faster, the right-sized-infra rule is honored.
- ADR-0003 §1's source-of-truth discipline is preserved by default —
  Ktor doesn't tempt anyone to flip polarity by auto-generating specs
  from controllers.
- SSE-first means the v1 deploy target can be any plain HTTP host.

### Harder

- The Spring ecosystem's free lunches (Spring Security, Spring Data,
  Actuator metrics out of the box) have to be assembled from smaller
  parts when the time comes. Mitigation: each piece gets its own ADR
  when introduced.
- Smaller hiring pool than Spring. Acceptable in a sandbox; revisit
  if contributors arrive who'd be more productive in Spring.
- Some libraries are Java-first and assume a `Servlet`/`Filter`
  model. Integrations may need a thin adapter. Manageable; flagged
  when a specific case bites.

### Different

- "JVM API code" in this repo means Ktor + kotlinx-serialization, not
  Spring + Jackson. Contributors arriving with Spring muscle memory
  read the ADRs first.
- Live-update features default to SSE, not WS. Designs that reach
  for WebSocket out of habit get pushed back to "is this actually
  bidirectional?" before the dep lands.
- The contract direction is fixed: spec → Kotlin DTOs → Ktor
  controllers. The reverse is closed off by framework choice as well
  as by ADR-0003.

## Notes

This ADR is revisited if any of the following occur:

- A v1 feature genuinely needs bidirectional real-time before
  multiplayer ships. The SSE-default rule retires early; the
  multiplayer ADR is brought forward.
- Ktor 3.x introduces a breaking change that costs more to absorb
  than the ecosystem benefit. Re-evaluate, possibly pin to the prior
  major while planning migration.
- A second bounded context with an HTTP surface needs Spring
  Security / Spring Data so badly that running two frameworks
  side-by-side becomes the lesser evil. ADR amendment or per-context
  framework pick, not a silent split.
- The maintainer's learning goal pivots (a job context that needs
  Spring fluency). Sandbox-project rationale changes; ADR is amended
  with explicit justification.
- Anthropic ships an SDK that mandates a specific HTTP client and
  the Ktor client cannot drop in cleanly. Revisit §3 only.

Implementation does not begin in this PR. The follow-up workstream
(`feat/grid-api-hello-endpoint` or similar) introduces:

- `grid/application/` and `grid/api/` modules registered in
  `settings.gradle.kts`.
- Ktor 3.x server + `ContentNegotiation` +
  `kotlinx-serialization-json` in `grid/api/build.gradle.kts`.
- Ktor client (CIO) wired in `grid/infrastructure/` only when an
  outbound call exists; not before.
- Architecture-test additions ensuring `grid/domain/` and
  `grid/application/` import zero Ktor symbols.

Each piece stays under the 400-line cap per ADR-0001 §4. The
deployment-side workstream (ADR-0007 → its implementation) is
parallel and independent.
