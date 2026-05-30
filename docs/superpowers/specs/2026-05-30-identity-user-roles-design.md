# Identity User Roles + `UserRoleChanged` Event — Design (Spec A)

**Date:** 2026-05-30
**Status:** Approved (brainstorming) — pending implementation plan
**Bounded context:** `identity/`
**Companion ADR:** new ADR "identity user roles + `UserRoleChanged` event" (lands in the same PR; updates `docs/adr/INDEX.md`).

## Why this exists

This is **Spec A** of a four-part rollout whose end goal is: *maintainer-authored
correctifs in the survey context receive "gold" training weight, but only for
correctifs created on/after 2026-05-30.* The other specs:

- **Spec B** — survey consumes `UserRoleChanged`, caches the maintainer role,
  and stamps a `training_weight` on rater-proposed items authored by a
  maintainer post-cutoff.
- **Spec C** — `ExportDatasetUseCase` reads and emits `training_weight`.
- **Spec D** — wire the survey export into `build_modal_corpus.py` so the weight
  actually reaches the training run.

Spec A is the foundation: a **reusable authz primitive** (a `role` on identity
users) plus the event that carries role changes across the context boundary.
The role is intentionally general — future admin / moderation / campaign-control
features are expected to consume it. The cross-context cost (ADR + threat model
+ event contract) is a deliberate investment, not waste, because the role is not
single-use.

## Scope

**In scope (Spec A only):**

- A `role` column on `identity_users` and a `Role` domain type.
- `SetUserRoleUseCase` + a `UserRoleChangedBroadcaster` port and its NATS adapter.
- An AsyncAPI event fragment `identity/api/events/UserRoleChanged.yaml`.
- An arg-dispatch entrypoint (`--set-maintainer-roles`) on identity's `main()`.
- A Helm `post-install,post-upgrade` bootstrap Job that assigns the maintainer
  role to configured user ids and emits the event.
- The companion ADR + `docs/adr/INDEX.md` update.

**Explicitly out of scope:** the survey consumer, the `training_weight` column,
export changes, corpus wiring (Specs B–D). A **runtime role-management HTTP API
is deferred** (YAGNI) until a second assignment need arises.

## Architecture

Hexagonal, matching the existing identity layout:

- `domain/` — `Role` enum; `User` gains a `role` field. No dependencies.
- `application/` — `SetUserRoleUseCase`; `UserRoleChangedBroadcaster` port.
- `infrastructure/` — migration; `PostgresUserRepository` reads/writes `role`;
  `NatsUserRoleChangedBroadcaster` adapter.
- `api/` — `main()` arg dispatch for the bootstrap command; chart Job template
  + values; event fragment under `api/events/`.

Konsist architecture tests must stay green: no vendor SDK imports in
`domain/`/`application/`; the NATS adapter lives in `infrastructure/`.

### Components

**1. Domain & persistence**

- `Role` enum in `identity/domain/.../user/`: `PLAYER`, `MAINTAINER`.
  Extensible (new variants added later without migration churn beyond the
  CHECK constraint).
- `User` data class gains `val role: Role` (default `PLAYER` at construction
  sites that create new players).
- Migration `V5__user_role.sql` (expand-and-contract, backward-compatible):

  ```sql
  -- authz primitive; DEFAULT 'player' makes this column additive (no backfill needed).
  ALTER TABLE identity_users
      ADD COLUMN role TEXT NOT NULL DEFAULT 'player'
          CHECK (role IN ('player', 'maintainer'));
  ```

  Every existing user becomes `player`. No data backfill required.

**2. The event**

- `identity/api/events/UserRoleChanged.yaml` — AsyncAPI 2.6 message fragment,
  same posture as `UserDeleted.yaml` (not CI-linted yet):

  ```yaml
  summary: A user's role changed; downstream consumers update their cached role state.
  payload:
    type: object
    required: [userId, role, changedAt]
    properties:
      userId:    { type: string, format: uuid }
      role:
        type: string
        enum: [player, maintainer]
        x-enum-varnames: [PLAYER, MAINTAINER]
      changedAt: { type: string, format: date-time }
  ```

- Subject: `wordsparrow.user.role-changed` (mirrors `wordsparrow.user.renamed`,
  `wordsparrow.user.deleted`).
- Port `UserRoleChangedBroadcaster` in `application/ports`; adapter
  `NatsUserRoleChangedBroadcaster` in `infrastructure/events`, fire-and-forget
  JetStream publish, transport errors logged and swallowed (ADR-0049 — same as
  the rename/delete broadcasters).

**3. `SetUserRoleUseCase`**

- Signature: `execute(userId: UserId, role: Role)`.
- Behaviour:
  - Load the user via `UserRepository`. If absent → return a typed
    "user not found" outcome (the bootstrap entrypoint logs and skips it; a
    configured id for a not-yet-created account is not fatal).
  - If the user's role already equals `role` → **idempotent no-op, no event
    emitted** (so re-running the bootstrap Job does not spam the subject).
  - Otherwise persist the new role and broadcast `UserRoleChanged`.
