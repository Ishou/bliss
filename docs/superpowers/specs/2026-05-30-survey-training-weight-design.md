# Survey `training_weight` from `UserRoleChanged` — Design (Spec B)

**Date:** 2026-05-30
**Status:** Approved (brainstorming) — pending implementation plan
**Bounded context:** `survey/`
**Companion ADR:** none required — this is a consumer-side change within one
bounded context, reusing the existing NATS consumer pattern and the
`UserRoleChanged` contract Spec A already shipped. (If implementation surfaces
a cross-cutting decision, raise an ADR then.)

## Why this exists

This is **Spec B** of a four-part rollout whose end goal is: *maintainer-authored
correctifs in the survey context receive "gold" training weight, but only for
correctifs created on/after 2026-05-30.* The other specs:

- **Spec A** (shipped) — identity gained a `role` (`PLAYER`/`MAINTAINER`) and
  emits `UserRoleChanged` on subject `wordsparrow.user.role-changed`.
- **Spec C** — `ExportDatasetUseCase` reads and emits `training_weight`.
- **Spec D** — wire the survey export into `build_modal_corpus.py` so the weight
  reaches the training run.

Spec B is the bridge: survey **consumes** `UserRoleChanged`, caches the
maintainer role durably, and **stamps** a `training_weight` onto the
maintainer-authored survey items. The weight is a stored, frozen, reproducible
value (not derived at export), so a training corpus exported at time T always
carries the weights that were in effect at T.

## Scope

**In scope (Spec B only):**

- A `survey_items.training_weight` column (NUMERIC, default `1.0`).
- A durable `maintainer_roles` cache table.
- A `UserRoleChangedConsumer` (+ config) mirroring the existing
  `UserDeletedConsumer`, wired into `survey-api`.
- A `GoldWindowPolicy` domain function and a `RecomputeTrainingWeightUseCase`.
- Three triggers for the recompute: the role event, new rater proposals by a
  cached maintainer, and a manual `survey-worker --recompute-training-weights`
  Job.
- Erasing the `maintainer_roles` row on `UserDeleted` (extend the existing
  consumer).
- Migration `V8__training_weight.sql`.

**Explicitly out of scope:** export reading the weight (Spec C); corpus wiring
(Spec D); any identity-side change; any change to the `UserRoleChanged` wire
contract. Tuning the actual gold multiplier value is a config concern, not a
code deliverable.

## Architecture

Hexagonal, mirroring the existing `UserDeletedConsumer` slice in `survey/`:

- **`domain/`** — `GoldWindowPolicy`: a pure function
  `weightFor(createdAt: Instant, isMaintainer: Boolean): Double`. Holds the
  single definition of "what counts as gold." Constructed with a `cutoff`
  (`Instant`) and a `goldMultiplier` (`Double`) so future, more elaborate
  windows live here without touching call sites. Zero framework deps.
- **`application/`** — `RecomputeTrainingWeightUseCase` (the one idempotent
  recompute, invoked by all three triggers); a `MaintainerRoleRepository` port
  (read/upsert/delete cached roles); and a `TrainingWeightWriter` port (writes
  `survey_items.training_weight`). The use case also needs read access to
  authorship (`proposed_by`) — reuse/extend the existing `ProposedByRepository`
  port rather than inventing a new one.
- **`infrastructure/`** — `UserRoleChangedConsumer` + `UserRoleChangedConsumerConfig`
  (mirror `UserDeletedConsumer*`); `PgMaintainerRoleRepository`; the
  `training_weight` write on the existing Postgres items repository; migration
  V8.
- **`api/`** — wire the consumer into `survey/api/.../Main.kt`, started **before**
  Ktor (so redelivery-on-boot is caught, per ADR-0049), alongside the existing
  `userDeletedConsumer`.
- **`worker/`** — extend `--bootstrap-consumer` to also create the
  role-changed durable; add `--recompute-training-weights` for a full
  re-materialize.

Konsist architecture tests must stay green: no vendor SDK imports in
`domain/`/`application/`; the NATS adapter stays in `infrastructure/`.

### Components & schema

```sql
-- V8__training_weight.sql

-- training value of a corpus item; survives GDPR erasure of the author
-- (a bare numeric weight is not PII). DEFAULT 1.0 is the neutral multiplier,
-- so the column is additive with no backfill.
ALTER TABLE survey_items
    ADD COLUMN training_weight NUMERIC NOT NULL DEFAULT 1.0
        CHECK (training_weight > 0);

-- durable cache of cross-context role state. THIS table is PII
-- (user_id -> role) and is erased on UserDeleted.
CREATE TABLE maintainer_roles (
    user_id    UUID PRIMARY KEY,
    role       TEXT NOT NULL CHECK (role IN ('player', 'maintainer')),
    changed_at TIMESTAMPTZ NOT NULL
);
```

Consumer config mirrors `UserDeletedConsumerConfig`:

- Subject: `wordsparrow.user.role-changed`
- Stream: `WORDSPARROW_USER_EVENTS` (the stream identity already owns; same one
  `user.deleted` uses)
- Durable: `survey-api-user-role-changed` (deterministic, for idempotent
  bootstrap)
- `ExplicitAck`, 30 s `ackWait`, `maxDeliver = 5`
- `bootstrap(nats)` / `deleteConsumer(nats)` statics for the pre-install Job and
  operator migrations

