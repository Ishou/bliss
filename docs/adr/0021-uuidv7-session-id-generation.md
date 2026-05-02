# ADR-0021: UUID v7 Generation via `uuidv7` npm Package

## Status

Accepted

## Context

ADR-0018 §"Anonymous players" requires every browser client to own a stable
`sessionId` (UUID v7) that it generates locally and sends on every WebSocket
frame so the server can reattach a reconnecting client within the 30-second
reconnection window. The format requirement is UUID v7 specifically because
monotonic time-prefixed IDs simplify server-side ordering and are RFC 9562
compliant.

Two implementation options were considered:

1. **Hand-roll**: implement secure-random + monotonic-timestamp assembly
   manually using `crypto.getRandomValues()`.
2. **Use the `uuidv7` package**: a published npm package (~1 KB gzipped,
   zero runtime dependencies) that is RFC 9562 compliant.

Hand-rolling was rejected: getting the monotonic-timestamp + clock-seq logic
right under concurrent tab scenarios and across browsers is non-trivial. Bugs
in hand-rolled ID generation are subtle and hard to detect in tests. The `uuidv7`
package provides a well-tested, maintained implementation of exactly this logic,
and its size is negligible.

## Decision

The `frontend` module uses the `uuidv7` npm package (`^1.2.1`) for all new
UUID v7 generation. It is the only source of UUID v7 IDs in the frontend;
no other module or context generates them.

Acceptance of pre-existing IDs (for backwards compatibility with older clients)
accepts any well-formed UUID string regardless of version, because the wire
schema specifies `format: uuid` (version-agnostic). Only *new* generations
use v7.

## Consequences

### Easier

- UUID v7 generation is RFC 9562 compliant with no custom implementation to
  maintain.
- The package is zero-dependency and ~1 KB gzipped; bundle impact is
  contained and only materialises when a route loader imports the helper.

### Harder

- The `frontend` now has one additional runtime dependency. Any future audit
  of the dependency graph must account for it.
- If `uuidv7` is abandoned or has a security issue, a migration is required.
  At ~1 KB gzipped with zero deps, the migration path is hand-rolling or
  switching to `crypto.randomUUID()` polyfilled for non-v7 use cases.