- `UserRepository` gains a way to update the role (or a focused
  `updateRole(userId, role)` method) — `PostgresUserRepository` writes the
  column; `InMemoryUserRepository` mirrors it.

**4. Assignment mechanism — bootstrap Job (configure-in-cluster)**

Chosen over an HTTP endpoint to avoid the bootstrap paradox (no admin exists to
authorize the first admin) and to honour CLAUDE.md's "configure-in-cluster, not
push-from-CI" rule.

- `identity/api/Main.kt` gains arg dispatch (mirrors
  `survey-worker --bootstrap-consumer`): when invoked with
  `--set-maintainer-roles`, it reads `MAINTAINER_USER_IDS` (comma-separated
  UUIDs, sourced from a chart value / k8s Secret), constructs the use case with
  the real Postgres + NATS adapters, runs it once per id with
  `role = MAINTAINER`, then exits. With no args, the server path is unchanged.
- Helm Job in `identity/api/deploy/chart/templates/` (e.g.
  `job-maintainer-roles.yaml`), gated by `maintainerRoleBootstrap.enabled`:
  - `helm.sh/hook: post-install,post-upgrade`
  - `helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded`
  - `command: ["java", "-jar", "/app/identity-api.jar"]`, `args: ["--set-maintainer-roles"]`
  - same selector labels as the api pod so the NATS NetworkPolicy admits it
  - `ttlSecondsAfterFinished`, `backoffLimit`, `restartPolicy: OnFailure`
    matching the survey bootstrap Job.
- Values: `maintainerRoleBootstrap.enabled` (default false in base
  `values.yaml`; true in `values-prod.yaml`) and the maintainer id list wired
  via the existing env / `envFromSecret` pattern.

## Data flow

```
operator sets values-prod: maintainerUserIds + maintainerRoleBootstrap.enabled
        │
helm upgrade --install identity-api
        │  (post-install,post-upgrade hook)
        ▼
Job: identity-api --set-maintainer-roles
        │  reads MAINTAINER_USER_IDS
        ▼
SetUserRoleUseCase(userId, MAINTAINER)  ──not found──▶ log + skip
        │ already maintainer ──▶ no-op, no event
        │ changed
        ▼
UPDATE identity_users SET role='maintainer'  +  publish wordsparrow.user.role-changed
        │
        ▼
(Spec B) survey NATS consumer caches role → gates training_weight
```

## Threat model (required by CLAUDE.md; copied into the PR body + ADR)

- **Asset:** the `maintainer` role. Today it confers gold training weight on the
  holder's correctifs; future features may gate more on it.
- **Mutation surface:** only the bootstrap Job sets roles. The id list lives in
  a k8s Secret / chart values, never in code. A DB write requires cluster
  access. **No HTTP role-mutation endpoint exists**, so there is no runtime
  privilege-escalation path and no IDOR on a role-setting route.
- **Event exposure:** `UserRoleChanged` publishes to an internal NATS subject,
  reachable only in-cluster and guarded by the existing NetworkPolicy. Payload
  carries `userId`, `role`, `changedAt` — no secrets, no PII beyond the opaque
  user id already on other user events.
- **Event-loss failure mode:** the broadcast is fire-and-forget and may be lost.
  This is tolerable because (a) the bootstrap Job re-runs on every
  `helm upgrade`, re-emitting for any id whose role actually differs, and (b)
  Spec B's consumer is required to be idempotent and to have its own
  reconciliation path. Spec A does not depend on guaranteed delivery.
- **Spoofing:** only identity publishes to the subject; survey trusts the
  in-cluster broker (same trust model as `user.deleted`).

## Testing

- **Domain:** `Role` default at player-creation sites; `User` carries the role.
  (No test for the enum itself — trivial.)
- **Use case (TDD, near-100% on the branch logic):**
  - sets a new role and broadcasts exactly once;
  - idempotent no-op when the role is unchanged — **no event emitted**;
  - user-not-found returns the typed outcome and does not broadcast.
  Use the real `InMemoryUserRepository` and a fake/recording
  `UserRoleChangedBroadcaster` (mock only at the boundary, per CLAUDE.md).
- **Infrastructure:** migration applies cleanly and existing rows read back as
  `player` (Postgres-backed repo test, following the existing identity
  persistence test pattern); `PostgresUserRepository` round-trips `role`.
- **Arch:** Konsist tests stay green (no new boundary violations).
- **Chart:** `helm-lint` / `api-chart-lint` pass for the new Job template.

## Risks & open points

- The maintainer's identity `user_id` must be known to populate
  `maintainerUserIds`. It is obtainable from the survey `proposed_by` /
  `ratings` rows for the existing correctifs (ops step at deploy time, not a
  code dependency).
- Spec B must treat the event as best-effort and reconcile independently; this
  spec deliberately does not add delivery guarantees.

## Sequencing

Spec A ships first and independently (no consumer yet — the event is simply
unobserved until Spec B lands). B depends on A's event + subject; C on B's
column; D on C's export output.
