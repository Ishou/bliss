# ADR-0022: Application-Layer Deviations from ADR-0018

## Status

Accepted

## Context

ADR-0018 §1 listed the `game:application` ports as including `LobbyEventBroadcaster` and
the use cases as including `EvaluateSolved`. PR #127 implemented the application layer with
two intentional deviations from those specifications. This ADR records and justifies both
deviations so the accepted-ADR corpus remains consistent with the codebase.

## Decision

### 1. `LobbyEventBroadcaster` replaced by `List<LobbyEvent>` in `UseCaseResult`

ADR-0018 §1 named `LobbyEventBroadcaster` as a port through which use cases would push
events. The implementation instead embeds events as `List<LobbyEvent>` in the
`UseCaseResult<T>` return type.

**Rationale:** A push-to-broadcaster port would couple use cases to a transport concern —
the use case would need to inject and call an infrastructure boundary. Returning events as
values keeps the application layer pure: use cases compute state and declare events; the
API layer (Wave F) receives the result and fans out to WebSocket subscribers. The seam ADR-0018
§"Future work" cited (`LobbyRepository` and `LobbyEventBroadcaster` as the upgrade points
for multi-replica) is preserved: the API layer that fans out events is the natural adapter
boundary, and a future Redis-backed broadcaster slots in there without touching use cases.

### 2. `EvaluateSolved` inlined into `UpdateCellUseCase`

ADR-0018 §1 listed `EvaluateSolved` as a standalone use case. The implementation inlines
solved detection into `UpdateCellUseCase` via `GameSession.isSolved()` (already on the
domain object, PR #126).

**Rationale:** Splitting into two use cases would require a second `mutate` round-trip and
a re-check of the solved condition, introducing a TOCTOU window between writing the cell
and evaluating solved status. The per-lobby lock makes the write + solve-check an atomic
unit: no intermediate state is observable. `GameSession.isSolved()` encapsulates the domain
logic; a thin `EvaluateSolved` wrapper would add indirection with no independent testable
behaviour.

## Consequences

- `LobbyEventBroadcaster` does not exist as a port. Wave D (infrastructure) does not
  implement it. Wave F receives events from use-case return values and owns fan-out.
- End-of-game stats (ADR-0018 §"Consequences / Easier") are surfaced via
  `LobbyEvent.GameSolved` in the use-case result list, not through a separate use case.
- The multi-replica upgrade path (ADR-0018 §"Future work") is unaffected: `LobbyRepository`
  remains the primary seam, and the Wave F broadcast adapter is the secondary seam.
