# ADR-0018: Game Bounded Context — AsyncAPI 2.6 Exception to ADR-0003 §5

## Status

Accepted

## Context

The game bounded context owns real-time lobby coordination and in-progress game
synchronisation over a WebSocket channel (`game/api/asyncapi.yaml`). ADR-0006
§5 deferred WebSocket support "until multiplayer ships"; that condition is met.

ADR-0003 §5 specifies **AsyncAPI 3.0** for async event surfaces. Two concrete
obstacles block applying that rule today:

1. **Spectral CI stability.** ADR-0003 §8.1 requires `spectral lint` to run on
   every AsyncAPI spec in CI. `@stoplight/spectral-rulesets` ships a production-
   ready `@asyncapi/spectral-ruleset` targeting AsyncAPI 2.x. The 3.x ruleset
   is pre-release; it cannot reliably gate PRs today.

2. **Codegen correctness.** ADR-0003 §5 names `@asyncapi/modelina` as the
   TypeScript/Kotlin codegen tool. `modelina` has incomplete AsyncAPI 3.x
   support; the `type`-discriminated dispatch pattern this protocol requires is
   materially harder to express in 3.x's channel-as-resource operation model.
   Staying on 3.x to satisfy the literal wording would mean shipping broken or
   absent generated types, directly violating ADR-0003 §3's
   "generated-types-must-be-accurate" guarantee.

## Decision

`game/api/asyncapi.yaml` uses **AsyncAPI 2.6.0**, not 3.0.

This is an explicit, bounded exception to ADR-0003 §5:

- Only `game/api/asyncapi.yaml` is affected. Any new async surface in a
  different bounded context must follow ADR-0003 §5 (or file its own ADR).
- ADR-0003 §5 is amended in intent: "AsyncAPI 2.6 minimum; 3.x when Spectral
  and modelina 3.x support is production-ready."
- A 2.6 → 3.x migration is a spec-only change (no wire-format change) and
  lands as a follow-up when tooling matures. The migration ADR supersedes
  this one.

## Consequences

### Easier

- Spectral CI gate (ADR-0003 §8.1) is operational immediately using the
  stable `@asyncapi/spectral-ruleset` 2.x package.
- `@asyncapi/modelina` generates correct TypeScript and Kotlin types from the
  2.6 spec without patching or workarounds.
- The `publish`/`subscribe` model maps directly to client→server /
  server→client framing; generated dispatch code is straightforward.

### Harder

- Spectral emits an informational `asyncapi-latest-version` note; this is
  acknowledged and suppressed rather than fixed — it is the intentional cost
  of this exception.
- When tooling matures, the 3.x migration touches the spec and all codegen
  consumers simultaneously (a bounded but non-trivial coordinated change).

### Different

- ADR-0003 §5 should be read as "AsyncAPI 2.6 minimum" for this context
  until the migration ADR supersedes it.
- Reviewers encountering `asyncapi: 2.6.0` in `game/api/asyncapi.yaml` are
  directed here; the PR description no longer needs to carry the rationale.
