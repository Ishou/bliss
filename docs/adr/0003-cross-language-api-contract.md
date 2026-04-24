# ADR-0003: Cross-Language API Contract

## Status

Accepted

## Context

Bliss has a Kotlin/JVM backend (per project goals) and a TypeScript/React
frontend (ADR-0002). Two languages cannot share types directly. The
manifesto already mandates schema-first APIs (`api/` per bounded context,
OpenAPI/AsyncAPI before implementation, versioned from day one) — this ADR
makes the schema-first discipline concrete: which formats, which tooling,
which conventions, and which CI gates.

ADR-0001 §3 established that schemas are the coordination layer for parallel
agents. That rule is binding only if (a) the schema files actually exist
before implementations start, and (b) the toolchain enforces "the schema is
the truth" instead of letting either side drift. This ADR specifies that
toolchain.

The selection space considered:

- **Schema source of truth:** hand-written OpenAPI YAML vs. generated from
  Kotlin annotations (Springdoc) vs. generated from TS Zod schemas.
- **TypeScript client tooling:** `openapi-typescript` + `openapi-fetch` vs.
  `orval` vs. `openapi-generator` vs. `oazapfts` vs. `swagger-typescript-api`.
- **Kotlin server tooling:** server-stub generation (`openapi-generator`) vs.
  hand-written controllers validated against the spec at boot/CI.
- **Async/event contract:** AsyncAPI vs. plain JSON Schema vs. ad-hoc.
- **Validation duplication:** generated client+server validators vs. one
  side validates, the other trusts.

## Decision

### 1. OpenAPI 3.1 YAML, hand-written, as source of truth

Each bounded context with an HTTP surface ships its own spec at
`<context>/api/openapi.yaml`. The YAML is hand-written (or generated from a
schema DSL, but never reverse-engineered from controller annotations).

Rationale:

- **Source of truth must be readable by both sides equally.** Generating
  OpenAPI from Spring annotations makes Kotlin the source of truth and
  reduces TypeScript to a downstream consumer. That breaks ADR-0001 §3 —
  agents on either side must be able to *propose schema changes* by editing
  the spec, not by editing one language's controllers.
- **Hand-written YAML stays under 400 lines per context for a long time** and
  is reviewable in a single sitting, which fits the manifesto's PR cap.
- **OpenAPI 3.1 aligns with JSON Schema 2020-12**, which means the same
  schema files can drive runtime validation on both sides without translation
  layers.

`<context>/api/` may also contain:
- `examples/` — request/response examples used by contract tests and
  documentation.
- `schemas/` — shared component schemas split out of the main YAML when it
  grows past a single reviewable file.

### 2. Versioning from day one

All HTTP routes are prefixed with `/v1/`. Breaking changes require a new
major version (`/v2/`); additive changes stay on the current version.

A version is retired only after every consumer has migrated. This is enforced
by humans, not tooling, at this scale.

### 3. TypeScript client: `openapi-typescript` + `openapi-fetch`

- `openapi-typescript` generates types only — no runtime, just `.d.ts`.
- `openapi-fetch` is the runtime client (~1 KB gzipped), fully typed against
  the generated types, no class hierarchy, no axios indirection.

Rejected alternatives:
- **`orval`** — generates React Query hooks too. Useful, but couples the
  client to a state library before that decision is made (ADR-0002 §8 defers
  state management).
- **`openapi-generator`** — Java-based, slow, generates large class
  hierarchies that bloat the bundle. Wrong fit for a game client.
- **`oazapfts` / `swagger-typescript-api`** — fine, but smaller communities
  and less idiomatic with TanStack Router patterns than `openapi-fetch`.

The generated TS client lives in `frontend/src/infrastructure/api/<context>/`
and is the *only* thing in `frontend/` allowed to import generated code.
Application and UI layers consume application hooks that wrap the client.

### 4. Kotlin server: schema-validated hand-written controllers

The Kotlin side does **not** use server-stub generation. Controllers are
hand-written using the chosen JVM HTTP framework (decided in a later
infrastructure ADR), and a CI step validates that:

- every operation declared in the spec has a matching route;
- every route exposed by the server is declared in the spec;
- request/response shapes match the spec at the type level.

Rejected: generating Kotlin server stubs from OpenAPI. The generated code is
verbose, hard to read, and creates a tight coupling between spec layout and
controller class structure. The validation-instead-of-generation approach
keeps the Kotlin code idiomatic and the spec authoritative without one
dictating the other.

Kotlin DTOs corresponding to spec component schemas live in
`<context>/api/src/.../dto/` and are generated from the spec at build time
using a lightweight tool (decision deferred to the infrastructure ADR for
the first context that needs it). DTOs do not leak into `domain/` or
`application/`; the API layer maps DTOs ↔ domain types.

### 5. Async/event contract: AsyncAPI 3.0

Real-time and event-driven surfaces (SSE, WebSocket, message bus, future
multiplayer) are described in AsyncAPI 3.0 at
`<context>/api/asyncapi.yaml`. The same source-of-truth, hand-written
discipline as OpenAPI applies. Tooling on the TS side is less mature than
OpenAPI; for v1, types are generated with
`@asyncapi/modelina` and the runtime is hand-written. This is acceptable
because async surfaces are unlikely to exist in the first few sprints.

### 6. Wire conventions

These conventions are part of the contract; agents do not relitigate them per
PR.