Locally-mirrored payload (ADR-0001 §1 forbids a cross-context `$ref`; this
mirrors `identity/api/events/UserRoleChanged.yaml`, exactly as
`UserDeletedPayload` mirrors `UserDeleted.yaml`):

```kotlin
@Serializable
data class UserRoleChangedPayload(
    val userId: String,     // UUID
    val role: String,       // "player" | "maintainer"
    val changedAt: String,  // ISO-8601 instant
)
```

## Data flow

```
UserRoleChanged(userId, role, changedAt)
   │  consumer decodes; nak() + log on parse error
   ▼
1. upsert maintainer_roles(userId, role, changedAt)
   │     (out-of-order guard: ignore if changedAt < cached changed_at)
   ▼
2. RecomputeTrainingWeightUseCase.forUser(userId)
   │     reads proposed_by rows for userId -> the authored proposed_item_ids
   │     isMaintainer = (role == 'maintainer')
   │     per item: weight = GoldWindowPolicy.weightFor(survey_items.created_at, isMaintainer)
   │       (cutoff compares the ITEM's created_at — "correctif created on/after 2026-05-30")
   ▼     UPDATE survey_items SET training_weight = ? WHERE item_id = ?
   (revocation: role flips to 'player' -> policy returns 1.0 -> items reset.)

New rater proposal by userId   (existing proposal submission use case)
   ▼  if maintainer_roles[userId] == 'maintainer':
        RecomputeTrainingWeightUseCase.forItem(newItemId)

survey-worker --recompute-training-weights   (manual / post-policy-change / safety net)
   ▼  recompute every maintainer-authored item under the current policy

UserDeleted(userId)   (existing consumer, extended)
   ▼  DELETE FROM maintainer_roles WHERE user_id = ?
        (role is PII; survey_items.training_weight stays frozen)
```

The launch scenario is the role event back-stamping: the maintainer proposes
their post-cutoff correctifs starting 2026-05-30, then Spec A's bootstrap Job
assigns the maintainer role at deploy time. The resulting `UserRoleChanged`
event drives step 2, which back-stamps every already-existing post-cutoff item.

**GDPR / reproducibility:** after author erasure, `proposed_by` rows are gone,
so recompute can no longer touch those items — their `training_weight` is frozen
at its last-computed value. This is the desired reproducibility property: the
corpus weight outlives the authorship link, and the link (the PII) is erased.

## Error handling

- **Parse failure** → `nak()` for redelivery; structured log (no `println`,
  no string concatenation in messages). Poison messages drop after
  `maxDeliver = 5` (mirrors `UserDeleted`).
- **Recompute DB failure** → `nak()` so the event redelivers. The recompute is
  idempotent (full overwrite of the affected items' weights), so reprocessing is
  safe.
- **Event loss** (upstream broadcast is fire-and-forget, may be lost) → the
  manual `--recompute-training-weights` Job is the reconciliation path. Spec A's
  threat model already declares delivery is best-effort and consumers must
  reconcile independently.
- **Out-of-order / duplicate events** → `maintainer_roles.changed_at` guard:
  ignore an event whose `changedAt` predates the cached row. Upsert + full
  recompute make duplicates harmless.

## Testing

- **Domain (TDD, near-100% on branch logic):** `GoldWindowPolicy` —
  - pre-cutoff item (any role) → `1.0`;
  - post-cutoff + maintainer → `goldMultiplier`;
  - post-cutoff + player → `1.0`;
  - boundary: item created exactly at the cutoff instant → gold (inclusive).
- **Use case (real `InMemory` repos + a recording fake at the NATS boundary —
  mock only at the boundary, per CLAUDE.md):**
  - role-grant back-stamps existing post-cutoff items, leaves pre-cutoff at
    `1.0`;
  - role-revocation resets the user's items to `1.0`;
  - new-proposal stamp weights a single item when the author is a cached
    maintainer;
  - unknown / non-maintainer author leaves items at `1.0`.
- **Infrastructure (Testcontainers, following `UserDeletedConsumerTest`):**
  - V8 migration applies cleanly; pre-existing `survey_items` rows read back
    `1.0`;
  - `PgMaintainerRoleRepository` round-trips role + `changed_at`;
  - consumer decodes a real `UserRoleChanged` payload, acks, and the recompute
    updates `survey_items.training_weight`;
  - `UserDeleted` removes the `maintainer_roles` row but leaves
    `training_weight` frozen.
- **Arch:** Konsist tests stay green (no new boundary violations).

## Risks & open points

- **Recompute scope on role events** reads all of a user's `proposed_by` rows;
  this is bounded (a single maintainer, low item counts) and runs off the event
  loop, not a request path.
- **`goldMultiplier` value** is a config placeholder (e.g. `3.0`) to be tuned
  against training results. It is wired via the existing env / chart-values
  pattern, not hard-coded in a way that needs a code change to adjust.
- **Future gold windows** beyond `createdAt >= cutoff` (e.g. per-campaign,
  date-range, graded multipliers) land entirely inside `GoldWindowPolicy` plus a
  re-run of the recompute Job — no schema or consumer change.
- The maintainer's identity `user_id` must already be assigned the maintainer
  role for any stamping to occur; that is Spec A's deploy step
  (`maintainerUserIds` in `values-prod`), not a Spec B dependency.

## Sequencing

Spec B depends on Spec A's event + subject (both shipped). Spec C depends on
Spec B's `training_weight` column. Spec D depends on Spec C's export output.
Spec B can ship and run before any export change exists — the stamped weights
simply wait, unobserved, until Spec C reads them.