- **Identifiers:** UUID v7 strings (`"01JE9D…"`-style), never integers,
  never opaque strings.
- **Dates and times:** ISO-8601 strings, always with timezone offset for
  instants (`2026-04-24T15:30:00Z`), date-only for calendar dates
  (`2026-04-24`). No epoch millis on the wire.
- **Money:** integer minor units (cents) plus a separate ISO-4217 currency
  code. Never floats.
- **Nullability:** OpenAPI `required: [...]` lists every present field;
  `nullable: true` is set explicitly when `null` is a meaningful value.
  Absence and `null` are distinct.
- **Enums:** `string` enums with explicit `x-enum-varnames` for code
  generation. Numeric enums forbidden — they break additivity.
- **Pagination:** cursor-based, `?cursor=...&limit=...`, response envelope
  `{ items, nextCursor }`. No offset pagination for unbounded sets.
- **Errors:** RFC 7807 (`application/problem+json`) with a stable
  `type` URI per error class.
- **Casing:** `camelCase` on the wire (matches both TypeScript and Kotlin
  idiom; spares one side a transformation).

### 7. Validation lives at the boundary, on both sides

- **Kotlin side:** every controller validates inbound DTOs against the spec
  at request time (`jakarta.validation` or framework equivalent, fed by
  generated DTOs).
- **TypeScript side:** Zod schemas generated from OpenAPI via
  `openapi-zod-client` or equivalent, applied at the infrastructure layer
  for inbound responses (defensive — the server might be ahead of the
  client). Outbound requests are validated by the type system; runtime
  validation is unnecessary for outbound payloads.

Validation duplication is acceptable because both sides derive their
validators from the same spec. Drift is impossible by construction.

### 8. CI gates

These are required, not advisory. They run on every PR.

1. **Spec linting:** `spectral lint` on every `<context>/api/openapi.yaml`
   and `asyncapi.yaml`. Fails the build on warnings.
2. **TS client regen-and-diff:** the CI re-runs `openapi-typescript` and
   `openapi-zod-client` and fails the build if the generated files differ
   from what is committed. This means the committed generated code is
   always in sync with the spec.
3. **Kotlin server-spec parity:** a custom check (or
   `openapi-generator-maven-plugin --skip-overwrite=false --dry-run`-style
   approach) verifies every spec operation has a matching route and every
   route is declared in the spec.
4. **Schema-only PRs are gated separately** (per ADR-0001 §3): the spec
   change merges first, then producer and consumer implementation PRs land
   in parallel.

### 9. Contract tests

Per the manifesto: "Infrastructure adapters: contract tests against real
schemas/APIs."

- **Kotlin side:** every controller has at least one test that posts the
  spec's `examples/` payloads and asserts the response matches the spec.
- **TypeScript side:** every infrastructure adapter has at least one test
  that calls a mocked server replaying the spec's `examples/` and asserts
  the application layer consumes the result correctly.
- **End-to-end:** Pact (or Dredd) runs the spec against a running server in
  CI for the critical paths only. Few, focused, not exhaustive.

## Consequences

### Easier

- Schema-first becomes mechanically enforced, not just culturally aspired
  to. Drift fails CI; it cannot ship.
- Agents on either side can propose contract changes by editing the YAML —
  the cross-language coordination problem reduces to "review the schema
  PR."
- Type safety extends to the wire on both sides without manual sync.
- Validation rules exist exactly once (the spec) and are mechanically
  derived for both runtimes.
- Wire conventions are written down and not re-debated per PR.

### Harder

- Hand-written OpenAPI YAML has a learning curve. Mitigation:
  `<context>/api/examples/` and a starter template in the first
  context's `api/` directory.
- A schema change is a two-step dance (spec PR, then implementation PRs).
  This is the same cost ADR-0001 §3 already accepted; this ADR makes it
  unavoidable.
- Generated files are committed (TS types, Zod schemas, Kotlin DTOs). The
  diff is visible per PR, which is intentional — it reveals what actually
  changes on the wire — but reviewers must learn to skim generated diffs.

### Different

- Backend changes that affect the wire surface are no longer an internal
  Kotlin matter. They cross the schema boundary and follow the parallel-PR
  workflow.
- The spec files become the most-read documents in the repo. They deserve
  the same reviewer attention as production code.
- Frontend code looks the same regardless of which JVM framework backs each
  context — `openapi-fetch` calls are identical. Backend framework choice
  is reversible without frontend churn.

## Notes

Decisions deferred to later ADRs:

- The Kotlin HTTP framework (Ktor, Spring Boot, http4k, Micronaut) — chosen
  in the first context's infrastructure ADR.
- The exact tool for Kotlin DTO generation from OpenAPI.
- The deployment topology (one service vs. modular monolith vs. microservices)
  — does not affect the contract's shape, only its hosting.
- Authentication and authorization on the wire — decided when the
  `accounts/` context is introduced; will appear as `securitySchemes` in the
  affected specs.

This ADR is revisited if any of the following occur:

- Hand-written OpenAPI becomes a productivity bottleneck in measurable PR
  cycle time. Switch to a schema DSL (e.g. TypeSpec) is then on the table.
- A third runtime joins the project (e.g. mobile-native via something other
  than Capacitor). The wire conventions stand; the toolchain section
  expands.
- Async surfaces grow large enough that AsyncAPI tooling immaturity becomes
  painful. Codegen stack is then re-evaluated.
